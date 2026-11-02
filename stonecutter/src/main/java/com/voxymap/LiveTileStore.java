package com.voxymap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    public LiveTileStore(long minWX, long minWZ, int pixelBlocks) {
        this.minWX = minWX;
        this.minWZ = minWZ;
        this.pixelBlocks = pixelBlocks;
    }

    public boolean matches(long minWX, long minWZ, int pixelBlocks) {
        return this.minWX == minWX && this.minWZ == minWZ && this.pixelBlocks == pixelBlocks;
    }

    public int pixelBlocks() {
        return pixelBlocks;
    }

    public int tileCount() {
        return tiles.size();
    }

    public long sectionCount() {
        return sectionCount.get();
    }

    public void beginFrame() {
        createdThisFrame.set(0);
        colorRequestsThisFrame.set(0);
        seedCreatesThisFrame.set(0);
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
        if (existing != null) return existing;
        if (seedCreatesThisFrame.get() >= 16) return null;
        seedCreatesThisFrame.incrementAndGet();
        long[] srcPixels = seedPixels;
        int[] srcRgb = seedRgb;
        if (srcPixels == null || srcRgb == null) return null;
        long tileWorldX = tileMinWX(tx);
        long tileWorldZ = tileMinWZ(tz);
        int px0 = (int) Math.floorDiv(tileWorldX - seedMinWX, seedPixelBlocks);
        int pz0 = (int) Math.floorDiv(tileWorldZ - seedMinWZ, seedPixelBlocks);
        int step = Math.max(1, seedPixelBlocks / pixelBlocks);
        LiveTile tile = new LiveTile(tx, tz, tileWorldX, tileWorldZ);
        tile.seedEpoch = seedEpoch;
        boolean any = false;
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
                if ((c >>> 24) != 0) any = true;
            }
        }
        if (!any) return null;
        LiveTile previous = tiles.putIfAbsent(key(tx, tz), tile);
        return previous != null ? previous : tile;
    }

    private void refreshSeed(LiveTile tile) {
        if (tile.liveDirty) return;
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
                        if (tile == null) tile = created;
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
    public Identifier textureFor(LiveTile tile, String style, int sea) {
        if (tile == null) return null;
        int styleHash = (style == null ? 0 : style.hashCode()) * 31 + sea;
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

    public void dispose() {
        for (LiveTile tile : tiles.values()) tile.dispose();
        tiles.clear();
    }
}
