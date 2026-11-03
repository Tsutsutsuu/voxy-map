package com.voxymap;

import com.github.luben.zstd.Zstd;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live 2D tile overlay fed by Voxy section change events.
 *
 * <p>This is intentionally independent of the global {@code Snapshot}: new sections can create or
 * update a tile even when their world coordinates are outside the current map rectangle, so edge
 * exploration no longer requires a full bounds rebuild. The base tile store still wins wherever it
 * has data; the live store only fills uncovered/outside tiles.</p>
 *
 * <p>Colours are currently a lightweight approximation (land/water palette, no hillshade or
 * contour). The data path and geometry are the important part; the existing colour pipeline can
 * later be reused for a higher-quality tile render.</p>
 */
public final class LiveTileStore {
    public static final int TILE_PIXELS = 256;
    private static final int CACHE_MAGIC = 0x56585431;
    private static final int CACHE_VERSION = 1;
    private static final ExecutorService CACHE_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voxymap-tile-cache");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService SEED_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voxymap-tile-seed");
        t.setDaemon(true);
        return t;
    });

    public static final class LiveTile {
        public final int tx, tz;
        public final long minWX, minWZ;
        public final long[] pixels = new long[TILE_PIXELS * TILE_PIXELS];
        volatile int version;
        volatile int coloredVersion = -1;
        volatile int colorStyleHash;
        volatile boolean colorPending;
        volatile boolean liveDirty;
        volatile int seedEpoch = -1;
        volatile int textureSeedEpoch = -1;
        volatile int[] rgb;
        volatile boolean textureDirty = true;
        Identifier texture;
        DynamicTexture dynamic;
        int textureEpoch = -1;

        LiveTile(int tx, int tz, long minWX, long minWZ) {
            this.tx = tx;
            this.tz = tz;
            this.minWX = minWX;
            this.minWZ = minWZ;
            java.util.Arrays.fill(pixels, VoxyMerge.LONG_EMPTY);
        }

        void dispose() {
            try {
                if (dynamic != null) dynamic.close();
                if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
            } catch (Throwable ignored) {
            }
            texture = null;
            dynamic = null;
            textureEpoch = -1;
        }
    }

    private final long minWX, minWZ;
    private final int pixelBlocks;
    private final Path cacheDir;
    private volatile long[] seedPixels;
    private volatile int[] seedRgb;
    private volatile int seedWidth, seedHeight;
    private volatile long seedMinWX, seedMinWZ;
    private volatile int seedPixelBlocks = 1;
    private volatile int seedEpoch;
    private final Map<Long, LiveTile> tiles = new ConcurrentHashMap<>();
    private final AtomicLong sectionCount = new AtomicLong();
    private final AtomicInteger createdThisFrame = new AtomicInteger();
    private final AtomicInteger colorRequestsThisFrame = new AtomicInteger();
    private final AtomicInteger seedCreatesThisFrame = new AtomicInteger();
    private final AtomicInteger seedRefreshesThisFrame = new AtomicInteger();
    private final AtomicInteger seedRequestsThisFrame = new AtomicInteger();
    private final Set<Long> seedPending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger seedTileCount = new AtomicInteger();
    private final AtomicInteger liveOnlyTileCount = new AtomicInteger();
    private final AtomicInteger minTileX = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxTileX = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger minTileZ = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxTileZ = new AtomicInteger(Integer.MIN_VALUE);

    public LiveTileStore(long minWX, long minWZ, int pixelBlocks, Path cacheDir) {
        this.minWX = minWX;
        this.minWZ = minWZ;
        this.pixelBlocks = pixelBlocks;
        this.cacheDir = cacheDir;
        if (cacheDir != null) CACHE_IO.submit(this::cleanupCache);
    }

    private void cleanupCache() {
        try {
            if (!Files.isDirectory(cacheDir)) return;
            java.util.List<Path> files = Files.list(cacheDir)
                    .filter(p -> p.getFileName().toString().endsWith(".vxt"))
                    .toList();
            final int maxFiles = 4096;
            if (files.size() <= maxFiles) return;
            files.sort((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                            Files.getLastModifiedTime(a).toMillis());
                } catch (Throwable t) {
                    return 0;
                }
            });
            for (int i = maxFiles; i < files.size(); i++) Files.deleteIfExists(files.get(i));
        } catch (Throwable ignored) {
        }
    }

    public boolean matches(long minWX, long minWZ, int pixelBlocks) {
        return this.minWX == minWX && this.minWZ == minWZ && this.pixelBlocks == pixelBlocks;
    }

    public int pixelBlocks() {
        return pixelBlocks;
    }

    public long minWX() {
        return minWX;
    }

    public long minWZ() {
        return minWZ;
    }

    public int tileCount() {
        return tiles.size();
    }

    public int seedTileCount() {
        return seedTileCount.get();
    }

    public int liveOnlyTileCount() {
        return liveOnlyTileCount.get();
    }

    public boolean hasBounds() {
        return minTileX.get() != Integer.MAX_VALUE;
    }

    public int minTileX() {
        return minTileX.get();
    }

    public int maxTileX() {
        return maxTileX.get();
    }

    public int minTileZ() {
        return minTileZ.get();
    }

    public int maxTileZ() {
        return maxTileZ.get();
    }

    private void markBounds(int tx, int tz) {
        minTileX.accumulateAndGet(tx, Math::min);
        maxTileX.accumulateAndGet(tx, Math::max);
        minTileZ.accumulateAndGet(tz, Math::min);
        maxTileZ.accumulateAndGet(tz, Math::max);
    }

    public long sectionCount() {
        return sectionCount.get();
    }

    public void beginFrame() {
        createdThisFrame.set(0);
        colorRequestsThisFrame.set(0);
        seedCreatesThisFrame.set(0);
        seedRefreshesThisFrame.set(0);
        seedRequestsThisFrame.set(0);
    }

    public LiveTile get(int tx, int tz) {
        return tiles.get(key(tx, tz));
    }

    /** Sets the base snapshot used to lazily seed unmodified tiles. */
    public void setSeed(long[] pixels, int[] rgb, int width, int height, long minWX, long minWZ,
                        int pixelBlocks, int epoch) {
        if (this.seedPixels == pixels && this.seedRgb == rgb && this.seedEpoch == epoch) return;
        this.seedPixels = pixels;
        this.seedRgb = rgb;
        this.seedWidth = width;
        this.seedHeight = height;
        this.seedMinWX = minWX;
        this.seedMinWZ = minWZ;
        this.seedPixelBlocks = Math.max(1, pixelBlocks);
        this.seedEpoch = epoch;
    }

    /**
     * Lazily creates a tile from the base snapshot. Returns null for empty/out-of-seed tiles so the
     * unified renderer does not allocate tiles for the empty margin.
     */
    public LiveTile getOrCreateSeed(int tx, int tz) {
        LiveTile existing = tiles.get(key(tx, tz));
        if (existing != null) {
            if (existing.seedEpoch < 0 && seedCreatesThisFrame.get() < 16) {
                seedCreatesThisFrame.incrementAndGet();
                seedExisting(existing);
            }
            return existing;
        }
        if (seedRequestsThisFrame.get() >= 16) return null;
        long key = key(tx, tz);
        if (!seedPending.add(key)) return null;
        seedRequestsThisFrame.incrementAndGet();
        final long[] srcPixels = seedPixels;
        final int[] srcRgb = seedRgb;
        final int srcWidth = seedWidth, srcHeight = seedHeight;
        final long srcMinWX = seedMinWX, srcMinWZ = seedMinWZ;
        final int srcPb = seedPixelBlocks;
        final int srcEpoch = seedEpoch;
        SEED_IO.submit(() -> {
            LiveTile built = buildSeedTile(tx, tz, srcPixels, srcRgb, srcWidth, srcHeight,
                    srcMinWX, srcMinWZ, srcPb, srcEpoch);
            if (built == null) built = loadCached(tx, tz);
            final LiveTile result = built;
            Minecraft.getInstance().execute(() -> {
                seedPending.remove(key);
                if (result == null) return;
                LiveTile previous = tiles.putIfAbsent(key, result);
                if (previous == null) {
                    seedTileCount.incrementAndGet();
                    markBounds(tx, tz);
                }
            });
        });
        return null; // the renderer retries next frame; preview hides the gap
    }

    private LiveTile buildSeedTile(int tx, int tz, long[] srcPixels, int[] srcRgb,
                                   int srcWidth, int srcHeight, long srcMinWX, long srcMinWZ,
                                   int srcPb, int srcEpoch) {
        if (srcPixels == null || srcRgb == null || srcWidth <= 0 || srcHeight <= 0) return null;
        long tileWorldX = tileMinWX(tx);
        long tileWorldZ = tileMinWZ(tz);
        int px0 = (int) Math.floorDiv(tileWorldX - srcMinWX, Math.max(1, srcPb));
        int pz0 = (int) Math.floorDiv(tileWorldZ - srcMinWZ, Math.max(1, srcPb));
        LiveTile tile = new LiveTile(tx, tz, tileWorldX, tileWorldZ);
        boolean any = false;
        for (int z = 0; z < TILE_PIXELS; z++) {
            int sz = pz0 + z;
            if (sz < 0 || sz >= srcHeight) continue;
            for (int x = 0; x < TILE_PIXELS; x++) {
                int sx = px0 + x;
                if (sx < 0 || sx >= srcWidth) continue;
                int si = sz * srcWidth + sx;
                if (si >= srcPixels.length || si >= srcRgb.length) continue;
                tile.pixels[z * TILE_PIXELS + x] = srcPixels[si];
                int c = srcRgb[si];
                if (tile.rgb == null) tile.rgb = new int[TILE_PIXELS * TILE_PIXELS];
                tile.rgb[z * TILE_PIXELS + x] = c;
                if ((c >>> 24) != 0) any = true;
            }
        }
        if (!any) return null;
        tile.seedEpoch = srcEpoch;
        return tile;
    }

    private Path cacheFile(int tx, int tz) {
        return cacheDir.resolve(tx + "_" + tz + ".vxt");
    }

    private LiveTile loadCached(int tx, int tz) {
        if (cacheDir == null) return null;
        Path file = cacheFile(tx, tz);
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != CACHE_MAGIC || in.readInt() != CACHE_VERSION) return null;
            int ftx = in.readInt(), ftz = in.readInt();
            long fminX = in.readLong(), fminZ = in.readLong();
            int fpb = in.readInt();
            int rawLen = in.readInt();
            if (ftx != tx || ftz != tz || fpb != pixelBlocks
                    || fminX != tileMinWX(tx) || fminZ != tileMinWZ(tz)) return null;
            byte[] comp = in.readAllBytes();
            byte[] raw = Zstd.decompress(comp, TILE_PIXELS * TILE_PIXELS * Long.BYTES);
            if (raw == null || raw.length != TILE_PIXELS * TILE_PIXELS * Long.BYTES) return null;
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            LiveTile tile = new LiveTile(tx, tz, tileMinWX(tx), tileMinWZ(tz));
            for (int i = 0; i < tile.pixels.length; i++) tile.pixels[i] = bb.getLong();
            tile.liveDirty = true; // cached pixels have no colour; let the live pipeline colour them
            liveOnlyTileCount.incrementAndGet();
            markBounds(tx, tz);
            return tile;
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveCached(LiveTile tile) {
        if (cacheDir == null || tile.seedEpoch >= 0) return;
        try {
            Files.createDirectories(cacheDir);
            ByteBuffer raw = ByteBuffer.allocate(TILE_PIXELS * TILE_PIXELS * Long.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (long v : tile.pixels) raw.putLong(v);
            byte[] comp = Zstd.compress(raw.array(), 3);
            Path file = cacheFile(tile.tx, tile.tz);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(CACHE_MAGIC);
                out.writeInt(CACHE_VERSION);
                out.writeInt(tile.tx);
                out.writeInt(tile.tz);
                out.writeLong(tile.minWX);
                out.writeLong(tile.minWZ);
                out.writeInt(pixelBlocks);
                out.writeInt(raw.capacity());
                out.write(comp);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable ignored) {
        }
    }

    private void refreshSeed(LiveTile tile) {
        long[] srcPixels = seedPixels;
        int[] srcRgb = seedRgb;
        if (srcPixels == null || srcRgb == null) return;
        int px0 = (int) Math.floorDiv(tile.minWX - seedMinWX, seedPixelBlocks);
        int pz0 = (int) Math.floorDiv(tile.minWZ - seedMinWZ, seedPixelBlocks);
        if (tile.rgb == null) tile.rgb = new int[TILE_PIXELS * TILE_PIXELS];
        for (int z = 0; z < TILE_PIXELS; z++) {
            int sz = pz0 + z;
            if (sz < 0 || sz >= seedHeight) continue;
            for (int x = 0; x < TILE_PIXELS; x++) {
                int sx = px0 + x;
                if (sx < 0 || sx >= seedWidth) continue;
                int si = sz * seedWidth + sx;
                if (si >= srcPixels.length || si >= srcRgb.length) continue;
                tile.pixels[z * TILE_PIXELS + x] = srcPixels[si];
                int c = srcRgb[si];
                if (tile.rgb == null) tile.rgb = new int[TILE_PIXELS * TILE_PIXELS];
                tile.rgb[z * TILE_PIXELS + x] = c;
            }
        }
        tile.seedEpoch = seedEpoch;
        tile.textureDirty = true;
    }

    /**
     * A tile may be created by the event worker before the first frame has seeded it (very common
     * after a map-scale change). Fill it from the base snapshot so it cannot stay as a coarse,
     * live-only patch surrounded by fine seeded tiles.
     */
    private void seedExisting(LiveTile tile) {
        long[] srcPixels = seedPixels;
        int[] srcRgb = seedRgb;
        if (srcPixels == null || srcRgb == null) return;
        int px0 = (int) Math.floorDiv(tile.minWX - seedMinWX, seedPixelBlocks);
        int pz0 = (int) Math.floorDiv(tile.minWZ - seedMinWZ, seedPixelBlocks);
        if (tile.rgb == null) tile.rgb = new int[TILE_PIXELS * TILE_PIXELS];
        for (int z = 0; z < TILE_PIXELS; z++) {
            int sz = pz0 + z;
            if (sz < 0 || sz >= seedHeight) continue;
            for (int x = 0; x < TILE_PIXELS; x++) {
                int sx = px0 + x;
                if (sx < 0 || sx >= seedWidth) continue;
                int si = sz * seedWidth + sx;
                if (si >= srcPixels.length || si >= srcRgb.length) continue;
                int di = z * TILE_PIXELS + x;
                if (tile.pixels[di] == VoxyMerge.LONG_EMPTY) tile.pixels[di] = srcPixels[si];
                tile.rgb[di] = srcRgb[si];
            }
        }
        tile.seedEpoch = seedEpoch;
        tile.textureDirty = true;
    }

    public int tileIndexX(double worldX) {
        return (int) Math.floorDiv((long) Math.floor(worldX) - minWX,
                (long) TILE_PIXELS * pixelBlocks);
    }

    public int tileIndexZ(double worldZ) {
        return (int) Math.floorDiv((long) Math.floor(worldZ) - minWZ,
                (long) TILE_PIXELS * pixelBlocks);
    }

    public long tileMinWX(int tx) {
        return minWX + (long) tx * TILE_PIXELS * pixelBlocks;
    }

    public long tileMinWZ(int tz) {
        return minWZ + (long) tz * TILE_PIXELS * pixelBlocks;
    }

    private static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    /** Applies one live Voxy section; called from the Voxy event worker, one thread at a time. */
    public void applySection(long key, long[] raw, Set<Long> waterIds) {
        long[] cols = VoxySectionDecoder.decode(key, raw, waterIds);
        if (cols == null) return;
        sectionCount.incrementAndGet();
        int lvl = (int) ((key >>> 60) & 0xF);
        int scale = 1 << lvl;
        int sx = (int) ((key << 36) >> 40);
        int sz = (int) ((key << 12) >> 40);
        java.util.Set<LiveTile> touched = new java.util.HashSet<>();
        for (int c = 0; c < 1024; c++) {
            long packed = cols[c];
            if (packed == VoxyMerge.LONG_EMPTY) continue;
            long wx0 = (long) (sx * 32 + (c & 31)) * scale;
            long wz0 = (long) (sz * 32 + (c >> 5)) * scale;
            int px0 = (int) Math.floorDiv(wx0 - minWX, pixelBlocks);
            int px1 = (int) Math.floorDiv(wx0 + scale - 1 - minWX, pixelBlocks);
            int pz0 = (int) Math.floorDiv(wz0 - minWZ, pixelBlocks);
            int pz1 = (int) Math.floorDiv(wz0 + scale - 1 - minWZ, pixelBlocks);
            for (int pz = pz0; pz <= pz1; pz++) {
                int tz = Math.floorDiv(pz, TILE_PIXELS);
                int localZ = pz - tz * TILE_PIXELS;
                for (int px = px0; px <= px1; px++) {
                    int tx = Math.floorDiv(px, TILE_PIXELS);
                    int localX = px - tx * TILE_PIXELS;
                    LiveTile tile = tiles.get(key(tx, tz));
                    if (tile == null) {
                        LiveTile created = new LiveTile(tx, tz, tileMinWX(tx), tileMinWZ(tz));
                        tile = tiles.putIfAbsent(key(tx, tz), created);
                        if (tile == null) {
                            tile = created;
                            liveOnlyTileCount.incrementAndGet();
                            markBounds(tx, tz);
                        }
                    }
                    int idx = localZ * TILE_PIXELS + localX;
                    long prev = tile.pixels[idx];
                    tile.pixels[idx] = VoxyMerge.mergeColumn(prev, lvl,
                            (int) ((packed >>> 24) & 0xFFFF) - 32768,
                            (int) (packed & 0xFFFF) - 32768,
                            (packed & (1L << 52)) != 0,
                            (int) ((packed >>> 53) & 0x1FF));
                    touched.add(tile);
                }
            }
        }
        for (LiveTile t : touched) {
            t.liveDirty = true;
            t.version++;
            t.textureDirty = true;
            t.colorPending = false;
        }
    }

    /**
     * Makes sure a texture exists for this live tile. Full-style colouring is queued on the
     * map loader (serialized with global refreshes) and the GPU upload is rate-limited.
     */
    public Identifier textureFor(LiveTile tile, String style, int sea, int colorEpoch) {
        if (tile == null) return null;
        int styleHash = ((style == null ? 0 : style.hashCode()) * 31 + sea) * 31 + colorEpoch;
        if (tile.seedEpoch >= 0) {
            // Tiles covered by the base snapshot keep the proven base colour pipeline. Live
            // colouring is currently only used for genuinely new tiles outside the snapshot.
            if (tile.seedEpoch != seedEpoch && seedRefreshesThisFrame.get() < 10) {
                seedRefreshesThisFrame.incrementAndGet();
                refreshSeed(tile);
            }
            if (tile.rgb == null) return tile.texture;
            if (tile.texture == null || tile.textureDirty || tile.textureEpoch != styleHash) {
                if (createdThisFrame.get() >= 6) return tile.texture;
                buildTexture(tile, styleHash);
            }
            return tile.texture;
        }
        if (!tile.liveDirty) {
            if (tile.seedEpoch != seedEpoch) refreshSeed(tile);
            if (tile.rgb == null) return tile.texture;
            if (tile.texture == null || tile.textureDirty || tile.textureEpoch != styleHash) {
                if (createdThisFrame.get() >= 6) return tile.texture;
                buildTexture(tile, styleHash);
            }
            return tile.texture;
        }
        if (tile.texture != null && tile.textureEpoch == styleHash && !tile.textureDirty) {
            return tile.texture;
        }
        if (tile.version != tile.coloredVersion || tile.colorStyleHash != styleHash) {
            if (!tile.colorPending && colorRequestsThisFrame.get() < 4) {
                requestColor(tile, styleHash);
            }
            return tile.texture;
        }
        if (tile.rgb == null) return tile.texture;
        if (createdThisFrame.get() >= 6) return tile.texture;
        buildTexture(tile, styleHash);
        return tile.texture;
    }

    private void requestColor(LiveTile tile, int styleHash) {
        tile.colorPending = true;
        colorRequestsThisFrame.incrementAndGet();
        final int version = tile.version;
        final int halo = 8;
        final int hw = TILE_PIXELS + halo * 2;
        long[] withHalo = new long[hw * hw];
        int baseX = tile.tx * TILE_PIXELS - halo;
        int baseZ = tile.tz * TILE_PIXELS - halo;
        for (int z = 0; z < hw; z++) {
            int gz = baseZ + z;
            int tz = Math.floorDiv(gz, TILE_PIXELS);
            int lz = gz - tz * TILE_PIXELS;
            for (int x = 0; x < hw; x++) {
                int gx = baseX + x;
                int tx = Math.floorDiv(gx, TILE_PIXELS);
                int lx = gx - tx * TILE_PIXELS;
                LiveTile src = tiles.get(key(tx, tz));
                if (src != null) {
                    withHalo[z * hw + x] = src.pixels[lz * TILE_PIXELS + lx];
                }
            }
        }
        long minX = tile.minWX - (long) halo * pixelBlocks;
        long minZ = tile.minWZ - (long) halo * pixelBlocks;
        MapData.INSTANCE.submitTileColor(withHalo, hw, hw, minX, minZ, pixelBlocks, full -> {
            tile.colorPending = false;
            if (full == null || full.length < hw * hw) return;
            if (tile.version != version) {
                return; // a newer section update won the race; a later frame requests again
            }
            if (tile.coloredVersion == version && tile.colorStyleHash == styleHash) return;
            int[] cropped = new int[TILE_PIXELS * TILE_PIXELS];
            for (int z = 0; z < TILE_PIXELS; z++) {
                System.arraycopy(full, (z + halo) * hw + halo, cropped, z * TILE_PIXELS, TILE_PIXELS);
            }
            tile.rgb = cropped;
            tile.coloredVersion = version;
            tile.colorStyleHash = styleHash;
            tile.textureDirty = true;
        });
    }

    private void buildTexture(LiveTile tile, int styleHash) {
        createdThisFrame.incrementAndGet();
        NativeImage image = new NativeImage(TILE_PIXELS, TILE_PIXELS, false);
        int[] rgb = tile.rgb;
        for (int i = 0; i < TILE_PIXELS * TILE_PIXELS; i++) {
            int c = rgb[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            image.setPixelABGR(i % TILE_PIXELS, i / TILE_PIXELS,
                    ((c >>> 24) << 24) | (b << 16) | (g << 8) | r);
        }
        DynamicTexture dynamic = new DynamicTexture(() -> "voxymap_live_tile", image);
        Identifier id = Identifier.fromNamespaceAndPath("voxymap",
                "live_" + tile.tx + "_" + tile.tz + "_" + Math.abs(styleHash));
        if (tile.texture != null) tile.dispose();
        Minecraft.getInstance().getTextureManager().register(id, dynamic);
        tile.texture = id;
        tile.dynamic = dynamic;
        tile.textureEpoch = styleHash;
        tile.textureDirty = false;
    }

    /** Frees GPU textures but keeps tile pixels/rgb so reopening can re-upload cheaply. */
    public void disposeTextures() {
        for (LiveTile tile : tiles.values()) {
            tile.dispose();
            tile.textureDirty = true;
        }
    }

    /**
     * Bounds the resident seeded tiles. Seeded tiles are reconstructible from the base snapshot,
     * so far ones can be dropped safely; live-only tiles are kept (they have no other source).
     */
    public void evictFar(int centerTx, int centerTz, int keepRadius, int maxTiles) {
        if (tiles.size() <= maxTiles) return;
        java.util.List<LiveTile> candidates = new java.util.ArrayList<>();
        for (LiveTile tile : tiles.values()) {
            if (tile.seedEpoch < 0) continue; // keep live-only data
            int dx = tile.tx - centerTx;
            int dz = tile.tz - centerTz;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > keepRadius) candidates.add(tile);
        }
        candidates.sort((a, b) -> {
            int da = Math.max(Math.abs(a.tx - centerTx), Math.abs(a.tz - centerTz));
            int db = Math.max(Math.abs(b.tx - centerTx), Math.abs(b.tz - centerTz));
            return Integer.compare(db, da);
        });
        int excess = tiles.size() - maxTiles;
        for (LiveTile tile : candidates) {
            if (excess-- <= 0) break;
            if (tiles.remove(key(tile.tx, tile.tz), tile)) {
                tile.dispose();
                seedTileCount.decrementAndGet();
            }
        }
    }

    public void dispose() {
        for (LiveTile tile : tiles.values()) {
            if (tile.seedEpoch < 0) CACHE_IO.submit(() -> saveCached(tile));
            tile.dispose();
        }
        tiles.clear();
    }
}
