package com.voxymap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lazy 2D tile view of the current colour frame.
 *
 * <p>Milestone 1 of the tiled map: the screen no longer draws one huge map texture. Tiles are cut
 * from the current colour buffer on demand, uploaded independently and cached until the colour
 * frame changes. This validates tile coordinates, culling, partial texture upload and the tile
 * renderer without changing the data source yet. Phase 2 replaces the colour frame with real
 * per-tile data.</p>
 */
public final class TileStore {
    public static final int TILE_PIXELS = 256;

    public static final class MapTile {
        public final int indexX, indexZ;
        public final long minWX, minWZ;
        public final int width, height;
        public final int frameEpoch;
        /** Independent pixel slice for this tile (foundation for per-tile map updates). */
        public final long[] pixels;
        public final Identifier texture;
        public final Identifier textureSmall;
        public final int smallWidth, smallHeight;
        private final DynamicTexture dynamic;
        private final DynamicTexture dynamicSmall;

        MapTile(int indexX, int indexZ, long minWX, long minWZ, int width, int height,
                int frameEpoch, long[] pixels, Identifier texture, DynamicTexture dynamic,
                Identifier textureSmall, DynamicTexture dynamicSmall,
                int smallWidth, int smallHeight) {
            this.indexX = indexX;
            this.indexZ = indexZ;
            this.minWX = minWX;
            this.minWZ = minWZ;
            this.width = width;
            this.height = height;
            this.frameEpoch = frameEpoch;
            this.pixels = pixels;
            this.texture = texture;
            this.dynamic = dynamic;
            this.textureSmall = textureSmall;
            this.dynamicSmall = dynamicSmall;
            this.smallWidth = smallWidth;
            this.smallHeight = smallHeight;
        }

        void dispose() {
            try {
                dynamic.close();
                Minecraft.getInstance().getTextureManager().release(texture);
            } catch (Throwable ignored) {
            }
            try {
                if (dynamicSmall != null) dynamicSmall.close();
                if (textureSmall != null) Minecraft.getInstance().getTextureManager().release(textureSmall);
            } catch (Throwable ignored) {
            }
        }
    }

    private volatile MapData.ColorFrame frame;
    private volatile long[] sourcePixels;
    private final int width, height;    private final long minWX, minWZ;
    private final int pixelBlocks;
    private final int tilesX, tilesY;
    private final long epoch;
    private final Map<Long, MapTile> tiles = new HashMap<>();
    private final Set<Long> empty = new HashSet<>();
    /** limit newly created textures per frame so a far-zoom open never freezes for a second */
    private int createBudget;

    public TileStore(MapData.ColorFrame frame, long[] sourcePixels, int width, int height, long minWX, long minWZ,
                     int pixelBlocks, long epoch) {
        this.frame = frame;
        this.sourcePixels = sourcePixels;
        this.width = width;
        this.height = height;
        this.minWX = minWX;
        this.minWZ = minWZ;
        this.pixelBlocks = pixelBlocks;
        this.tilesX = (width + TILE_PIXELS - 1) / TILE_PIXELS;
        this.tilesY = (height + TILE_PIXELS - 1) / TILE_PIXELS;
        this.epoch = epoch;
    }

    public MapData.ColorFrame frame() {
        return frame;
    }

    /** True when this store's world geometry matches the supplied snapshot. */
    public boolean matches(int width, int height, long minWX, long minWZ, int pixelBlocks) {
        return this.width == width && this.height == height
                && this.minWX == minWX && this.minWZ == minWZ && this.pixelBlocks == pixelBlocks;
    }

    /**
     * Keeps the existing tile objects but makes their textures stale. The next visit to a tile
     * re-cuts/upload it from the new colour frame within the per-frame budget, so a refresh does
     * not throw away every visible tile texture at once.
     */
    public void updateFrame(MapData.ColorFrame next, long[] nextPixels) {
        if (next == this.frame && nextPixels == this.sourcePixels) return;
        this.frame = next;
        this.sourcePixels = nextPixels;
        empty.clear();
    }

    public int tilesX() {
        return tilesX;
    }

    public int tilesY() {
        return tilesY;
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

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int createdCount() {
        return tiles.size();
    }

    public int emptyCount() {
        return empty.size();
    }

    public long tileMinWX(int tx) {
        return minWX + (long) tx * TILE_PIXELS * pixelBlocks;
    }

    public long tileMinWZ(int tz) {
        return minWZ + (long) tz * TILE_PIXELS * pixelBlocks;
    }

    public int tileWidth(int tx) {
        return Math.min(TILE_PIXELS, width - tx * TILE_PIXELS);
    }

    public int tileHeight(int tz) {
        return Math.min(TILE_PIXELS, height - tz * TILE_PIXELS);
    }

    public int tileIndexFromWorldX(double worldX) {
        return (int) Math.floorDiv((long) Math.floor(worldX) - minWX,
                (long) TILE_PIXELS * pixelBlocks);
    }

    public int tileIndexFromWorldZ(double worldZ) {
        return (int) Math.floorDiv((long) Math.floor(worldZ) - minWZ,
                (long) TILE_PIXELS * pixelBlocks);
    }

    /** Main thread: reset the per-frame texture-creation budget before drawing the visible tiles. */
    public void beginFrame() {
        createBudget = 12;
    }

    /** Cheap coverage test used by the live overlay: does this base tile contain any map pixels? */
    public boolean hasData(int tx, int tz) {
        if (tx < 0 || tz < 0 || tx >= tilesX || tz >= tilesY) return false;
        long key = ((long) tx << 32) | (tz & 0xFFFFFFFFL);
        if (tiles.containsKey(key)) return true;
        if (empty.contains(key)) return false;
        int tw = tileWidth(tx), th = tileHeight(tz);
        int[] rgb = frame.rgb;
        int px0 = tx * TILE_PIXELS, pz0 = tz * TILE_PIXELS;
        for (int z = 0; z < th; z++) {
            int row = (pz0 + z) * width + px0;
            for (int x = 0; x < tw; x++) {
                if ((rgb[row + x] >>> 24) != 0) return true;
            }
        }
        empty.add(key);
        return false;
    }

    /** Returns the tile, or null when the tile is empty / out of bounds / deferred this frame. Main thread only. */
    public MapTile getTile(int tx, int tz) {
        if (tx < 0 || tz < 0 || tx >= tilesX || tz >= tilesY) return null;
        long key = ((long) tx << 32) | (tz & 0xFFFFFFFFL);
        MapTile existing = tiles.get(key);
        if (existing != null) {
            if (existing.frameEpoch == frame.epoch) return existing;
            if (createBudget <= 0) return existing; // keep the old colours for one more frame
            createBudget--;
            existing.dispose();
            tiles.remove(key);
        }
        if (empty.contains(key)) return null;
        if (createBudget <= 0) return null;
        createBudget--;
        int tw = tileWidth(tx), th = tileHeight(tz);
        int[] rgb = frame.rgb;
        int px0 = tx * TILE_PIXELS, pz0 = tz * TILE_PIXELS;
        boolean any = false;
        for (int z = 0; z < th && !any; z++) {
            int row = (pz0 + z) * width + px0;
            for (int x = 0; x < tw; x++) {
                if ((rgb[row + x] >>> 24) != 0) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) {
            empty.add(key);
            return null;
        }
        NativeImage image = new NativeImage(tw, th, false);
        long[] tilePixels = new long[tw * th];
        for (int z = 0; z < th; z++) {
            int row = (pz0 + z) * width;
            int tileRow = z * tw;
            for (int x = 0; x < tw; x++) {
                int c = rgb[row + px0 + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                image.setPixelABGR(x, z, 0xFF000000 | (b << 16) | (g << 8) | r);
                if (sourcePixels != null && row + px0 + x < sourcePixels.length) {
                    tilePixels[tileRow + x] = sourcePixels[row + px0 + x];
                }
            }
        }
        int sw = Math.max(1, tw / 4), sh = Math.max(1, th / 4);
        NativeImage small = new NativeImage(sw, sh, false);
        for (int z = 0; z < sh; z++) {
            for (int x = 0; x < sw; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dz = 0; dz < 4; dz++) {
                    int zz = z * 4 + dz;
                    if (zz >= th) continue;
                    int row = (pz0 + zz) * width + px0;
                    for (int dx = 0; dx < 4; dx++) {
                        int xx = x * 4 + dx;
                        if (xx >= tw) continue;
                        int c = rgb[row + xx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                small.setPixelABGR(x, z, 0xFF000000 | ((b / n) << 16) | ((g / n) << 8) | (r / n));
            }
        }
        DynamicTexture dynamic = new DynamicTexture(() -> "voxymap_tile", image);
        Identifier id = Identifier.fromNamespaceAndPath("voxymap",
                "tile_" + epoch + "_" + tx + "_" + tz);
        Minecraft.getInstance().getTextureManager().register(id, dynamic);
        DynamicTexture dynamicSmall = new DynamicTexture(() -> "voxymap_tile_small", small);
        Identifier idSmall = Identifier.fromNamespaceAndPath("voxymap",
                "tile_" + epoch + "_" + tx + "_" + tz + "_s");
        Minecraft.getInstance().getTextureManager().register(idSmall, dynamicSmall);
        MapTile tile = new MapTile(tx, tz, tileMinWX(tx), tileMinWZ(tz), tw, th, frame.epoch, tilePixels,
                id, dynamic, idSmall, dynamicSmall, sw, sh);
        tiles.put(key, tile);
        return tile;
    }

    /** Releases all created tile textures. Main thread only. */
    public void dispose() {
        for (MapTile tile : tiles.values()) tile.dispose();
        tiles.clear();
        empty.clear();
    }
}
