package com.voxymap;

import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * On-demand tile builder for the unified map. When a visible tile has neither seed, live nor
 * cached data, it scans the Voxy section keys (built once) and decodes only the sections that
 * intersect that tile. This lets the tile map grow beyond the original global Snapshot rectangle.
 *
 * <p>A small dedicated I/O pool handles tile requests; the (possibly thousands of) sections of one
 * tile are decoded on a second pool in parallel, then merged in key order so the result is identical
 * to a single-threaded build. Callbacks are forwarded to the client thread.</p>
 */
public final class VoxyTileSource {
    private static final int INDEX_MAGIC = 0x56585349;
    private static final int INDEX_VERSION = 2;
    private final Path storage;
    private final Path indexCache;
    private final long minWX, minWZ;
    private final int pixelBlocks;
    private final int maxLod;
    private final java.util.function.Supplier<Set<Long>> waterIds;
    private final ExecutorService io = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "voxymap-tile-source");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /**
     * Decodes the sections of one tile in parallel. A coarse map scale puts thousands of sections
     * into a single tile, and that tile used to be decoded on one io thread while every other core
     * idled. Decoding one section is pure (read-only RocksDB get + zstd + LUT scan); the merge that
     * follows stays strictly in the original key order, so the produced pixels are identical to the
     * sequential implementation.
     */
    private final ExecutorService decodePool = Executors.newFixedThreadPool(
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1)), r -> {
                Thread t = new Thread(r, "voxymap-section-decode");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    /** Sections per decode/merge batch: bounds the decoded columns held in memory (8 KiB each). */
    private static final int DECODE_BATCH_SECTIONS = 256;
    /** Sections per parallel decode task: small enough to balance, large enough to amortise. */
    private static final int DECODE_CHUNK_SECTIONS = 32;
    private static final ThreadLocal<ByteBuffer> KEY_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN));
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean indexed;
    /**
     * Serialises index rebuilds against tile decodes. A read-only RocksDB instance keeps its own
     * version/memtable view and NEVER sees writes made after it was opened, so refreshing the map
     * has to close and reopen it. Decodes hold the read lock, the reopen takes the write lock:
     * the connection is only closed once no decode can still be reading it.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock dbLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    /** duration of the last completed index scan; callers pace their rescans by it. */
    private volatile long lastIndexScanNanos;
    /** bumped to discard queued/running decode work after the viewport moved */
    private final java.util.concurrent.atomic.AtomicInteger requestGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * tile -> section keys. Written by the index scan (replaced atomically as a whole map) and
     * incrementally by live section events (see {@link #addLiveKey(long)}); a decode may read it
     * concurrently, so every {@link KeyList} is internally synchronized.
     */
    private volatile java.util.concurrent.ConcurrentHashMap<Long, KeyList> byTile =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** live section keys received before the index was usable; merged when it becomes ready */
    private final java.util.Set<Long> pendingLiveKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** distinct live keys already merged into the current index (dedupe: live events repeat keys) */
    private final java.util.Set<Long> liveKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** set when live-key dedupe memory hits its cap; the owner should schedule one full rescan */
    private volatile boolean rescanRequested;
    /** true when the index came from the disk cache but its signature no longer matches the DB */
    private volatile boolean indexFromStaleCache;
    /**
     * Background full re-index. Runs on its own thread with its own read-only RocksDB handle, so a
     * rescan never blocks tile decodes (the old index keeps serving until the new map is swapped).
     */
    private final java.util.concurrent.ExecutorService indexPool =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "voxymap-index-scan");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final AtomicBoolean scanInFlight = new AtomicBoolean();
    /** live keys that arrived while a background scan was running; merged before the swap */
    private final java.util.Queue<Long> liveDuringScan = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** set when a background scan swapped in a new index; the store re-queries its tiles once. */
    private volatile boolean indexSwapped;
    private Object db;
    private final List<Object> handles = new ArrayList<>();
    private Object sectionsHandle;
    private volatile Set<Long> ownWaterIds = Set.of();
    /**
     * Decoded coarse sections are shared by several neighbouring tiles (especially at low zoom);
     * a small LRU avoids reading/decoding the same 32³ section four times per tile batch.
     */
    private final java.util.Map<Long, long[]> sectionCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, long[]> eldest) {
                    return size() > 384;
                }
            });

    private static final class KeyList {
        long[] keys = new long[16];
        int size;

        synchronized void add(long key) {
            grow();
            keys[size++] = key;
        }

        private void grow() {
            if (size == keys.length) keys = java.util.Arrays.copyOf(keys, size + size / 2 + 1);
        }

        /** Copy of the current keys, safe against a concurrent live-key add. */
        synchronized long[] snapshot() {
            return java.util.Arrays.copyOf(keys, size);
        }
    }

    public VoxyTileSource(Path storage, long minWX, long minWZ, int pixelBlocks, int maxLod,
                          Set<Long> waterIds) {
        this(storage, minWX, minWZ, pixelBlocks, maxLod, () -> waterIds, null);
    }

    public VoxyTileSource(Path storage, long minWX, long minWZ, int pixelBlocks, int maxLod,
                          java.util.function.Supplier<Set<Long>> waterIds) {
        this(storage, minWX, minWZ, pixelBlocks, maxLod, waterIds, null);
    }

    public VoxyTileSource(Path storage, long minWX, long minWZ, int pixelBlocks, int maxLod,
                          java.util.function.Supplier<Set<Long>> waterIds, Path indexCache) {
        this.storage = storage;
        this.minWX = minWX;
        this.minWZ = minWZ;
        this.pixelBlocks = Math.max(1, pixelBlocks);
        this.maxLod = maxLod;
        this.waterIds = waterIds;
        this.indexCache = indexCache;
    }

    public int indexedTileCount() {
        return indexed ? byTile.size() : 0;
    }

    /**
     * Fast existence check after the section index is built. An unindexed source returns true
     * (unknown) so callers still perform the normal cache/source request.
     */
    public boolean hasTileData(int tx, int tz) {
        return !indexed || byTile.containsKey(tileKey(tx, tz));
    }

    /** Drops queued decode work for an obsolete viewport without closing the DB connection. */
    public void cancelPending() {
        requestGeneration.incrementAndGet();
    }

    public void request(int tx, int tz, Consumer<long[]> callback) {
        if (closed.get()) return;
        final int generation = requestGeneration.get();
        final long submittedNs = System.nanoTime();
        if (TileTrace.enabled()) TileTrace.stage(tx, tz, "src_submit", "gen=" + generation);
        try {
            io.submit(() -> requestOnWorker(tx, tz, generation, submittedNs, callback));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // closed between the check above and the submit: the caller will never get a callback
            // (it is being disposed anyway), do not let it escape into the render thread
        }
    }

    private void requestOnWorker(int tx, int tz, int generation, long submittedNs,
                                 Consumer<long[]> callback) {
        if (closed.get() || generation != requestGeneration.get()) return;
        final long workNs = System.nanoTime();
        if (TileTrace.enabled()) {
            TileTrace.timed(tx, tz, "src_queue", submittedNs, "worker-start");
        }
        try {
            long[] pixels;
            // A read lock keeps the DB connection alive for the whole decode; the reopen in
            // invalidateIndex() waits for it, so a decode can never read a closed handle.
            dbLock.readLock().lock();
            try {
                ensureIndex();
                if (generation != requestGeneration.get()) return;
                pixels = buildTile(tx, tz);
            } finally {
                dbLock.readLock().unlock();
            }
            if (TileTrace.enabled()) {
                TileTrace.timed(tx, tz, "src_decode", workNs,
                        "pixels=" + (pixels == null ? "null" : String.valueOf(pixels.length)));
            }
            if (closed.get() || generation != requestGeneration.get()) return;
            Minecraft.getInstance().execute(() -> callback.accept(pixels));
        } catch (Throwable t) {
            if (generation == requestGeneration.get()) {
                if (TileTrace.enabled()) {
                    TileTrace.stage(tx, tz, "src_error", t.getClass().getSimpleName());
                }
                Minecraft.getInstance().execute(() -> callback.accept(null));
            }
        }
    }

    private synchronized void ensureIndex() throws Exception {
        if (!RocksBridge.available() || storage == null) {
            indexed = true;
            pendingLiveKeys.clear();
            return;
        }
        if (db == null) {
            db = RocksBridge.openReadOnly(storage.toString(), handles);
            if (db == null || handles.size() < 3) {
                indexed = true;
                pendingLiveKeys.clear();
                return;
            }
            sectionsHandle = handles.get(1);
            // The tile source must not decode water as solid just because the async global liquid-id
            // load has not finished yet: read the same mapping table directly from this connection.
            if (waterIds.get().isEmpty()) {
                ownWaterIds = MapData.loadWaterIdsFromDb(db, handles.get(2));
            }
        }
        if (indexed) {
            // A cheap reopen (see reopenDb()) only replaces the snapshot; the index is still valid.
            drainPendingLiveKeys();
            return;
        }
        final long indexStartNs = System.nanoTime();
        if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_scan_start", "pb=" + pixelBlocks);
        long signature = RocksBridge.latestSequence(db);
        if (signature < 0) signature = storageSignature();
        if (loadIndexCache(signature)) {
            indexed = true;
            lastIndexScanNanos = 0L; // came from the disk cache: a fresh close/reopen is cheap
            drainPendingLiveKeys();
            return;
        }
        // Build the index into a fresh map and swap it in at the end: decodes in flight keep using
        // the previous snapshot instead of being cancelled by an in-place clear().
        final java.util.concurrent.ConcurrentHashMap<Long, KeyList> built =
                new java.util.concurrent.ConcurrentHashMap<>();
        Object it = RocksBridge.newIterator(db, sectionsHandle);
        try {
            RocksBridge.seekToFirst(it);
            while (RocksBridge.isValid(it)) {
                byte[] kb = RocksBridge.key(it);
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    addKeyTo(built, key);
                }
                RocksBridge.next(it);
            }
        } finally {
            RocksBridge.close(it);
        }
        byTile = built;
        indexed = true;
        liveKeys.clear();
        indexFromStaleCache = false;
        drainPendingLiveKeys();
        lastIndexScanNanos = System.nanoTime() - indexStartNs;
        saveIndexCacheAsync(signature);
        if (TileTrace.enabled()) {
            TileTrace.timed(-1, -1, "index_scan_done", indexStartNs, "tiles=" + byTile.size());
        }
    }

    /**
     * Merges live section keys received while the index was not ready. Live events deliver exactly
     * the section keys Voxy is writing, so an open-map session can keep its index current without
     * rescanning RocksDB every few seconds.
     */
    private void drainPendingLiveKeys() {
        if (pendingLiveKeys.isEmpty()) return;
        java.util.Iterator<Long> it = pendingLiveKeys.iterator();
        while (it.hasNext()) {
            Long key = it.next();
            it.remove();
            mergeLiveKey(key);
        }
    }

    private void mergeLiveKey(long key) {
        if (key <= 0) return;
        int lvl = (int) ((key >>> 60) & 0xF);
        if (lvl > maxLod) return;
        if (!liveKeys.add(key)) return;
        addKeyTo(byTile, key);
    }

    /** Adds one section key to the given tile map (one entry per intersecting tile). */
    private void addKeyTo(java.util.Map<Long, KeyList> map, long key) {
        int lvl = (int) ((key >>> 60) & 0xF);
        if (lvl > maxLod) return;
        int scale = 1 << lvl;
        int sx = (int) ((key << 36) >> 40);
        int sz = (int) ((key << 12) >> 40);
        long tileWorld = (long) LiveTileStore.TILE_PIXELS * pixelBlocks;
        long wx0 = (long) sx * 32 * scale;
        long wz0 = (long) sz * 32 * scale;
        int tx0 = (int) Math.floorDiv(wx0 - minWX, tileWorld);
        int tx1 = (int) Math.floorDiv(wx0 + 32L * scale - 1 - minWX, tileWorld);
        int tz0 = (int) Math.floorDiv(wz0 - minWZ, tileWorld);
        int tz1 = (int) Math.floorDiv(wz0 + 32L * scale - 1 - minWZ, tileWorld);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                map.computeIfAbsent(tileKey(tx, tz), k -> new KeyList()).add(key);
            }
        }
    }

    /** One-shot: a background scan has swapped in a new index and tiles should re-query. */
    public boolean consumeIndexSwapped() {
        if (!indexSwapped) return false;
        indexSwapped = false;
        return true;
    }

    /**
     * Full index rebuild in the background. Uses a separate read-only connection so decodes keep
     * running against the old index/handle; the new map replaces {@code byTile} atomically when it
     * is complete. This removes the old "scan blocks every source worker" stall on first open and
     * during refresh.
     */
    private void startBackgroundScan() {
        if (closed.get() || !RocksBridge.available() || storage == null) return;
        if (!scanInFlight.compareAndSet(false, true)) return;
        try {
            indexPool.submit(() -> {
                try {
                    scanIndexIndependent();
                } finally {
                    scanInFlight.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            scanInFlight.set(false);
        }
    }

    private void scanIndexIndependent() {
        final long scanStartNs = System.nanoTime();
        if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_scan_start", "pb=" + pixelBlocks + " async");
        List<Object> hs = new ArrayList<>();
        Object ownDb = null;
        try {
            ownDb = RocksBridge.openReadOnly(storage.toString(), hs);
            if (ownDb == null || hs.size() < 3) return;
            Object ownSections = hs.get(1);
            long signature = RocksBridge.latestSequence(ownDb);
            if (signature < 0) signature = storageSignature();
            final java.util.concurrent.ConcurrentHashMap<Long, KeyList> built =
                    new java.util.concurrent.ConcurrentHashMap<>();
            Object it = RocksBridge.newIterator(ownDb, ownSections);
            try {
                RocksBridge.seekToFirst(it);
                while (RocksBridge.isValid(it)) {
                    byte[] kb = RocksBridge.key(it);
                    if (kb != null && kb.length == 8) {
                        long sectionKey = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                        addKeyTo(built, sectionKey);
                    }
                    RocksBridge.next(it);
                }
            } finally {
                RocksBridge.close(it);
            }
            // Section keys that arrived while we were scanning: they belong to the new index too.
            Long pending;
            while ((pending = liveDuringScan.poll()) != null) {
                addKeyTo(built, pending);
            }
            byTile = built;
            indexed = true;
            indexFromStaleCache = false;
            indexSwapped = true;
            liveKeys.clear();
            pendingLiveKeys.clear();
            lastIndexScanNanos = System.nanoTime() - scanStartNs;
            saveIndexCacheAsync(signature);
            if (TileTrace.enabled()) {
                TileTrace.timed(-1, -1, "index_scan_done", scanStartNs, "tiles=" + built.size() + " async");
            }
        } catch (Throwable t) {
            System.out.println("[voxymap] background index scan failed: " + t);
        } finally {
            for (Object h : hs) RocksBridge.close(h);
            RocksBridge.close(ownDb);
        }
    }

    /** Duration of the last completed index scan (0 when the index came from the disk cache). */
    public long lastIndexScanNanos() {
        return lastIndexScanNanos;
    }

    /** Cheap-ish RocksDB storage fingerprint: path + file size/mtime over the top two levels. */
    private long storageSignature() {
        long h = 1125899906842597L;
        try (java.util.stream.Stream<Path> stream = Files.walk(storage, 2)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                try {
                    h = h * 31 + p.getFileName().toString().hashCode();
                    h = h * 31 + Files.size(p);
                    h = h * 31 + Files.getLastModifiedTime(p).toMillis();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return h;
    }

    private boolean loadIndexCache(long signature) {
        if (indexCache == null || !Files.isRegularFile(indexCache)) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexCache)))) {
            if (in.readInt() != INDEX_MAGIC || in.readInt() != INDEX_VERSION) return false;
            long cachedSignature = in.readLong();
            // RocksDB's latestSequence changes for background writes/compaction even when the
            // section key set is unchanged. The cached key set is still useful: use it
            // immediately, and let the deferred rescan replace it if the world changed.
            boolean stale = cachedSignature != signature;
            if (in.readLong() != minWX || in.readLong() != minWZ
                    || in.readInt() != pixelBlocks || in.readInt() != maxLod) return false;
            int count = in.readInt();
            if (count < 0 || count > 2_000_000) return false;
            java.util.concurrent.ConcurrentHashMap<Long, KeyList> loaded =
                    new java.util.concurrent.ConcurrentHashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int n = in.readInt();
                if (n < 0 || n > 1_000_000) return false;
                KeyList list = new KeyList();
                list.keys = new long[Math.max(4, n)];
                for (int k = 0; k < n; k++) list.keys[k] = in.readLong();
                list.size = n;
                loaded.put(key, list);
            }
            byTile = loaded;
            indexFromStaleCache = stale;
            if (TileTrace.enabled()) {
                TileTrace.stage(-1, -1, stale ? "index_cache_stale" : "index_cache_hit",
                        "tiles=" + byTile.size());
            }
            return true;
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(indexCache);
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /** True when the index restored from disk no longer matches the database signature. */
    public boolean indexFromStaleCache() {
        return indexFromStaleCache;
    }

    /**
     * Cheap refresh of the read-only snapshot: closes the handle so the next request reopens it and
     * can see sections written since the last open. The section index (and its disk cache) is kept,
     * so no full key scan and no in-flight decode cancellation is needed.
     */
    public void reopenDb() {
        if (closed.get()) return;
        if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_reopen", "keep-index");
        try {
            io.submit(() -> {
                dbLock.writeLock().lock();
                try {
                    // decoded section values may have been refined in the DB: drop the shared decode
                    // cache so a later refill sees the new values, but keep byTile (key set).
                    sectionCache.clear();
                    closeHandlesKeepIndex();
                } finally {
                    dbLock.writeLock().unlock();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // closing: nothing to refresh
        }
    }

    /**
     * Adds one live section key to the index. Called from Voxy's live-section worker; the index may
     * not exist yet (queued) and duplicate keys are filtered (Voxy re-sends the same section many
     * times while it refines LOD data).
     */
    public void addLiveKey(long key) {
        if (closed.get()) return;
        if (!indexed) {
            pendingLiveKeys.add(key);
            return;
        }
        if (scanInFlight.get()) {
            // A background scan is building a fresh map from its own snapshot: queue keys so they
            // are merged before the swap instead of touching the soon-to-be-replaced index.
            liveDuringScan.add(key);
            return;
        }
        if (liveKeys.size() >= 131_072) {
            // dedupe memory bound reached: ask the owner for one full rescan and skip adding until
            // it ran (the scan clears both sets)
            rescanRequested = true;
            return;
        }
        mergeLiveKey(key);
    }

    /** One-shot flag: the owner should schedule a full rescan (live-key dedupe cap reached). */
    public boolean consumeRescanRequest() {
        if (!rescanRequested) return false;
        rescanRequested = false;
        return true;
    }

    /** Cheap fingerprint of a tile's section-key set (used to skip no-op source refills). */
    public long tileKeySignature(int tx, int tz) {
        KeyList list = byTile.get(tileKey(tx, tz));
        if (list == null) return 0L;
        long[] keys = list.snapshot();
        long h = 1125899906842597L;
        for (long k : keys) h = h * 31 + k;
        return h * 31 + keys.length;
    }

    private void saveIndexCacheAsync(long signature) {
        if (indexCache == null) return;
        // Write on the index worker directly: queueing this behind source decodes meant the file
        // was often still absent when the next map open tried to load it.
        try {
            Files.createDirectories(indexCache.getParent());
            Path tmp = indexCache.resolveSibling(indexCache.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(INDEX_MAGIC);
                out.writeInt(INDEX_VERSION);
                out.writeLong(signature);
                out.writeLong(minWX);
                out.writeLong(minWZ);
                out.writeInt(pixelBlocks);
                out.writeInt(maxLod);
                out.writeInt(byTile.size());
                for (java.util.Map.Entry<Long, KeyList> e : byTile.entrySet()) {
                    long[] keys = e.getValue().snapshot();
                    out.writeLong(e.getKey());
                    out.writeInt(keys.length);
                    for (long k : keys) out.writeLong(k);
                }
            }
            Files.move(tmp, indexCache, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_cache_save", "tiles=" + byTile.size());
        } catch (Throwable t) {
            if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_cache_save_error", t.getClass().getSimpleName());
        }
    }

    private static long tileKey(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    private long[] buildTile(int tx, int tz) throws Exception {
        if (db == null || sectionsHandle == null) return null;
        long[] pixels = new long[LiveTileStore.TILE_PIXELS * LiveTileStore.TILE_PIXELS];
        java.util.Arrays.fill(pixels, VoxyMerge.LONG_EMPTY);
        long tileX0 = minWX + (long) tx * LiveTileStore.TILE_PIXELS * pixelBlocks;
        long tileZ0 = minWZ + (long) tz * LiveTileStore.TILE_PIXELS * pixelBlocks;
        long tileX1 = tileX0 + (long) LiveTileStore.TILE_PIXELS * pixelBlocks;
        long tileZ1 = tileZ0 + (long) LiveTileStore.TILE_PIXELS * pixelBlocks;
        boolean any = false;
        KeyList list = byTile.get(tileKey(tx, tz));
        if (list == null) return null;
        // Snapshot the key set: live events may append keys concurrently, and the merge below must
        // work on a stable array.
        final long[] keys = list.snapshot();
        final int keyCount = keys.length;
        if (keyCount == 0) return null;
        Set<Long> ids = waterIds.get();
        if (ids.isEmpty()) ids = ownWaterIds;
        // optional split timing for the debug tile trace: how much of a tile build is section
        // decoding (now parallel) versus the ordered merge (still serial)
        final boolean trace = TileTrace.enabled();
        long decodeNs = 0L;
        long mergeNs = 0L;
        int batches = 0;
        // A coarse map scale puts thousands of sections in one tile. Decode each batch of sections
        // in parallel (the expensive part) and merge them in the original key order afterwards, so
        // the pixels are byte-for-byte what the sequential builder produced.
        for (int base = 0; base < keyCount; base += DECODE_BATCH_SECTIONS) {
            batches++;
            int end = Math.min(keyCount, base + DECODE_BATCH_SECTIONS);
            int len = end - base;
            long[][] decoded = new long[len][];
            int[] misses = new int[len];
            int missCount = 0;
            for (int j = 0; j < len; j++) {
                long key = keys[base + j];
                if (!intersectsTile(key, tileX0, tileX1, tileZ0, tileZ1)) continue;
                long[] cols = sectionCache.get(key);
                if (cols != null) {
                    decoded[j] = cols;
                } else {
                    misses[missCount++] = j;
                }
            }
            long decodeStartNs = trace ? System.nanoTime() : 0L;
            if (missCount > 0) {
                decodeMisses(keys, base, misses, missCount, decoded, ids);
            }
            if (trace) decodeNs += System.nanoTime() - decodeStartNs;
            long mergeStartNs = trace ? System.nanoTime() : 0L;
            // Merge in key order: this is what makes the result identical to a single-threaded pass.
            for (int j = 0; j < len; j++) {
                long[] cols = decoded[j];
                if (cols == null) continue;
                long key = keys[base + j];
                int lvl = (int) ((key >>> 60) & 0xF);
                int scale = 1 << lvl;
                int sy = (int) ((key << 4) >> 56);
                int sectionMinY = sy * 32 * scale;
                int sx = (int) ((key << 36) >> 40);
                int sz = (int) ((key << 12) >> 40);
                long wx0 = (long) sx * 32 * scale;
                long wz0 = (long) sz * 32 * scale;
                for (int c = 0; c < 1024; c++) {
                    long packed = cols[c];
                    if (packed == VoxyMerge.LONG_EMPTY) continue;
                    boolean empty = packed == VoxyMerge.EMPTY_COLUMN;
                    long colX = wx0 + (long) (c & 31) * scale;
                    long colZ = wz0 + (long) (c >> 5) * scale;
                    int px0 = (int) Math.floorDiv(colX - minWX, pixelBlocks);
                    int pz0 = (int) Math.floorDiv(colZ - minWZ, pixelBlocks);
                    int px1 = (int) Math.floorDiv(colX + scale - 1 - minWX, pixelBlocks);
                    int pz1 = (int) Math.floorDiv(colZ + scale - 1 - minWZ, pixelBlocks);
                    for (int pz = pz0; pz <= pz1; pz++) {
                        if (pz < tz * LiveTileStore.TILE_PIXELS
                                || pz >= (tz + 1) * LiveTileStore.TILE_PIXELS) continue;
                        int lz = pz - tz * LiveTileStore.TILE_PIXELS;
                        for (int px = px0; px <= px1; px++) {
                            if (px < tx * LiveTileStore.TILE_PIXELS
                                    || px >= (tx + 1) * LiveTileStore.TILE_PIXELS) continue;
                            int lx = px - tx * LiveTileStore.TILE_PIXELS;
                            int idx = lz * LiveTileStore.TILE_PIXELS + lx;
                            if (empty) {
                                // an all-air column only locks an existing pixel (see VoxyMerge); it
                                // can never bring a tile into existence on its own
                                pixels[idx] = VoxyMerge.mergeEmptyColumn(pixels[idx], lvl, sectionMinY);
                            } else {
                                pixels[idx] = VoxyMerge.mergeColumn(pixels[idx], lvl,
                                        (int) ((packed >>> 24) & 0xFFFF) - 32768,
                                        (int) (packed & 0xFFFF) - 32768,
                                        (packed & (1L << 52)) != 0,
                                        (int) ((packed >>> 53) & 0x1FF),
                                        (int) ((packed >>> 40) & 0xFF));
                                any = true;
                            }
                        }
                    }
                }
            }
            if (trace) mergeNs += System.nanoTime() - mergeStartNs;
        }
        if (trace) {
            TileTrace.stage(tx, tz, "src_build_split", "decode_ms=" + (decodeNs / 1_000_000L)
                    + " merge_ms=" + (mergeNs / 1_000_000L) + " batches=" + batches);
        }
        return any ? pixels : null;
    }

    /**
     * Decodes this batch's cache misses on the decode pool. Each task only writes its own slots of
     * {@code decoded} (and publishes into the thread-safe section cache), so no locking is needed.
     */
    private void decodeMisses(long[] keys, int base, int[] misses, int missCount,
                              long[][] decoded, Set<Long> ids) throws Exception {
        List<Future<?>> futures = new ArrayList<>(
                (missCount + DECODE_CHUNK_SECTIONS - 1) / DECODE_CHUNK_SECTIONS);
        for (int m = 0; m < missCount; m += DECODE_CHUNK_SECTIONS) {
            final int from = m;
            final int to = Math.min(missCount, m + DECODE_CHUNK_SECTIONS);
            futures.add(decodePool.submit(() -> {
                for (int q = from; q < to; q++) {
                    int j = misses[q];
                    long key = keys[base + j];
                    long[] cols = decodeSection(key, ids);
                    if (cols != null) {
                        decoded[j] = cols;
                        sectionCache.put(key, cols);
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }
        }
    }

    /** Reads and decodes one section; thread-safe (read-only RocksDB get + pure decoding). */
    private long[] decodeSection(long key, Set<Long> ids) {
        ByteBuffer buf = KEY_BUFFER.get();
        buf.clear();
        buf.putLong(key);
        byte[] val = RocksBridge.get(db, sectionsHandle, buf.array());
        if (val == null) return null;
        return MapData.decodeSectionColumns(key, val, ids);
    }

    /** True when the section key's horizontal footprint overlaps the tile's footprint. */
    private static boolean intersectsTile(long key, long tileX0, long tileX1,
                                          long tileZ0, long tileZ1) {
        int lvl = (int) ((key >>> 60) & 0xF);
        int scale = 1 << lvl;
        int sx = (int) ((key << 36) >> 40);
        int sz = (int) ((key << 12) >> 40);
        long wx0 = (long) sx * 32 * scale;
        long wz0 = (long) sz * 32 * scale;
        return !(wx0 + 32L * scale <= tileX0 || wx0 >= tileX1
                || wz0 + 32L * scale <= tileZ0 || wz0 >= tileZ1);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        indexPool.shutdownNow();
        io.shutdown();
        try {
            io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // buildTile always waits for its own decode tasks, so the pool is idle by now
        decodePool.shutdown();
        try {
            decodePool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Belt and braces: if a decode outlived the await, do not close the handle under it.
        dbLock.writeLock().lock();
        try {
            closeDb();
        } finally {
            dbLock.writeLock().unlock();
        }
    }

    /**
     * Requests a full index rebuild on the next request (manual refresh, closed-map dirty data or
     * the live-key dedupe cap). The previous index stays usable for in-flight decodes until the new
     * one is built and swapped in (see {@link #ensureIndex()}), so this no longer cancels work.
     */
    /**
     * Requests a full index rebuild (manual refresh, closed-map dirty data, or the live-key dedupe
     * cap). The scan runs on its own thread with its own read-only handle and swaps the new map in
     * atomically, so decodes keep using the previous index in the meantime — no lock stall, no
     * cancelled in-flight work.
     */
    public void invalidateIndex() {
        if (closed.get()) return;
        if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_invalidate", "async");
        try {
            if (indexCache != null) Files.deleteIfExists(indexCache);
        } catch (Throwable ignored) {
        }
        startBackgroundScan();
    }

    /** Closes the DB handle but keeps the in-memory index (used by reopen and before a rescan). */
    private void closeHandlesKeepIndex() {
        for (Object h : handles) RocksBridge.close(h);
        handles.clear();
        RocksBridge.close(db);
        db = null;
        sectionsHandle = null;
    }

    private void closeDb() {
        sectionCache.clear();
        closeHandlesKeepIndex();
    }
}
