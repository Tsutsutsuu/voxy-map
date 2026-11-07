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
    private static final int CACHE_VERSION = 7;
    private static final ExecutorService CACHE_IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "voxymap-tile-cache");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final ExecutorService SEED_IO = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "voxymap-tile-seed");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public static final class LiveTile {
        public final int tx, tz;
        public final long minWX, minWZ;
        public final long[] pixels;
        volatile int version;
        volatile int coloredVersion = -1;
        volatile int colorStyleHash;
        volatile boolean colorPending;
        volatile boolean liveDirty;
        volatile int seedEpoch = -1;
        /** true once the whole tile has been filled from cache/Voxy storage (not just live sections) */
        volatile boolean sourceFilled;
        volatile int textureSeedEpoch = -1;
        volatile int[] rgb;
        volatile boolean textureDirty = true;
        Identifier texture;
        DynamicTexture dynamic;
        int textureEpoch = -1;
        /** highest non-water top in this tile; lets the global max shrink again after refinement */
        volatile int tileMaxLand = Integer.MIN_VALUE;

        LiveTile(int tx, int tz, long minWX, long minWZ) {
            this(tx, tz, minWX, minWZ, new long[TILE_PIXELS * TILE_PIXELS]);
            java.util.Arrays.fill(pixels, VoxyMerge.LONG_EMPTY);
        }

        /** Takes ownership of an already decoded 256×256 pixel array (avoids a 512 KiB copy). */
        LiveTile(int tx, int tz, long minWX, long minWZ, long[] pixels) {
            this.tx = tx;
            this.tz = tz;
            this.minWX = minWX;
            this.minWZ = minWZ;
            this.pixels = pixels;
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
    /** false until the world's liquid-id table is known; dry (water-as-solid) tiles must not be cached. */
    private volatile boolean cacheReady;
    private final AtomicInteger cacheGeneration = new AtomicInteger();
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
    private final AtomicInteger sourceFillRequestsThisFrame = new AtomicInteger();
    private final Set<Long> seedPending = ConcurrentHashMap.newKeySet();
    private final Set<Long> sourcePending = ConcurrentHashMap.newKeySet();
    private final Set<Long> sourceEmpty = ConcurrentHashMap.newKeySet();
    private final AtomicInteger resetGeneration = new AtomicInteger();
    private final AtomicInteger sourceEpoch = new AtomicInteger();
    private volatile VoxyTileSource source;
    private final AtomicInteger seedTileCount = new AtomicInteger();
    private final AtomicInteger liveOnlyTileCount = new AtomicInteger();
    private final AtomicInteger minTileX = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxTileX = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger minTileZ = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxTileZ = new AtomicInteger(Integer.MIN_VALUE);
    /** highest known non-water terrain top, used by the land max-point colour ramp */
    private final AtomicInteger maxLandY = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger rampVersion = new AtomicInteger();
    private final AtomicInteger contentVersion = new AtomicInteger();
    private final AtomicInteger colorVersion = new AtomicInteger();
    private volatile long lastRampChangeMs;

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

    /** Snapshot of all loaded tile pixels, used for tile-native biome overlay/labels/export. */
    public MapData.Snapshot buildSnapshot(java.util.Map<Integer, String> biomeNames) {
        if (!hasBounds()) return null;
        int minTx = minTileX(), maxTx = maxTileX();
        int minTz = minTileZ(), maxTz = maxTileZ();
        long wpx = (long) (maxTx - minTx + 1) * TILE_PIXELS;
        long hpx = (long) (maxTz - minTz + 1) * TILE_PIXELS;
        if (wpx <= 0 || hpx <= 0 || wpx > 16_384L || hpx > 16_384L
                || wpx * hpx > 32_000_000L) return null;
        int w = (int) wpx, h = (int) hpx;
        long minWX = tileMinWX(minTx), minWZ = tileMinWZ(minTz);
        long[] pixels = new long[w * h];
        java.util.Arrays.fill(pixels, VoxyMerge.LONG_EMPTY);
        for (LiveTile tile : tiles.values()) {
            int ox = (tile.tx - minTx) * TILE_PIXELS;
            int oz = (tile.tz - minTz) * TILE_PIXELS;
            if (ox < 0 || oz < 0 || ox + TILE_PIXELS > w || oz + TILE_PIXELS > h) continue;
            for (int z = 0; z < TILE_PIXELS; z++) {
                System.arraycopy(tile.pixels, z * TILE_PIXELS,
                        pixels, (oz + z) * w + ox, TILE_PIXELS);
            }
        }
        return new MapData.Snapshot(pixels, w, h, minWX, minWZ, pixelBlocks,
                biomeNames == null ? java.util.Map.of() : biomeNames, System.nanoTime());
    }

    /** Snapshot of a viewport-sized map-pixel rectangle (used by the 2D biome overlay). */
    public MapData.Snapshot buildSnapshotRegion(int centerPxX, int centerPzX, int halfW, int halfH,
                                                java.util.Map<Integer, String> biomeNames) {
        int w = halfW * 2;
        int h = halfH * 2;
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || (long) w * h > 10_000_000L) return null;
        int minPxX = centerPxX - halfW;
        int minPzX = centerPzX - halfH;
        long[] pixels = new long[w * h];
        java.util.Arrays.fill(pixels, VoxyMerge.LONG_EMPTY);
        for (LiveTile tile : tiles.values()) {
            int tilePxX = (int) Math.floorDiv(tile.minWX, pixelBlocks);
            int tilePzX = (int) Math.floorDiv(tile.minWZ, pixelBlocks);
            int ox = tilePxX - minPxX;
            int oz = tilePzX - minPzX;
            int sx0 = Math.max(0, -ox);
            int sz0 = Math.max(0, -oz);
            int sx1 = Math.min(TILE_PIXELS, w - ox);
            int sz1 = Math.min(TILE_PIXELS, h - oz);
            if (sx0 >= sx1 || sz0 >= sz1) continue;
            for (int z = sz0; z < sz1; z++) {
                System.arraycopy(tile.pixels, z * TILE_PIXELS + sx0,
                        pixels, (oz + z) * w + ox + sx0, sx1 - sx0);
            }
        }
        long minWX = (long) minPxX * pixelBlocks;
        long minWZ = (long) minPzX * pixelBlocks;
        return new MapData.Snapshot(pixels, w, h, minWX, minWZ, pixelBlocks,
                biomeNames == null ? java.util.Map.of() : biomeNames, System.nanoTime());
    }

    /** Read-only view used by the PNG exporter. */
    public java.util.Collection<LiveTile> tilesView() {
        return java.util.List.copyOf(tiles.values());
    }

    public long pixelBytesEstimate() {
        return (long) tiles.size() * TILE_PIXELS * TILE_PIXELS * Long.BYTES;
    }

    public long rgbBytesEstimate() {
        long n = 0;
        for (LiveTile t : tiles.values()) if (t.rgb != null) n++;
        return n * TILE_PIXELS * TILE_PIXELS * Integer.BYTES;
    }

    public int textureCount() {
        int n = 0;
        for (LiveTile t : tiles.values()) if (t.texture != null) n++;
        return n;
    }

    public int sourceIndexTiles() {
        VoxyTileSource src = source;
        return src == null ? 0 : src.indexedTileCount();
    }

    /** Invalidates every tile colour/texture after a style or display-option change. */
    public void invalidateAllColors() {
        for (LiveTile tile : tiles.values()) {
            tile.liveDirty = true;
            tile.colorPending = false;
            tile.coloredVersion = -1;
            tile.colorStyleHash = 0;
            tile.textureDirty = true;
            tile.textureEpoch = -1;
        }
        colorRequestsThisFrame.set(0);
        colorVersion.incrementAndGet();
    }

    /** Drops every tile so the next frame rebuilds them from cache/Voxy storage (manual refresh). */
    public void dropAllTiles() {
        dropAllTiles(true);
    }

    /** @param persistLive write live-only tiles to disk before dropping (false while invalidating) */
    public void dropAllTiles(boolean persistLive) {
        resetGeneration.incrementAndGet();
        for (LiveTile tile : tiles.values()) {
            if (persistLive && tile.seedEpoch < 0) scheduleSave(tile);
            tile.dispose();
        }
        tiles.clear();
        seedTileCount.set(0);
        liveOnlyTileCount.set(0);
        sourceEmpty.clear();
        seedPending.clear();
        sourcePending.clear();
        maxLandY.set(Integer.MIN_VALUE);
        rampVersion.incrementAndGet();
        contentVersion.incrementAndGet();
        lastRampChangeMs = System.currentTimeMillis();
        minTileX.set(Integer.MAX_VALUE);
        maxTileX.set(Integer.MIN_VALUE);
        minTileZ.set(Integer.MAX_VALUE);
        maxTileZ.set(Integer.MIN_VALUE);
    }

    /** Deletes this tile store's on-disk cache (used when the liquid-id table was not ready yet). */
    public void clearCacheFiles() {
        cacheGeneration.incrementAndGet(); // queued writes from the dry phase must be ignored
        if (cacheDir == null) return;
        try {
            if (!Files.isDirectory(cacheDir)) return;
            try (java.util.stream.Stream<Path> files = Files.list(cacheDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".vxt"))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Throwable ignored) {
                            }
                        });
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleSave(LiveTile tile) {
        if (cacheDir == null || !cacheReady || tile.seedEpoch >= 0) return;
        final int generation = cacheGeneration.get();
        CACHE_IO.submit(() -> {
            if (generation == cacheGeneration.get()) saveCached(tile);
        });
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
        MapPerf.INSTANCE.tileObjects.increment();
        minTileX.accumulateAndGet(tx, Math::min);
        maxTileX.accumulateAndGet(tx, Math::max);
        minTileZ.accumulateAndGet(tz, Math::min);
        maxTileZ.accumulateAndGet(tz, Math::max);
    }

    /**
     * A tile's colour job uses an 8px halo from its neighbours (hillshade, contours, water depth).
     * When a neighbour appears or changes, the tile must be coloured again or it keeps a visible
     * contour/edge frame along the tile boundary.
     */
    private void invalidateNeighborColors(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                LiveTile t = tiles.get(key(tx + dx, tz + dz));
                if (t == null || t.seedEpoch >= 0) continue;
                t.liveDirty = true;
                t.coloredVersion = -1;
                t.colorPending = false;
                t.textureDirty = true;
            }
        }
    }

    public long sectionCount() {
        return sectionCount.get();
    }

    public void setSource(VoxyTileSource source) {
        this.source = source;
    }

    public void setCacheReady(boolean ready) {
        this.cacheReady = ready;
    }

    /** Highest known dry-land top; sea is returned when no land has been loaded yet. */
    public int maxLandTop(int sea) {
        int v = maxLandY.get();
        return v == Integer.MIN_VALUE ? sea : v;
    }

    public int rampVersion() {
        return rampVersion.get();
    }

    /** Increments whenever terrain pixels change; biome overlay/labels rebuild on this cheaply. */
    public int contentVersion() {
        return contentVersion.get();
    }

    public int colorVersion() {
        return colorVersion.get();
    }

    public long lastRampChangeMs() {
        return lastRampChangeMs;
    }

    private static int landTopOfPacked(long packed) {
        if (packed == VoxyMerge.LONG_EMPTY) return Integer.MIN_VALUE;
        if ((packed & (1L << 52)) != 0) return Integer.MIN_VALUE;
        int top = (int) ((packed >>> 24) & 0xFFFF) - 32768;
        int floor = (int) (packed & 0xFFFF) - 32768;
        if (top == VoxyMerge.NO_TOP) top = floor;
        if (top == VoxyMerge.NO_TOP || top == VoxyMerge.NO_SOLID) return Integer.MIN_VALUE;
        return top;
    }

    private void observeTile(LiveTile tile) {
        if (tile == null) return;
        int max = Integer.MIN_VALUE;
        for (long packed : tile.pixels) {
            int top = landTopOfPacked(packed);
            if (top > max) max = top;
        }
        if (max == tile.tileMaxLand) return;
        tile.tileMaxLand = max;
        refreshGlobalLandMax();
    }

    /** Recompute the global maximum from per-tile maxima; cheap (tile count, not pixel count). */
    private synchronized void refreshGlobalLandMax() {
        int max = Integer.MIN_VALUE;
        for (LiveTile tile : tiles.values()) {
            int v = tile.tileMaxLand;
            if (v > max) max = v;
        }
        if (max == maxLandY.get()) return;
        maxLandY.set(max);
        rampVersion.incrementAndGet();
        lastRampChangeMs = System.currentTimeMillis();
    }

    /** Rebuilds the lazy source's section index after a manual refresh. */
    public void invalidateSourceIndex() {
        VoxyTileSource src = source;
        if (src != null) src.invalidateIndex();
    }

    /** Cancel decode work for a viewport that is no longer relevant. */
    public void cancelPendingSourceRequests() {
        sourceEpoch.incrementAndGet();
        VoxyTileSource src = source;
        if (src != null) src.cancelPending();
        sourcePending.clear();
    }

    /** Lets the next visible pass re-query storage and replace any coarse LOD pixels. */
    public void markAllSourceUnfilled() {
        // Cancel/forget in-flight source requests from the old index. Without this, a request
        // started before the rescan can finish with coarse pixels and set sourceFilled=true, so
        // the fresh index is never queried for the tile.
        resetGeneration.incrementAndGet();
        sourceEpoch.incrementAndGet();
        VoxyTileSource src = source;
        if (src != null) src.cancelPending();
        sourcePending.clear();
        sourceEmpty.clear();
        for (LiveTile tile : tiles.values()) {
            tile.sourceFilled = false;
        }
    }

    private void requestSourceTile(int tx, int tz, long key) {
        VoxyTileSource src = source;
        if (src == null || sourcePending.contains(key) || sourceEmpty.contains(key)) return;
        if (!src.hasTileData(tx, tz)) {
            sourceEmpty.add(key);
            return;
        }
        sourcePending.add(key);
        final int generation = resetGeneration.get();
        final int epochAtRequest = sourceEpoch.get();
        MapPerf.INSTANCE.sourceRequests.increment();
        src.request(tx, tz, pixels -> {
            if (generation != resetGeneration.get()) return;
            if (epochAtRequest != sourceEpoch.get()) return;
            sourcePending.remove(key);
            if (pixels == null) {
                MapPerf.INSTANCE.sourceEmpty.increment();
                sourceEmpty.add(key);
                return;
            }
            MapPerf.INSTANCE.sourceHits.increment();
            LiveTile tile = new LiveTile(tx, tz, tileMinWX(tx), tileMinWZ(tz), pixels);
            tile.liveDirty = true;
            tile.sourceFilled = true;
            LiveTile previous = tiles.putIfAbsent(key, tile);
            if (previous == null) {
                liveOnlyTileCount.incrementAndGet();
                markBounds(tx, tz);
                invalidateNeighborColors(tx, tz);
                observeTile(tile);
                contentVersion.incrementAndGet();
            } else {
                previous.sourceFilled = true;
                fillMissing(previous, pixels);
            }
        });
    }

    /**
     * Live events create tiles containing only the touched sections. Fill the remaining pixels
     * from the authoritative storage tile so a partially updated tile cannot appear as a small
     * island surrounded by an empty/black rectangle (especially visible at 1:4).
     */
    private void requestSourceFill(int tx, int tz, LiveTile tile) {
        VoxyTileSource src = source;
        if (tile.sourceFilled) return;
        if (src == null) {
            tile.sourceFilled = true;
            return;
        }
        long key = key(tx, tz);
        if (sourcePending.contains(key) || sourceEmpty.contains(key)) return;
        if (sourceFillRequestsThisFrame.get() >= 48) return;
        sourceFillRequestsThisFrame.incrementAndGet();
        sourcePending.add(key);
        final int generation = resetGeneration.get();
        final int epochAtRequest = sourceEpoch.get();
        MapPerf.INSTANCE.sourceRequests.increment();
        src.request(tx, tz, pixels -> {
            if (generation != resetGeneration.get()) return;
            if (epochAtRequest != sourceEpoch.get()) return;
            sourcePending.remove(key);
            if (pixels == null) MapPerf.INSTANCE.sourceEmpty.increment();
            else MapPerf.INSTANCE.sourceHits.increment();
            LiveTile current = tiles.get(key);
            if (current == null) {
                if (pixels == null) {
                    sourceEmpty.add(key);
                    return;
                }
                LiveTile created = new LiveTile(tx, tz, tileMinWX(tx), tileMinWZ(tz), pixels);
                created.liveDirty = true;
                created.sourceFilled = true;
                if (tiles.putIfAbsent(key, created) == null) {
                    liveOnlyTileCount.incrementAndGet();
                    markBounds(tx, tz);
                    invalidateNeighborColors(tx, tz);
                }
                return;
            }
            current.sourceFilled = true;
            if (pixels == null) return;
            fillMissing(current, pixels);
        });
    }

    private void fillMissing(LiveTile tile, long[] pixels) {
        boolean changed = false;
        for (int i = 0; i < tile.pixels.length; i++) {
            long old = tile.pixels[i];
            long src = pixels[i];
            if (src == VoxyMerge.LONG_EMPTY) continue;
            if (old == VoxyMerge.LONG_EMPTY || detailLevel(src) < detailLevel(old)) {
                // Replace, not just fill: a live event may have created the pixel from a coarse
                // LOD, and leaving it would keep a rectangular coarse-height step even though the
                // storage tile has a much finer sample for the same column.
                tile.pixels[i] = src;
                changed = true;
            }
        }
        if (changed) {
            tile.liveDirty = true;
            tile.version++;
            tile.textureDirty = true;
            tile.colorPending = false;
            observeTile(tile);
            contentVersion.incrementAndGet();
            invalidateNeighborColors(tile.tx, tile.tz);
        }
    }

    private static int detailLevel(long packed) {
        if (packed == VoxyMerge.LONG_EMPTY) return 15;
        int topLvl = (int) ((packed >>> 48) & 0xF);
        int solidLvl = (int) ((packed >>> 16) & 0xF);
        return Math.min(topLvl, solidLvl);
    }

    public void beginFrame() {
        createdThisFrame.set(0);
        colorRequestsThisFrame.set(0);
        seedCreatesThisFrame.set(0);
        seedRefreshesThisFrame.set(0);
        seedRequestsThisFrame.set(0);
        sourceFillRequestsThisFrame.set(0);
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
     * 3D uses the same progressive tile pipeline as 2D: create tiles from the centre outwards and
     * request their colours, using the same per-frame budgets. No GPU texture is needed here.
     */
    public void pumpFor3D(double centerX, double centerZ, int radiusTiles,
                          String style, int sea, int colorEpoch) {
        beginFrame();
        int ccx = tileIndexX(centerX);
        int ccz = tileIndexZ(centerZ);
        int radius = Math.max(1, Math.min(32, radiusTiles));
        int styleHash = ((style == null ? 0 : style.hashCode()) * 31 + sea) * 31 + colorEpoch;
        for (int r = 0; r <= radius; r++) {
            for (int tx = ccx - r; tx <= ccx + r; tx++) {
                pump3DTile(tx, ccz - r, styleHash);
                if (r != 0) pump3DTile(tx, ccz + r, styleHash);
            }
            for (int tz = ccz - r + 1; tz <= ccz + r - 1; tz++) {
                pump3DTile(ccx - r, tz, styleHash);
                if (r != 0) pump3DTile(ccx + r, tz, styleHash);
            }
        }
        evictFar(ccx, ccz, radius + 4, 512);
    }

    private void pump3DTile(int tx, int tz, int styleHash) {
        LiveTile tile = getOrCreateSeed(tx, tz);
        if (tile == null) return;
        if (tile.version != tile.coloredVersion || tile.colorStyleHash != styleHash) {
            if (!tile.colorPending && colorRequestsThisFrame.get() < 12) {
                requestColor(tile, styleHash);
            }
        }
    }

    /**
     * Lazily creates a tile from the base snapshot. Returns null for empty/out-of-seed tiles so the
     * unified renderer does not allocate tiles for the empty margin.
     */
    public LiveTile getOrCreateSeed(int tx, int tz) {
        LiveTile existing = tiles.get(key(tx, tz));
        if (existing != null) {
            if (!existing.sourceFilled) requestSourceFill(tx, tz, existing);
            if (existing.seedEpoch < 0 && seedPixels != null && seedRgb != null
                    && seedCreatesThisFrame.get() < 32) {
                seedCreatesThisFrame.incrementAndGet();
                seedExisting(existing);
            }
            return existing;
        }
        if (seedRequestsThisFrame.get() >= 32) return null;
        long key = key(tx, tz);
        // Do not retry the cache/source for a tile that is already being decoded or was proven
        // empty. Without these checks a visible empty margin re-ran loadCached() every frame.
        if (sourcePending.contains(key) || sourceEmpty.contains(key)) return null;
        if (!seedPending.add(key)) return null;
        seedRequestsThisFrame.incrementAndGet();
        final boolean hasSeed = seedPixels != null && seedRgb != null;
        final int generation = resetGeneration.get();
        if (!hasSeed && !cacheReady) {
            seedPending.remove(key);
            requestSourceTile(tx, tz, key);
            return null;
        }
        if (!hasSeed) {
            SEED_IO.submit(() -> {
                LiveTile cached = loadCached(tx, tz);
                Minecraft.getInstance().execute(() -> {
                    if (generation != resetGeneration.get()) return;
                    seedPending.remove(key);
                    if (cached == null) {
                        MapPerf.INSTANCE.cacheMisses.increment();
                        requestSourceTile(tx, tz, key);
                        return;
                    }
                    MapPerf.INSTANCE.cacheHits.increment();
                    LiveTile previous = tiles.putIfAbsent(key, cached);
                    if (previous == null) {
                        liveOnlyTileCount.incrementAndGet();
                        markBounds(tx, tz);
                        invalidateNeighborColors(tx, tz);
                        observeTile(cached);
                        contentVersion.incrementAndGet();
                    }
                });
            });
            return null;
        }
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
                if (generation != resetGeneration.get()) return;
                seedPending.remove(key);
                if (result == null) {
                    requestSourceTile(tx, tz, key);
                    return;
                }
                LiveTile previous = tiles.putIfAbsent(key, result);
                if (previous == null) {
                    seedTileCount.incrementAndGet();
                    markBounds(tx, tz);
                    invalidateNeighborColors(tx, tz);
                    observeTile(result);
                    contentVersion.incrementAndGet();
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
        if (cacheDir == null || !cacheReady) return null;
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
        if (cacheDir == null || !cacheReady || tile.seedEpoch >= 0) return;
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
            observeTile(t);
            contentVersion.incrementAndGet();
            invalidateNeighborColors(t.tx, t.tz);
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
                if (createdThisFrame.get() >= 12) return tile.texture;
                buildTexture(tile, styleHash);
            }
            return tile.texture;
        }
        if (!tile.liveDirty) {
            if (tile.seedEpoch != seedEpoch) refreshSeed(tile);
            if (tile.rgb == null) return tile.texture;
            if (tile.texture == null || tile.textureDirty || tile.textureEpoch != styleHash) {
                if (createdThisFrame.get() >= 12) return tile.texture;
                buildTexture(tile, styleHash);
            }
            return tile.texture;
        }
        if (tile.texture != null && tile.textureEpoch == styleHash && !tile.textureDirty) {
            return tile.texture;
        }
        if (tile.version != tile.coloredVersion || tile.colorStyleHash != styleHash) {
            if (!tile.colorPending && colorRequestsThisFrame.get() < 12) {
                requestColor(tile, styleHash);
            }
            return tile.texture;
        }
        if (tile.rgb == null) return tile.texture;
        if (createdThisFrame.get() >= 12) return tile.texture;
        buildTexture(tile, styleHash);
        return tile.texture;
    }

    private void requestColor(LiveTile tile, int styleHash) {
        tile.colorPending = true;
        colorRequestsThisFrame.incrementAndGet();
        MapPerf.INSTANCE.colorRequests.increment();
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
                } else {
                    // No neighbour loaded yet: clamp this tile's edge instead of leaving an
                    // empty halo. Empty halo pixels made the contour pass draw a false frame
                    // along every tile border after a map-scale change.
                    int ownX = Math.max(0, Math.min(TILE_PIXELS - 1, gx - tile.tx * TILE_PIXELS));
                    int ownZ = Math.max(0, Math.min(TILE_PIXELS - 1, gz - tile.tz * TILE_PIXELS));
                    withHalo[z * hw + x] = tile.pixels[ownZ * TILE_PIXELS + ownX];
                }
            }
        }
        long minX = tile.minWX - (long) halo * pixelBlocks;
        long minZ = tile.minWZ - (long) halo * pixelBlocks;
        MapData.INSTANCE.submitTileColor(withHalo, hw, hw, minX, minZ, pixelBlocks, full -> {
            tile.colorPending = false;
            if (full == null || full.length < hw * hw) return;
            if (tile.version != version) {
                MapPerf.INSTANCE.colorStale.increment();
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
            colorVersion.incrementAndGet();
            MapPerf.INSTANCE.colorDone.increment();
        });
    }

    private void buildTexture(LiveTile tile, int styleHash) {
        createdThisFrame.incrementAndGet();
        MapPerf.INSTANCE.textureBuilds.increment();
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
            int dx = tile.tx - centerTx;
            int dz = tile.tz - centerTz;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > keepRadius) {
                long k = key(tile.tx, tile.tz);
                // A pending source/cache decode is still being paid for; evicting it now just
                // makes the callback recreate it and re-request the same tile later.
                if (sourcePending.contains(k) || seedPending.contains(k)) continue;
                candidates.add(tile);
            }
        }
        candidates.sort((a, b) -> {
            int da = Math.max(Math.abs(a.tx - centerTx), Math.abs(a.tz - centerTz));
            int db = Math.max(Math.abs(b.tx - centerTx), Math.abs(b.tz - centerTz));
            return Integer.compare(db, da);
        });
        int excess = tiles.size() - maxTiles;
        for (LiveTile tile : candidates) {
            if (excess-- <= 0) break;
            // live-only tiles are persisted before eviction, so a later visit can restore them
            // from cache (or rebuild from Voxy storage) instead of keeping them forever
            if (tile.seedEpoch < 0) {
                scheduleSave(tile);
            }
            if (tiles.remove(key(tile.tx, tile.tz), tile)) {
                tile.dispose();
                MapPerf.INSTANCE.evictions.increment();
                contentVersion.incrementAndGet();
                if (tile.seedEpoch < 0) {
                    liveOnlyTileCount.decrementAndGet();
                } else {
                    seedTileCount.decrementAndGet();
                }
            }
        }
        refreshGlobalLandMax();
    }

    public void dispose() {
        resetGeneration.incrementAndGet();
        for (LiveTile tile : tiles.values()) {
            if (tile.seedEpoch < 0) scheduleSave(tile);
            tile.dispose();
        }
        tiles.clear();
        VoxyTileSource src = source;
        source = null;
        if (src != null) src.close();
    }
}
