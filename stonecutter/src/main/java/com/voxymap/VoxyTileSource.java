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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * On-demand tile builder for the unified map. When a visible tile has neither seed, live nor
 * cached data, it scans the Voxy section keys (built once) and decodes only the sections that
 * intersect that tile. This lets the tile map grow beyond the original global Snapshot rectangle.
 *
 * <p>A small dedicated I/O pool; callbacks are forwarded to the client thread.</p>
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
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean indexed;
    /** bumped to discard queued/running decode work after the viewport moved */
    private final java.util.concurrent.atomic.AtomicInteger requestGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<Long, KeyList> byTile =
            new java.util.concurrent.ConcurrentHashMap<>();
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

        void add(long key) {
            if (size == keys.length) keys = java.util.Arrays.copyOf(keys, size + size / 2 + 1);
            keys[size++] = key;
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
        io.submit(() -> {
            if (closed.get() || generation != requestGeneration.get()) return;
            final long workNs = System.nanoTime();
            if (TileTrace.enabled()) {
                TileTrace.timed(tx, tz, "src_queue", submittedNs, "worker-start");
            }
            try {
                ensureIndex();
                if (generation != requestGeneration.get()) return;
                long[] pixels = buildTile(tx, tz);
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
        });
    }

    private synchronized void ensureIndex() throws Exception {
        if (indexed) return;
        final long indexStartNs = System.nanoTime();
        if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_scan_start", "pb=" + pixelBlocks);
        if (!RocksBridge.available() || storage == null) {
            indexed = true;
            return;
        }
        if (db == null) {
            db = RocksBridge.openReadOnly(storage.toString(), handles);
            if (db == null || handles.size() < 3) {
                indexed = true;
                return;
            }
        }
        sectionsHandle = handles.get(1);
        // The tile source must not decode water as solid just because the async global liquid-id
        // load has not finished yet: read the same mapping table directly from this connection.
        if (waterIds.get().isEmpty()) {
            ownWaterIds = MapData.loadWaterIdsFromDb(db, handles.get(2));
        }
        long signature = RocksBridge.latestSequence(db);
        if (signature < 0) signature = storageSignature();
        if (loadIndexCache(signature)) {
            indexed = true;
            return;
        }
        Object it = RocksBridge.newIterator(db, sectionsHandle);
        final long tileWorld = (long) LiveTileStore.TILE_PIXELS * pixelBlocks;
        try {
            RocksBridge.seekToFirst(it);
            while (RocksBridge.isValid(it)) {
                byte[] kb = RocksBridge.key(it);
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int) ((key >>> 60) & 0xF);
                    if (lvl <= maxLod) {
                        int scale = 1 << lvl;
                        int sx = (int) ((key << 36) >> 40);
                        int sz = (int) ((key << 12) >> 40);
                        long wx0 = (long) sx * 32 * scale;
                        long wz0 = (long) sz * 32 * scale;
                        long wx1 = wx0 + 32L * scale;
                        long wz1 = wz0 + 32L * scale;
                        int tx0 = (int) Math.floorDiv(wx0 - minWX, tileWorld);
                        int tx1 = (int) Math.floorDiv(wx1 - 1 - minWX, tileWorld);
                        int tz0 = (int) Math.floorDiv(wz0 - minWZ, tileWorld);
                        int tz1 = (int) Math.floorDiv(wz1 - 1 - minWZ, tileWorld);
                        for (int tz = tz0; tz <= tz1; tz++) {
                            for (int tx = tx0; tx <= tx1; tx++) {
                                byTile.computeIfAbsent(tileKey(tx, tz), k -> new KeyList()).add(key);
                            }
                        }
                    }
                }
                RocksBridge.next(it);
            }
        } finally {
            RocksBridge.close(it);
        }
        indexed = true;
        saveIndexCacheAsync(signature);
        if (TileTrace.enabled()) {
            TileTrace.timed(-1, -1, "index_scan_done", indexStartNs, "tiles=" + byTile.size());
        }
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
            // immediately, and let the normal 4s/14s/34s resync replace it if the world changed.
            boolean stale = cachedSignature != signature;
            if (in.readLong() != minWX || in.readLong() != minWZ
                    || in.readInt() != pixelBlocks || in.readInt() != maxLod) return false;
            int count = in.readInt();
            if (count < 0 || count > 2_000_000) return false;
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int n = in.readInt();
                if (n < 0 || n > 1_000_000) return false;
                KeyList list = new KeyList();
                list.keys = new long[Math.max(4, n)];
                for (int k = 0; k < n; k++) list.keys[k] = in.readLong();
                list.size = n;
                byTile.put(key, list);
            }
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
            byTile.clear();
            return false;
        }
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
                    KeyList list = e.getValue();
                    out.writeLong(e.getKey());
                    out.writeInt(list.size);
                    for (int k = 0; k < list.size; k++) out.writeLong(list.keys[k]);
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
        ByteBuffer keyBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        for (int k = 0; k < list.size; k++) {
            long key = list.keys[k];
            int lvl = (int) ((key >>> 60) & 0xF);
            int scale = 1 << lvl;
            int sx = (int) ((key << 36) >> 40);
            int sz = (int) ((key << 12) >> 40);
            long wx0 = (long) sx * 32 * scale;
            long wz0 = (long) sz * 32 * scale;
            long wx1 = wx0 + 32L * scale;
            long wz1 = wz0 + 32L * scale;
            if (wx1 <= tileX0 || wx0 >= tileX1 || wz1 <= tileZ0 || wz0 >= tileZ1) continue;
            long[] cols = sectionCache.get(key);
            if (cols == null) {
                keyBuf.clear();
                keyBuf.putLong(key);
                byte[] val = RocksBridge.get(db, sectionsHandle, keyBuf.array());
                if (val == null) continue;
                Set<Long> ids = waterIds.get();
                if (ids.isEmpty()) ids = ownWaterIds;
                cols = MapData.decodeSectionColumns(key, val, ids);
                if (cols == null) continue;
                sectionCache.put(key, cols);
            }
            for (int c = 0; c < 1024; c++) {
                long packed = cols[c];
                if (packed == VoxyMerge.LONG_EMPTY) continue;
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
                        pixels[idx] = VoxyMerge.mergeColumn(pixels[idx], lvl,
                                (int) ((packed >>> 24) & 0xFFFF) - 32768,
                                (int) (packed & 0xFFFF) - 32768,
                                (packed & (1L << 52)) != 0,
                                (int) ((packed >>> 53) & 0x1FF));
                        any = true;
                    }
                }
            }
        }
        return any ? pixels : null;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        io.shutdown();
        try {
            io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeDb();
    }

    /** Rebuilds the section index on the next request (manual refresh after new exploration). */
    public void invalidateIndex() {
        if (closed.get()) return;
        if (TileTrace.enabled()) TileTrace.stage(-1, -1, "index_invalidate", "");
        // Do not close the RocksDB connection here: decode tasks may still be reading it. Reusing
        // the connection is safe and avoids a close/reopen race now that requests use a pool.
        io.submit(() -> {
            byTile.clear();
            sectionCache.clear();
            indexed = false;
            try {
                if (indexCache != null) Files.deleteIfExists(indexCache);
            } catch (Throwable ignored) {
            }
        });
    }

    private void closeDb() {
        sectionCache.clear();
        for (Object h : handles) RocksBridge.close(h);
        handles.clear();
        RocksBridge.close(db);
        db = null;
        sectionsHandle = null;
    }
}
