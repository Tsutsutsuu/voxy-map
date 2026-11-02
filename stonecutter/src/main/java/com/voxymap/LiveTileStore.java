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
        volatile boolean dirty = true;
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
    private final Map<Long, LiveTile> tiles = new ConcurrentHashMap<>();
    private final AtomicLong sectionCount = new AtomicLong();
    private final AtomicInteger createdThisFrame = new AtomicInteger();

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
    }

    public LiveTile get(int tx, int tz) {
        return tiles.get(key(tx, tz));
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
                    tile.dirty = true;
                }
            }
        }
    }

    /**
     * Makes sure a texture exists for this live tile. Called on the render thread; creation is
     * rate-limited so a burst of new tiles cannot stall the frame.
     */
    public Identifier textureFor(LiveTile tile, String style, int sea) {
        if (tile == null) return null;
        int styleHash = (style == null ? 0 : style.hashCode()) * 31 + sea;
        if (tile.texture != null) {
            if (tile.dirty || tile.textureEpoch != styleHash) {
                if (createdThisFrame.get() >= 6) return tile.texture;
                updatedTexture(tile, style, sea, styleHash);
            }
            return tile.texture;
        }
        if (createdThisFrame.get() >= 6) return null;
        updatedTexture(tile, style, sea, styleHash);
        return tile.texture;
    }

    private void updatedTexture(LiveTile tile, String style, int sea, int styleHash) {
        createdThisFrame.incrementAndGet();
        NativeImage image = new NativeImage(TILE_PIXELS, TILE_PIXELS, false);
        boolean any = false;
        for (int i = 0; i < TILE_PIXELS * TILE_PIXELS; i++) {
            int argb = color(tile.pixels[i], style, sea);
            if ((argb >>> 24) != 0) any = true;
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            image.setPixelABGR(i % TILE_PIXELS, i / TILE_PIXELS,
                    ((argb >>> 24) << 24) | (b << 16) | (g << 8) | r);
        }
        if (!any) {
            image.close();
            tile.dirty = false;
            return;
        }
        DynamicTexture dynamic = new DynamicTexture(() -> "voxymap_live_tile", image);
        Identifier id = Identifier.fromNamespaceAndPath("voxymap",
                "live_" + tile.tx + "_" + tile.tz + "_" + Math.abs(styleHash));
        if (tile.texture != null) {
            tile.dispose();
        }
        Minecraft.getInstance().getTextureManager().register(id, dynamic);
        tile.texture = id;
        tile.dynamic = dynamic;
        tile.textureEpoch = styleHash;
        tile.dirty = false;
    }

    private static int color(long packed, String style, int sea) {
        if (packed == VoxyMerge.LONG_EMPTY) return 0;
        int top = (int) ((packed >>> 24) & 0xFFFF) - 32768;
        int floor = (int) (packed & 0xFFFF) - 32768;
        if (top == VoxyMerge.NO_TOP) top = floor;
        if (top == Integer.MIN_VALUE || top == VoxyMerge.NO_TOP) return 0;
        boolean water = (packed & (1L << 52)) != 0;
        int rgb = water
                ? MapStyles.waterColor(style, Math.max(1, sea - floor))
                : MapStyles.landColor(style, top, sea, sea - 64, sea + 384, false);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public void dispose() {
        for (LiveTile tile : tiles.values()) tile.dispose();
        tiles.clear();
    }
}
