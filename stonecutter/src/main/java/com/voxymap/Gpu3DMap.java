package com.voxymap;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * GPU 3D terrain: CPU meshing on a worker, batched GPU uploads on the render thread, one
 * immutable buffer per tile, texture-atlas NEAREST sampling, frustum culling and back-face culling
 * (SOLID_BLOCK pipeline).
 *
 * <p>26.2 的 BLOCK 顶点格式只有 Position/Color/UV0/UV2（没有 Normal），所以顶点循环里不再写
 * setNormal；法线只影响着色，SOLID_BLOCK 的 core/block 着色器本来就不读它。</p>
 */
public final class Gpu3DMap {
    private static final int ATLAS = 1024;
    private static final int CELL = 256;
    private static final int COLS = ATLAS / CELL;
    private static final int SLOTS = COLS * COLS;
    private static final int MAX_JOBS = 32;
    private static final int RETIRE_FRAMES = 3;
    private static int batchBuild() {
        return Math.max(1, Math.min(32, VoxyMapConfig.GPU3D_BATCH_BUILD.get()));
    }
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final RenderPipeline PIPELINE = RenderPipelines.SOLID_BLOCK;
    private static final RenderPipeline OVERLAY_PIPELINE = overlayPipeline();
    private static final int OVERLAY_SEGMENTS = 64;
    private static GpuBuffer overlayVbo;
    private static GpuBuffer overlayIbo;
    private static int overlayIndexCount;

    /**
     * The distance ribbon re-uses vanilla's debug-quads pipeline: position+color vertices, QUADS
     * topology (mapped to GL_TRIANGLES by the backend), translucent blending, depth testing without
     * depth writes and culling disabled - exactly what the ribbon needs, and per-version correct
     * on both 26.2 (reversed Z) and 26.1.2 (standard Z).
     */
    private static RenderPipeline overlayPipeline() {
        return RenderPipelines.DEBUG_QUADS;
    }
    private static final Map<Long, TileGpu> TILES = new HashMap<>();
    /**
     * Tiles whose stepped mesh is empty (no renderable grid cell). Value = the stamp/step at which
     * that was observed, so tick() stops re-submitting them every frame. Render thread only.
     */
    private static final Map<Long, EmptyMark> EMPTY = new HashMap<>();
    private static final List<Page> PAGES = new ArrayList<>();
    private static final Set<Long> IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<Long> CANCELLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<BuildResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static final java.util.ArrayDeque<Long> TEXTURE_REFRESH = new java.util.ArrayDeque<>();
    private static final List<RetiredBuffer> RETIRED = new ArrayList<>();
    private static long frameCounter;
    private static long lastOverlayKey = Long.MIN_VALUE;
    /** Shared clip-plane scratch; boxVisible() only runs on the render thread. */
    private static final float[] CLIP_PLANES = new float[24];
    /** Shared 256x256 RGBA staging buffer for atlas uploads; render thread only. */
    private static final ByteBuffer ATLAS_SCRATCH =
            ByteBuffer.allocateDirect(CELL * CELL * 4).order(ByteOrder.nativeOrder());
    private static final ThreadPoolExecutor BUILD_POOL = new ThreadPoolExecutor(
            buildThreads(), buildThreads(), 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "voxymap-gpu3d-build");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public static void reconfigureBuildThreads() {
        int n = buildThreads();
        try {
            // ThreadPoolExecutor.setMaximumPoolSize() throws when the new maximum is below the
            // current core size, so shrink the core first and grow the maximum first.
            if (n < BUILD_POOL.getCorePoolSize()) {
                BUILD_POOL.setCorePoolSize(n);
                BUILD_POOL.setMaximumPoolSize(n);
            } else {
                BUILD_POOL.setMaximumPoolSize(n);
                BUILD_POOL.setCorePoolSize(n);
            }
        } catch (IllegalArgumentException e) {
            // Never let a config change crash the client; the old pool size stays in effect.
            System.out.println("[voxymap] gpu3d build thread resize failed: " + e);
        }
    }

    /**
     * Records that a tile has no stepped geometry for a given stamp/step. A finer step may still
     * have data (grid sampling differs), so tick() re-tries when it wants a finer step than the
     * one that was found empty.
     */
    private static final class EmptyMark {
        final long stamp;
        final int step;

        EmptyMark(long stamp, int step) {
            this.stamp = stamp;
            this.step = step;
        }
    }

    private static final class RetiredBuffer {
        final GpuBuffer buffer;
        final long frame;

        RetiredBuffer(GpuBuffer buffer, long frame) {
            this.buffer = buffer;
            this.frame = frame;
        }
    }

    private static int buildThreads() {
        int configured = VoxyMapConfig.GPU3D_BUILD_THREADS.get();
        if (configured <= 0) {
            return Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
        }
        return Math.max(1, Math.min(16, configured));
    }
    private static GpuSampler sampler;
    private static GpuSampler lightSampler;
    private static ProjectionMatrixBuffer projection;
    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static int depthWidth, depthHeight;
    private static long lastLog;
    private static volatile int lastVisible, lastTotal;
    private static volatile int generation;
    private static volatile long lastBuildNanos, lastUploadNanos;
    private static volatile int lastUploadTiles, lastDrawTiles, lastAtlasPages;
    private static volatile long lastGpuBytes, lastAtlasBytes, lastDepthBytes;
    private static long gpuBytes;
    private static long lastVramLogMs;

    private Gpu3DMap() {
    }

    private static final class Page {
        final GpuTexture texture;
        final GpuTextureView view;
        final boolean[] used = new boolean[SLOTS];

        Page() {
            texture = BlazeCompat.createColorTexture("voxymap_gpu3d_atlas",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, ATLAS, ATLAS);
            view = RenderSystem.getDevice().createTextureView(texture);
        }
    }

    private static final class Slot {
        final Page page;
        final int index;
        boolean released;

        Slot(Page page, int index) {
            this.page = page;
            this.index = index;
        }
    }

    private static final class TileGpu {
        final GpuBuffer vertices;
        final GpuBuffer indices;
        final int indexCount;
        final int step;
        final long stamp;
        final double centerX, centerZ;
        final double minY, maxY;
        final Slot slot;
        final long bytes;

        TileGpu(GpuBuffer vertices, GpuBuffer indices, int indexCount, int step,
                long stamp, double centerX, double centerZ, double minY, double maxY, Slot slot,
                long bytes) {
            this.vertices = vertices;
            this.indices = indices;
            this.indexCount = indexCount;
            this.step = step;
            this.stamp = stamp;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.minY = minY;
            this.maxY = maxY;
            this.slot = slot;
            this.bytes = bytes;
        }
    }

    private static final class BuildResult {
        final int generation;
        final long key;
        final long stamp;
        final Slot slot;
        final ByteBuffer vertices;
        final ByteBuffer indices;
        /** 0 means "this tile has no renderable geometry"; vertices/indices are null then. */
        final int indexCount;
        final int step;
        final double centerX, centerZ, minY, maxY;
        final long buildNanos;

        BuildResult(int generation, long key, long stamp, Slot slot, ByteBuffer vertices, ByteBuffer indices,
                    int indexCount, int step,
                    double centerX, double centerZ, double minY, double maxY, long buildNanos) {
            this.generation = generation;
            this.key = key;
            this.stamp = stamp;
            this.slot = slot;
            this.vertices = vertices;
            this.indices = indices;
            this.indexCount = indexCount;
            this.step = step;
            this.buildNanos = buildNanos;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static void ensureAtlas() {
        if (sampler != null) return;
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    /** Upper bound for GPU tiles derived from the shared tile-texture VRAM budget. */
    private static int maxGpuTiles() {
        // gpuTextureBudgetMb is also used by the 2D tile textures; each 3D tile owns one
        // 256x256 RGBA atlas slot (4 slots per MiB), so this bounds the 3D atlas to that budget.
        int budgetMb = Math.max(16, VoxyMapConfig.GPU_TEXTURE_BUDGET_MB.get());
        return Math.max(16, budgetMb * 4);
    }

    /** Real VRAM used by the atlas pages, in bytes (for the debug panel / reporting). */
    private static long atlasBytes() {
        return (long) PAGES.size() * ATLAS * ATLAS * 4L;
    }

    /** Real VRAM used by the private depth attachment, in bytes. */
    private static long depthBytes() {
        return (long) depthWidth * depthHeight * 4L;
    }

    private static Slot allocateSlot() {
        ensureAtlas();
        for (Page page : PAGES) {
            for (int i = 0; i < SLOTS; i++) {
                if (!page.used[i]) {
                    page.used[i] = true;
                    return new Slot(page, i);
                }
            }
        }
        Page page = new Page();
        page.used[0] = true;
        PAGES.add(page);
        return new Slot(page, 0);
    }

    private static void releaseSlot(Slot slot) {
        if (slot == null || slot.released) return;
        slot.released = true;
        slot.page.used[slot.index] = false;
        for (boolean used : slot.page.used) {
            if (used) return;
        }
        PAGES.remove(slot.page);
        slot.page.view.close();
        slot.page.texture.close();
    }

    private static void retireBuffer(GpuBuffer buffer) {
        if (buffer == null) return;
        RETIRED.add(new RetiredBuffer(buffer, frameCounter));
    }

    private static void retireTile(TileGpu tile) {
        retireBuffer(tile.vertices);
        retireBuffer(tile.indices);
        gpuBytes -= tile.bytes;
    }

    private static void flushRetired() {
        for (Iterator<RetiredBuffer> it = RETIRED.iterator(); it.hasNext(); ) {
            RetiredBuffer r = it.next();
            if (frameCounter - r.frame < RETIRE_FRAMES) continue;
            try {
                r.buffer.close();
            } catch (Throwable ignored) {
            }
            it.remove();
        }
    }

    private static void evictUnloaded(LiveTileStore live) {
        for (Iterator<Map.Entry<Long, TileGpu>> it = TILES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, TileGpu> entry = it.next();
            long k = entry.getKey();
            int tx = (int) (k >> 32);
            int tz = (int) k;
            LiveTileStore.LiveTile current = live.get(tx, tz);
            if (current != null && current.rgb != null) continue;
            TileGpu old = entry.getValue();
            it.remove();
            if (IN_FLIGHT.contains(k)) CANCELLED.add(k);
            retireTile(old);
            EMPTY.remove(k);
            if (!IN_FLIGHT.contains(k)) releaseSlot(old.slot);
        }
    }

    /**
     * Drops GPU tiles outside the atlas budget, farthest first. {@code order} is the ascending
     * distance order computed by tick(), so the nearest {@code cap} candidates are kept. Runs as
     * soon as TILES reaches the cap (not only above it): otherwise the tiles of a previous camera
     * position would occupy every slot forever and new nearby tiles could never be built.
     */
    private static void evictOverBudget(List<LiveTileStore.LiveTile> sorted, long[] order, int cap) {
        int keepCount = Math.min(cap, order.length);
        long[] keep = new long[keepCount];
        for (int i = 0; i < keepCount; i++) {
            LiveTileStore.LiveTile tile = sorted.get((int) order[i]);
            keep[i] = key(tile.tx, tile.tz);
        }
        java.util.Arrays.sort(keep);
        for (Iterator<Map.Entry<Long, TileGpu>> it = TILES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, TileGpu> entry = it.next();
            long k = entry.getKey();
            if (java.util.Arrays.binarySearch(keep, k) >= 0) continue;
            TileGpu old = entry.getValue();
            it.remove();
            if (IN_FLIGHT.contains(k)) CANCELLED.add(k);
            retireTile(old);
            EMPTY.remove(k);
            if (!IN_FLIGHT.contains(k)) releaseSlot(old.slot);
        }
    }

    private static void ensureProjection() {
        if (projection == null) projection = new ProjectionMatrixBuffer("voxymap_gpu3d_projection");
    }

    private static void ensureDepth(int width, int height) {
        if (depthTexture != null && depthWidth == width && depthHeight == height) return;
        if (depthView != null) depthView.close();
        if (depthTexture != null) depthTexture.close();
        depthTexture = BlazeCompat.createDepthTexture("voxymap_gpu3d_depth", width, height);
        depthView = RenderSystem.getDevice().createTextureView(depthTexture);
        depthWidth = width;
        depthHeight = height;
    }

    public static void tick(MapData data, Map3D.Camera camera) {
        // currentLiveTiles() is a volatile read; getLiveTiles() would run findStorage() (directory
        // IO) on the render thread every frame. Only fall back when no store exists yet.
        LiveTileStore found = data.currentLiveTiles();
        if (found == null) found = data.getLiveTiles();
        if (found == null || camera == null) return;
        final LiveTileStore live = found;
        drainResults(live);
        refreshOverlayTextures(live);
        frameCounter++;
        flushRetired();
        evictUnloaded(live);
        int gen = generation;
        int budget = batchBuild();
        int pb = Math.max(1, MapData.INSTANCE.getPixelBlocks());
        // Global geometry inputs are read once per frame on the render thread and passed down to the
        // workers: the worker must not touch MapData/Minecraft state (and the stamp must cover them,
        // otherwise a config change would not rebuild the meshes).
        int sea = MapData.INSTANCE.seaLevel();
        double vscale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
        int atlasCap = maxGpuTiles();
        List<LiveTileStore.LiveTile> candidates = new ArrayList<>();
        for (LiveTileStore.LiveTile tile : live.tilesView()) {
            if (tile.rgb != null && tile.pixels != null) candidates.add(tile);
        }
        int count = candidates.size();
        double eyeX = camera.eyeX();
        double eyeY = camera.eyeY();
        double eyeZ = camera.eyeZ();
        // One distance evaluation per candidate per frame (the comparator used to call
        // tileDistance() -> MapData.heightAt() 2*n*log(n) times). Pack (distance, index) into a
        // long and sort that instead, then reuse the cached distances below.
        long[] order = new long[count];
        double[] distances = new double[count];
        for (int i = 0; i < count; i++) {
            double distance = tileDistance(candidates.get(i), eyeX, eyeY, eyeZ, vscale, pb);
            distances[i] = distance;
            order[i] = (Math.min(Integer.MAX_VALUE, (long) distance) << 32) | i;
        }
        java.util.Arrays.sort(order);
        if (TILES.size() >= atlasCap) evictOverBudget(candidates, order, atlasCap);
        int knownEmpty = 0;
        for (LiveTileStore.LiveTile tile : candidates) {
            if (EMPTY.containsKey(key(tile.tx, tile.tz))) knownEmpty++;
        }
        int buildable = candidates.size() - knownEmpty;
        // First pass: every loaded tile gets a cheap step-32 mesh. Only after the whole map is
        // covered do we refine near tiles to step 4 and then step 1.
        boolean filling = TILES.size() < buildable && TILES.size() < atlasCap;
        int submitted = 0;
        for (int oi = 0; oi < count; oi++) {
            int ci = (int) order[oi];
            LiveTileStore.LiveTile tile = candidates.get(ci);
            long key = key(tile.tx, tile.tz);
            if (IN_FLIGHT.contains(key)) continue;
            TileGpu old = TILES.get(key);
            long stamp = stamp(live, tile, pb, sea, vscale);
            double distance = distances[ci];
            int targetStep;
            if (filling) {
                if (old != null) continue;
                targetStep = meshStep(VoxyMapConfig.GPU_TERRAIN_FAR_STEP.get());
            } else {
                targetStep = desiredStep(distance, old == null
                        ? meshStep(VoxyMapConfig.GPU_TERRAIN_FAR_STEP.get()) : old.step);
            }
            if (old != null && old.stamp == stamp && old.step == targetStep) continue;
            if (old == null) {
                EmptyMark mark = EMPTY.get(key);
                if (mark != null) {
                    if (mark.stamp == stamp && targetStep >= mark.step) continue;
                    if (mark.stamp != stamp) EMPTY.remove(key);
                }
                if (TILES.size() >= atlasCap) continue;
            }
            if (submitted >= budget || IN_FLIGHT.size() >= MAX_JOBS) break;
            Slot slot = old == null ? allocateSlot() : old.slot;
            final boolean isNew = old == null;
            long[] pixels = tile.pixels;
            int[] rgb = tile.rgb;
            int step = targetStep;
            CANCELLED.remove(key);
            IN_FLIGHT.add(key);
            BUILD_POOL.execute(() -> {
                try {
                    BuildResult r = build(gen, key, stamp, slot, step, tile, pixels, rgb, live, pb, sea, vscale);
                    if (r == null) {
                        if (gen == generation) {
                            IN_FLIGHT.remove(key);
                            if (isNew) releaseSlot(slot);
                        }
                    } else if (CANCELLED.remove(key) || gen != generation) {
                        IN_FLIGHT.remove(key);
                        if (gen == generation) releaseSlot(slot);
                    } else {
                        RESULTS.add(r);
                    }
                } catch (Throwable t) {
                    if (gen == generation) {
                        IN_FLIGHT.remove(key);
                        // A failed build must not leak the atlas slot it was given.
                        if (isNew) releaseSlot(slot);
                    }
                    System.out.println("[voxymap] gpu3d build failed: " + t);
                }
            });
            submitted++;
        }
        long now = System.currentTimeMillis();
        if (now - lastLog > 2000L) {
            lastLog = now;
            System.out.println("[voxymap] gpu3d tiles=" + TILES.size()
                    + " inflight=" + IN_FLIGHT.size());
        }
    }

    private static void drainResults(LiveTileStore live) {
        BuildResult r;
        List<BuildResult> batch = new ArrayList<>();
        while ((r = RESULTS.poll()) != null) batch.add(r);
        if (batch.isEmpty()) return;
        long uploadStart = System.nanoTime();
        int uploaded = 0;
        long buildNanos = 0;
        int pb = Math.max(1, MapData.INSTANCE.getPixelBlocks());
        int sea = MapData.INSTANCE.seaLevel();
        double vscale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (BuildResult b : batch) {
            if (b.generation != generation) continue;
            IN_FLIGHT.remove(b.key);
            LiveTileStore.LiveTile current = live.get((int) (b.key >> 32), (int) b.key);
            boolean valid = current != null && current.rgb != null
                    && stamp(live, current, pb, sea, vscale) == b.stamp;
            if (b.indexCount == 0) {
                // Fully empty tile: drop any previous geometry, remember the stamp/step so tick()
                // does not re-submit it every frame, and hand the atlas slot back.
                TileGpu old = TILES.remove(b.key);
                if (old != null) {
                    retireTile(old);
                    if (old.slot != b.slot) releaseSlot(old.slot);
                }
                releaseSlot(b.slot);
                if (valid) {
                    EMPTY.put(b.key, new EmptyMark(b.stamp, b.step));
                } else {
                    EMPTY.remove(b.key);
                }
                continue;
            }
            if (!valid) {
                if (TILES.get(b.key) == null) releaseSlot(b.slot);
                continue;
            }
            long bytes = b.vertices.remaining() + b.indices.remaining();
            uploadTileTexture(encoder, b.slot, current.rgb, current, live);
            GpuBuffer vbuf = upload(encoder, "voxymap_gpu3d_v", GpuBuffer.USAGE_VERTEX, b.vertices);
            GpuBuffer ibuf = upload(encoder, "voxymap_gpu3d_i", GpuBuffer.USAGE_INDEX, b.indices);
            TileGpu old = TILES.put(b.key, new TileGpu(vbuf, ibuf, b.indexCount,
                    b.step, b.stamp, b.centerX, b.centerZ, b.minY, b.maxY, b.slot, bytes));
            EMPTY.remove(b.key);
            gpuBytes += bytes;
            if (old != null) retireTile(old);
            uploaded++;
            buildNanos += b.buildNanos;
        }
        BlazeCompat.submit(encoder);
        if (uploaded > 0) {
            lastUploadNanos = System.nanoTime() - uploadStart;
            lastUploadTiles = uploaded;
            lastBuildNanos = buildNanos / uploaded;
            lastGpuBytes = gpuBytes;
            lastAtlasPages = PAGES.size();
            lastAtlasBytes = atlasBytes();
        }
    }

    private static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    /** Spherical distance from the real camera eye to the tile centre. */
    private static double tileDistance(LiveTileStore.LiveTile tile,
                                       double ex, double ey, double ez,
                                       double vscale, int pb) {
        double cx = tile.minWX + LiveTileStore.TILE_PIXELS * pb * 0.5;
        double cz = tile.minWZ + LiveTileStore.TILE_PIXELS * pb * 0.5;
        double cy = MapData.INSTANCE.heightAt((int) cx, (int) cz) * vscale;
        double dx = cx - ex;
        double dy = cy - ey;
        double dz = cz - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int desiredStep(double distance, int oldStep) {
        double near = VoxyMapConfig.GPU_TERRAIN_NEAR_DISTANCE.get();
        double mid = VoxyMapConfig.GPU_TERRAIN_MID_DISTANCE.get();
        int nearStep = meshStep(VoxyMapConfig.GPU_TERRAIN_NEAR_STEP.get());
        int midStep = meshStep(VoxyMapConfig.GPU_TERRAIN_MID_STEP.get());
        int farStep = meshStep(VoxyMapConfig.GPU_TERRAIN_FAR_STEP.get());
        double hysteresis = Math.max(0, Math.min(50,
                VoxyMapConfig.GPU_TERRAIN_HYSTERESIS_PCT.get())) / 100.0;
        double expand = 1.0 + hysteresis;
        double shrink = 1.0 - hysteresis;
        if (oldStep <= nearStep) {
            if (distance <= near * expand) return nearStep;
            return distance <= mid * expand ? midStep : farStep;
        }
        if (oldStep <= midStep) {
            if (distance < near * shrink) return nearStep;
            return distance <= mid * expand ? midStep : farStep;
        }
        if (distance < near * shrink) return nearStep;
        if (distance < mid * shrink) return midStep;
        return farStep;
    }

    /** Mesh steps must divide the 256-pixel tile exactly. */
    private static int meshStep(int configured) {
        int step = Math.max(1, Math.min(256, configured));
        while (step > 1 && LiveTileStore.TILE_PIXELS % step != 0) step--;
        return step;
    }

    private static long stamp(LiveTileStore live, LiveTileStore.LiveTile tile,
                              int pb, int sea, double vscale) {
        long stamp = ((long) tile.version << 32) ^ (tile.coloredVersion & 0xFFFFFFFFL);
        stamp = stamp * 31L + tile.colorStyleHash;
        // Geometry also depends on these globals; without them a config change (map scale, sea
        // level, vertical scale) would keep the old mesh forever.
        stamp = stamp * 31L + (Long.rotateLeft(Double.doubleToLongBits(vscale), 17) ^ (long) pb);
        stamp = stamp * 31L + sea;
        // Boundary vertices use the right/bottom neighbour's first pixel. Rebuild this tile when
        // those neighbours change, otherwise two tiles would keep different heights at the seam.
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                LiveTileStore.LiveTile n = live.get(tile.tx + dx, tile.tz + dz);
                stamp = stamp * 31L + (n == null ? 0 : n.coloredVersion);
            }
        }
        return stamp;
    }

    /** Biome overlay only changes the atlas texture, never the terrain geometry. */
    private static long overlayKey() {
        // Edge pixels are derived per tile, so only the toggle state matters. There is no
        // viewport-sized biome edge texture to rebuild as the 3D camera moves.
        return MapData.INSTANCE.isBiomeOverlay() ? 1L : 0L;
    }

    private static void refreshOverlayTextures(LiveTileStore live) {
        long key = overlayKey();
        if (key != lastOverlayKey) {
            lastOverlayKey = key;
            TEXTURE_REFRESH.clear();
            TEXTURE_REFRESH.addAll(TILES.keySet());
        }
        if (TEXTURE_REFRESH.isEmpty()) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        int budget = 32;
        while (budget-- > 0 && !TEXTURE_REFRESH.isEmpty()) {
            long key2 = TEXTURE_REFRESH.removeFirst();
            TileGpu tile = TILES.get(key2);
            if (tile == null) continue;
            LiveTileStore.LiveTile current = live.get((int) (key2 >> 32), (int) key2);
            if (current == null || current.rgb == null) continue;
            uploadTileTexture(encoder, tile.slot, current.rgb, current, live);
        }
        BlazeCompat.submit(encoder);
    }

    /**
     * Reusable per-worker scratch for the mesh arrays. step>=4 tiles are built many times per
     * session, so caching their arrays avoids ~100 KB of garbage per build; step 1/2 tiles are rare
     * and larger, so they still allocate (keeping memory bounded per thread).
     */
    private static final class BuildScratch {
        private static final int MAX_CACHED_VERTS = 5000;
        private static final int MAX_CACHED_INDICES = 30000;
        private static final ThreadLocal<BuildScratch> LOCAL = ThreadLocal.withInitial(BuildScratch::new);

        float[] px = new float[0], py = new float[0], pz = new float[0], pu = new float[0], pv = new float[0];
        boolean[] empty = new boolean[0];
        int[] idx = new int[0];

        static BuildScratch get(int verts, int grid) {
            int indexLen = grid * grid * 6 + 4 * grid * 12;
            if (verts > MAX_CACHED_VERTS || indexLen > MAX_CACHED_INDICES) {
                return new BuildScratch(verts, grid, indexLen);
            }
            BuildScratch s = LOCAL.get();
            s.px = grow(s.px, verts);
            s.py = grow(s.py, verts);
            s.pz = grow(s.pz, verts);
            s.pu = grow(s.pu, verts);
            s.pv = grow(s.pv, verts);
            s.empty = grow(s.empty, (grid + 1) * (grid + 1));
            s.idx = grow(s.idx, indexLen);
            return s;
        }

        private BuildScratch() {
        }

        private BuildScratch(int verts, int grid, int indexLen) {
            px = new float[verts];
            py = new float[verts];
            pz = new float[verts];
            pu = new float[verts];
            pv = new float[verts];
            empty = new boolean[(grid + 1) * (grid + 1)];
            idx = new int[indexLen];
        }

        private static float[] grow(float[] a, int len) {
            return a.length >= len ? a : new float[len];
        }

        private static boolean[] grow(boolean[] a, int len) {
            return a.length >= len ? a : new boolean[len];
        }

        private static int[] grow(int[] a, int len) {
            return a.length >= len ? a : new int[len];
        }
    }

    private static BuildResult build(int gen, long key, long stamp, Slot slot, int step,
                                     LiveTileStore.LiveTile tile, long[] pixels, int[] rgb,
                                     LiveTileStore live, int pb, int sea, double vscale) {
        long buildStart = System.nanoTime();
        int grid = LiveTileStore.TILE_PIXELS / step;
        int gw = grid + 1;
        int perim = 4 * grid;
        int maxVerts = gw * gw + perim * 2;
        BuildScratch scratch = BuildScratch.get(maxVerts, grid);
        float[] px = scratch.px;
        float[] py = scratch.py;
        float[] pz = scratch.pz;
        float[] pu = scratch.pu;
        float[] pv = scratch.pv;
        boolean[] empty = scratch.empty;
        int[] idx = scratch.idx;
        int vi = 0, ii = 0;
        int sx = (slot.index % COLS) * CELL;
        int sy = (slot.index / COLS) * CELL;
        // Atlas UVs address texel centres: (sx + min(x, 255) + 0.5) / ATLAS. The old linear mapping
        // from (sx+0.5) to (sx+255.5) compressed by 255/256 and sampled the wrong texel from x>128.
        final int texMax = LiveTileStore.TILE_PIXELS - 1;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int gz = 0; gz <= grid; gz++) {
            int z = gz * step;
            long wz = tile.minWZ + (long) z * pb;
            float v = (sy + Math.min(texMax, z) + 0.5f) / ATLAS;
            for (int gx = 0; gx <= grid; gx++) {
                int x = gx * step;
                long wx = tile.minWX + (long) x * pb;
                int sxm = Math.min(texMax, x);
                int szm = Math.min(texMax, z);
                long packed = sampleBoundary(tile, pixels, x, z, sxm, szm, live);
                int top = packed == VoxyMerge.LONG_EMPTY ? VoxyMerge.NO_TOP
                        : (int) ((packed >>> 24) & 0xFFFF) - 32768;
                int floor = packed == VoxyMerge.LONG_EMPTY ? VoxyMerge.NO_SOLID
                        : (int) (packed & 0xFFFF) - 32768;
                boolean e = packed == VoxyMerge.LONG_EMPTY
                        || (top == VoxyMerge.NO_TOP && floor == VoxyMerge.NO_SOLID);
                float y;
                if (e) {
                    y = 0f;
                } else if ((packed & (1L << 52)) != 0) {
                    y = (float) (sea * vscale);
                } else if (top == VoxyMerge.NO_TOP) {
                    y = (float) (floor * vscale);
                } else {
                    y = (float) (top * vscale);
                }
                px[vi] = wx;
                py[vi] = y;
                pz[vi] = wz;
                pu[vi] = (sx + Math.min(texMax, x) + 0.5f) / ATLAS;
                pv[vi] = v;
                empty[vi++] = e;
                if (!e) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        for (int gz = 0; gz < grid; gz++) {
            for (int gx = 0; gx < grid; gx++) {
                int a = gz * gw + gx;
                int b = a + 1;
                int c = a + gw;
                int d = c + 1;
                if (empty[a] || empty[b] || empty[c] || empty[d]) continue;
                idx[ii++] = a; idx[ii++] = c; idx[ii++] = b;
                idx[ii++] = b; idx[ii++] = c; idx[ii++] = d;
            }
        }
        double cx = tile.minWX + LiveTileStore.TILE_PIXELS * pb * 0.5;
        double cz = tile.minWZ + LiveTileStore.TILE_PIXELS * pb * 0.5;
        if (maxY == -Double.MAX_VALUE) {
            // Entirely empty tile: every border corner is empty too, so the skirt loop below would
            // skip every segment. Report the empty mesh early and avoid the skirt bookkeeping.
            return new BuildResult(gen, key, stamp, slot, null, null, 0, step,
                    cx, cz, 0.0, 0.0, System.nanoTime() - buildStart);
        }
        // Vertical skirt around the tile hides cracks between different LOD steps. Segments that
        // touch an empty (unloaded) corner are skipped: there is no terrain edge to hide there and
        // the skirt used to drop to y=-skirt, which showed up as a curtain at the map border.
        int[] border = new int[perim];
        int bp = 0;
        for (int x = 0; x <= grid; x++) border[bp++] = x;
        for (int z = 1; z <= grid; z++) border[bp++] = z * gw + grid;
        for (int x = grid - 1; x >= 0; x--) border[bp++] = grid * gw + x;
        for (int z = grid - 1; z >= 1; z--) border[bp++] = z * gw;
        for (int s = 0; s < perim; s++) {
            int ta = border[s];
            int tb = border[(s + 1) % perim];
            if (empty[ta] || empty[tb]) continue;
            int ba = vi;
            addSkirtVertex(px, py, pz, pu, pv, ba, ta);
            int bb = vi + 1;
            addSkirtVertex(px, py, pz, pu, pv, bb, tb);
            vi += 2;
            minY = Math.min(minY, py[ba]);
            // Both windings: skirt visibility must not depend on tile border direction / culling.
            idx[ii++] = ta; idx[ii++] = ba; idx[ii++] = tb;
            idx[ii++] = tb; idx[ii++] = ba; idx[ii++] = bb;
            idx[ii++] = ta; idx[ii++] = tb; idx[ii++] = ba;
            idx[ii++] = tb; idx[ii++] = bb; idx[ii++] = ba;
        }
        if (ii == 0) {
            // Nothing renderable: report an empty mesh (indexCount 0) instead of null so the render
            // thread can remember it and stop re-submitting this tile every frame.
            return new BuildResult(gen, key, stamp, slot, null, null, 0, step,
                    cx, cz, 0.0, 0.0, System.nanoTime() - buildStart);
        }
        VertexFormat format = BlazeCompat.vertexFormat(PIPELINE);
        if (format == null) return null;
        ByteBufferBuilder alloc = new ByteBufferBuilder(Math.max(1024, vi * format.getVertexSize()));
        BufferBuilder bb = BlazeCompat.newBufferBuilder(alloc, format);
        for (int i = 0; i < vi; i++) {
            // BLOCK has no Normal element in 26.2, so setNormal would be a no-op.
            bb.addVertex(px[i], py[i], pz[i])
                    .setColor(255, 255, 255, 255)
                    .setUv(pu[i], pv[i])
                    .setUv2(240, 240);
        }
        MeshData mesh = bb.buildOrThrow();
        ByteBuffer vdata = copy(mesh.vertexBuffer());
        mesh.close();
        alloc.close();
        ByteBuffer idata = ByteBuffer.allocateDirect(ii * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < ii; i++) idata.putInt(idx[i]);
        idata.flip();
        return new BuildResult(gen, key, stamp, slot, vdata, idata, ii, step,
                cx, cz, minY, maxY, System.nanoTime() - buildStart);
    }

    private static void addSkirtVertex(float[] px, float[] py, float[] pz,
                                       float[] pu, float[] pv, int target, int source) {
        px[target] = px[source];
        py[target] = (float) (py[source]
                - VoxyMapConfig.GPU_TERRAIN_SKIRT_DEPTH.get());
        pz[target] = pz[source];
        pu[target] = pu[source];
        pv[target] = pv[source];
    }

    /**
     * Boundary vertices must use exactly the same pixel the neighbouring tile uses for its first
     * row/column. Sampling through MapData here could read a different (snapshot/live) value and
     * produced visible vertical seams even though both tiles had the same 4x4 resolution.
     *
     * <p>The LiveTileStore is passed in by tick(): calling MapData.getLiveTiles() here used to run
     * ensure2DStore()/findStorage() (a directory scan) once per boundary vertex on the worker.</p>
     */
    private static long sampleBoundary(LiveTileStore.LiveTile tile, long[] pixels,
                                       int x, int z, int sxm, int szm,
                                       LiveTileStore live) {
        if (x < LiveTileStore.TILE_PIXELS && z < LiveTileStore.TILE_PIXELS) {
            return pixels[szm * LiveTileStore.TILE_PIXELS + sxm];
        }
        int ntx = tile.tx + (x == LiveTileStore.TILE_PIXELS ? 1 : 0);
        int ntz = tile.tz + (z == LiveTileStore.TILE_PIXELS ? 1 : 0);
        int nx = x == LiveTileStore.TILE_PIXELS ? 0 : x;
        int nz = z == LiveTileStore.TILE_PIXELS ? 0 : z;
        LiveTileStore.LiveTile n = live == null ? null : live.get(ntx, ntz);
        if (n != null && n.pixels != null) {
            return n.pixels[nz * LiveTileStore.TILE_PIXELS + nx];
        }
        return pixels[szm * LiveTileStore.TILE_PIXELS + sxm];
    }

    private static ByteBuffer copy(ByteBuffer src) {
        src.rewind();
        ByteBuffer out = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder());
        out.put(src);
        out.flip();
        return out;
    }

    private static GpuBuffer upload(com.mojang.blaze3d.systems.CommandEncoder encoder,
                                    String label, int usage, ByteBuffer data) {
        int size = data.remaining();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> label, usage | GpuBuffer.USAGE_COPY_DST, size);
        encoder.writeToBuffer(new GpuBufferSlice(buffer, 0L, size), data);
        return buffer;
    }

    private static void uploadTileTexture(com.mojang.blaze3d.systems.CommandEncoder encoder,
                                          Slot slot, int[] rgb, LiveTileStore.LiveTile tile,
                                          LiveTileStore live) {
        MapData data = MapData.INSTANCE;
        boolean biomes = data.isBiomeOverlay();
        long[] pixels = tile.pixels;
        LiveTileStore.LiveTile right = biomes && live != null ? live.get(tile.tx + 1, tile.tz) : null;
        LiveTileStore.LiveTile bottom = biomes && live != null ? live.get(tile.tx, tile.tz + 1) : null;
        long[] rightPixels = right == null ? null : right.pixels;
        long[] bottomPixels = bottom == null ? null : bottom.pixels;
        Page page = slot.page;
        int ox = (slot.index % COLS) * CELL;
        int oy = (slot.index / COLS) * CELL;
        // Reused staging buffer: this used to allocate 256 KB per tile per upload.
        ByteBuffer cell = ATLAS_SCRATCH;
        cell.clear();
        for (int y = 0; y < CELL; y++) {
            int row = y * LiveTileStore.TILE_PIXELS;
            for (int x = 0; x < CELL; x++) {
                int c = rgb[row + x];
                if (biomes && pixels != null) {
                    long packed = pixels[row + x];
                    if (packed != VoxyMerge.LONG_EMPTY) {
                        int biome = (int) ((packed >>> 53) & 0x1FF);
                        long east = x < LiveTileStore.TILE_PIXELS - 1
                                ? pixels[row + x + 1]
                                : (rightPixels == null ? VoxyMerge.LONG_EMPTY
                                        : rightPixels[row]);
                        long south = y < LiveTileStore.TILE_PIXELS - 1
                                ? pixels[row + LiveTileStore.TILE_PIXELS + x]
                                : (bottomPixels == null ? VoxyMerge.LONG_EMPTY
                                        : bottomPixels[x]);
                        boolean edge = (east != VoxyMerge.LONG_EMPTY
                                && biome != (int) ((east >>> 53) & 0x1FF))
                                || (south != VoxyMerge.LONG_EMPTY
                                && biome != (int) ((south >>> 53) & 0x1FF));
                        if (edge) {
                            int alpha = ((x + y) & 1) == 0 ? 0xC0 : 0x40;
                            int r = (c >> 16) & 0xFF;
                            int g = (c >> 8) & 0xFF;
                            int b = c & 0xFF;
                            r = (r * (255 - alpha)) / 255;
                            g = (g * (255 - alpha) + 0xFF * alpha) / 255;
                            b = (b * (255 - alpha) + 0xCC * alpha) / 255;
                            c = (0xFF << 24) | (r << 16) | (g << 8) | b;
                        }
                    }
                }
                cell.put((byte) ((c >> 16) & 0xFF));
                cell.put((byte) ((c >> 8) & 0xFF));
                cell.put((byte) (c & 0xFF));
                cell.put((byte) 0xFF);
            }
        }
        cell.flip();
        BlazeCompat.uploadAtlasCell(encoder, page.texture, cell, ox, oy, CELL, CELL);
    }

    /**
     * Builds the yellow render-distance ribbon on the terrain surface. Unlike the CPU rasteriser
     * this stays a dynamic mesh: only ~500 vertices are updated per frame, no atlas rebake.
     */
    private static boolean prepareOverlay() {
        Minecraft mc = Minecraft.getInstance();
        MapData data = MapData.INSTANCE;
        var player = mc.player;
        if (player == null || !data.isReady() || !data.isRenderDistanceOverlay()) return false;
        int pb = Math.max(1, data.getPixelBlocks());
        double vscale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
        double dist = mc.options.renderDistance().get() * 16.0;
        double cx = player.getX();
        double cz = player.getZ();
        double width = Math.max(1.0, pb * 1.5);
        int maxVerts = 4 * (OVERLAY_SEGMENTS + 1) * 2;
        int maxIndices = 4 * OVERLAY_SEGMENTS * 6;
        if (overlayVbo == null) {
            overlayVbo = RenderSystem.getDevice().createBuffer(
                    () -> "voxymap_gpu3d_overlay_v",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, maxVerts * 16);
            overlayIbo = RenderSystem.getDevice().createBuffer(
                    () -> "voxymap_gpu3d_overlay_i",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, maxIndices * 4);
        }
        ByteBuffer vb = ByteBuffer.allocateDirect(maxVerts * 16).order(ByteOrder.nativeOrder());
        ByteBuffer ib = ByteBuffer.allocateDirect(maxIndices * 4).order(ByteOrder.nativeOrder());
        int vi = 0, ii = 0;
        for (int side = 0; side < 4; side++) {
            int base = vi;
            for (int i = 0; i <= OVERLAY_SEGMENTS; i++) {
                double t = i / (double) OVERLAY_SEGMENTS;
                double wx, wz, nx, nz;
                switch (side) {
                    case 0 -> { wx = cx - dist + 2 * dist * t; wz = cz - dist; nx = 0; nz = -1; }
                    case 1 -> { wx = cx + dist; wz = cz - dist + 2 * dist * t; nx = 1; nz = 0; }
                    case 2 -> { wx = cx + dist - 2 * dist * t; wz = cz + dist; nx = 0; nz = 1; }
                    default -> { wx = cx - dist; wz = cz + dist - 2 * dist * t; nx = -1; nz = 0; }
                }
                double y = data.heightAt((int) Math.floor(wx), (int) Math.floor(wz)) * vscale + 1.05;
                float ax = (float) (wx + nx * width * 0.5);
                float az = (float) (wz + nz * width * 0.5);
                float bx = (float) (wx - nx * width * 0.5);
                float bz = (float) (wz - nz * width * 0.5);
                putOverlayVertex(vb, ax, (float) y, az);
                putOverlayVertex(vb, bx, (float) y, bz);
                vi += 2;
            }
            for (int i = 0; i < OVERLAY_SEGMENTS; i++) {
                int a = base + i * 2;
                int b = a + 1;
                int c = a + 2;
                int d = a + 3;
                ib.putInt(a).putInt(c).putInt(b);
                ib.putInt(b).putInt(c).putInt(d);
                ii += 6;
            }
        }
        vb.flip();
        ib.flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(new GpuBufferSlice(overlayVbo, 0L, vb.remaining()), vb);
        encoder.writeToBuffer(new GpuBufferSlice(overlayIbo, 0L, ib.remaining()), ib);
        BlazeCompat.submit(encoder);
        overlayIndexCount = ii;
        return ii > 0;
    }

    private static void putOverlayVertex(ByteBuffer vb, float x, float y, float z) {
        vb.putFloat(x);
        vb.putFloat(y);
        vb.putFloat(z);
        vb.put((byte) 0xFF);
        vb.put((byte) 0xFF);
        vb.put((byte) 0x00);
        vb.put((byte) 0xC0);
    }

    private static void drawOverlay(RenderPass pass, GpuBufferSlice transforms) {
        if (overlayIndexCount <= 0 || overlayVbo == null) return;
        pass.setPipeline(OVERLAY_PIPELINE);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
        BlazeCompat.setVertexBuffer(pass, 0, overlayVbo);
        BlazeCompat.setIndexBuffer(pass, overlayIbo);
        BlazeCompat.drawIndexed(pass, overlayIndexCount);
    }

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        // Stonecutter rewrites this to Minecraft.screen on 26.1.2 (26.2 moved it behind Gui).
        if (!(Minecraft.getInstance().gui.screen() instanceof MapScreen screen)) return;
        if (!screen.isGpu3dView()) return;
        Map3D.Camera cam = screen.gpu3dCamera();
        if (cam == null) return;
        RenderTarget target = BlazeCompat.mainRenderTarget(mc);
        if (target.getColorTextureView() == null) return;
        if (TILES.isEmpty()) {
            // A keepalive world frame (GameRendererMixin lets one through per second while the
            // map is open) can paint the world behind the still-empty 3D view. Clear it here,
            // before the GUI phase, so the loading view never flashes the world for one frame.
            // This is a background clear, not a UI overlay: anything rendered later (labels, and
            // the terrain once the first tiles arrive) stays fully visible.
            ensureDepth(target.width, target.height);
            try (RenderPass clearPass = BlazeCompat.beginTerrainPass(
                    RenderSystem.getDevice().createCommandEncoder(), target, depthView)) {
                // clear-only pass
            }
            return;
        }
        ensureProjection();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) return;
        long rasterStartNs = System.nanoTime();
        int trisDrawn = 0;
        float fov = Math.max(30, Math.min(110, VoxyMapConfig.FOV_3D.get()));
        net.minecraft.client.renderer.Projection p = new net.minecraft.client.renderer.Projection();
        p.setupPerspective(0.05f, 60000f, fov, w, h);
        Matrix4f proj = p.getMatrix(new Matrix4f());
        Matrix4f view = cam.viewMatrix();
        Matrix4f viewProj = new Matrix4f(proj).mul(view);
        ensureDepth(target.width, target.height);
        overlayIndexCount = 0;
        boolean drawOverlay = prepareOverlay();

        RenderSystem.backupProjectionMatrix();
        try {
            RenderSystem.setProjectionMatrix(projection.getBuffer(proj),
                    com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(view, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
            try (RenderPass pass = BlazeCompat.beginTerrainPass(
                    RenderSystem.getDevice().createCommandEncoder(), target, depthView)) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                // Full-bright is intentional: the terrain re-uses the 2D map colours, which are
                // not lit. We are inside GameRenderer's UI-lightmap window (it sets
                // useUiLightmap = true right before GuiRenderer.render), so lightmap() is the
                // 1x1 white UiLightmap. Deliberately NOT levelLightmap(): that would dim the
                // terrain with time of day / darkness effects and make 3D disagree with 2D.
                pass.bindTexture("Sampler2", mc.gameRenderer.lightmap(), lightSampler);
                float half = mc.level == null ? 128f
                        : LiveTileStore.TILE_PIXELS * MapData.INSTANCE.getPixelBlocks() * 0.5f;
                int visible = 0;
                for (TileGpu tile : TILES.values()) {
                    if (!boxVisible(viewProj, tile.centerX, tile.centerZ,
                            tile.minY, tile.maxY, half)) continue;
                    visible++;
                    trisDrawn += tile.indexCount / 3;
                    pass.bindTexture("Sampler0", tile.slot.page.view, sampler);
                    BlazeCompat.setVertexBuffer(pass, 0, tile.vertices);
                    BlazeCompat.setIndexBuffer(pass, tile.indices);
                    BlazeCompat.drawIndexed(pass, tile.indexCount);
                }
                lastVisible = visible;
                lastTotal = TILES.size();
                lastDrawTiles = visible;
                lastAtlasPages = PAGES.size();
                lastAtlasBytes = atlasBytes();
                lastDepthBytes = depthBytes();
                if (VoxyMapConfig.DEBUG_ENABLED.get()) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastVramLogMs >= 2000L) {
                        lastVramLogMs = nowMs;
                        System.out.println("[voxymap] 3d vram=" + RefreshDebug.vramInfo()
                                + " tiles=" + TILES.size()
                                + " atlas=" + (lastAtlasBytes >> 20) + "MB"
                                + " mesh=" + (lastGpuBytes >> 20) + "MB");
                    }
                }
                if (drawOverlay) drawOverlay(pass, transforms);
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
        // frame counter for the perf panel / the scripted acceptance test's 3D settle detection
        MapPerf.INSTANCE.rasterFrame(System.nanoTime() - rasterStartNs, trisDrawn);
    }

    private static boolean boxVisible(Matrix4f m, double centerX, double centerZ,
                                      double minY, double maxY, float half) {
        // Six clip planes from projection * view. Both depth conventions used by these versions
        // satisfy 0 <= clipZ <= clipW (26.2 reversed Z: near=w, far=0; 26.1.2 standard Z:
        // near=0, far=w), so the same near/far halfspaces apply to both.
        float[] planes = CLIP_PLANES;
        setPlane(planes, 0, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        setPlane(planes, 4, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        setPlane(planes, 8, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        setPlane(planes, 12, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        setPlane(planes, 16, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());
        setPlane(planes, 20, m.m02(), m.m12(), m.m22(), m.m32());
        float x0 = (float) (centerX - half);
        float x1 = (float) (centerX + half);
        float y0 = (float) minY;
        float y1 = (float) maxY;
        float z0 = (float) (centerZ - half);
        float z1 = (float) (centerZ + half);
        for (int i = 0; i < 24; i += 4) {
            float a = planes[i], bb = planes[i + 1], c = planes[i + 2], d = planes[i + 3];
            float x = a >= 0 ? x1 : x0;
            float y = bb >= 0 ? y1 : y0;
            float z = c >= 0 ? z1 : z0;
            if (a * x + bb * y + c * z + d < 0) return false;
        }
        return true;
    }

    private static void setPlane(float[] planes, int o, float a, float b, float c, float d) {
        planes[o] = a;
        planes[o + 1] = b;
        planes[o + 2] = c;
        planes[o + 3] = d;
    }

    /** Visible/total tile count after frustum culling, for the Q debug panel. */
    public static String debugStats() {
        return "gpu3d: visible=" + lastVisible + "/" + lastTotal;
    }

    public static List<String> debugLines() {
        List<String> out = new ArrayList<>();
        out.add("gpu3d tiles: " + TILES.size() + "  visible=" + lastVisible
                + "  draw=" + lastDrawTiles + "  inflight=" + IN_FLIGHT.size()
                + "  empty=" + EMPTY.size() + "  atlasCap=" + maxGpuTiles());
        out.add(String.format(java.util.Locale.ROOT,
                "gpu3d build=%.2fms upload=%.2fms x%d  atlas=%d  mesh=%.1fMB atlas=%.1fMB depth=%.2fMB",
                lastBuildNanos / 1_000_000.0, lastUploadNanos / 1_000_000.0,
                lastUploadTiles, lastAtlasPages, lastGpuBytes / (1024.0 * 1024.0),
                lastAtlasBytes / (1024.0 * 1024.0), lastDepthBytes / (1024.0 * 1024.0)));
        return out;
    }

    private static void closeQuietly(TileGpu tile) {
        try {
            tile.vertices.close();
            tile.indices.close();
            gpuBytes -= tile.bytes;
        } catch (Throwable ignored) {
        }
    }

    public static void clear() {
        generation++;
        for (TileGpu tile : TILES.values()) closeQuietly(tile);
        TILES.clear();
        EMPTY.clear();
        IN_FLIGHT.clear();
        CANCELLED.clear();
        RESULTS.clear();
        TEXTURE_REFRESH.clear();
        for (RetiredBuffer r : RETIRED) {
            try {
                r.buffer.close();
            } catch (Throwable ignored) {
            }
        }
        RETIRED.clear();
        for (Page page : PAGES) {
            page.view.close();
            page.texture.close();
        }
        PAGES.clear();
        gpuBytes = 0;
        lastGpuBytes = 0;
        lastAtlasBytes = 0;
        lastDepthBytes = 0;
        lastAtlasPages = 0;
        lastUploadTiles = 0;
        lastDrawTiles = 0;
        lastVisible = 0;
        lastTotal = 0;
        lastBuildNanos = 0;
        lastUploadNanos = 0;
        if (overlayVbo != null) {
            overlayVbo.close();
            overlayVbo = null;
        }
        if (overlayIbo != null) {
            overlayIbo.close();
            overlayIbo = null;
        }
        overlayIndexCount = 0;
        if (projection != null) {
            projection.close();
            projection = null;
        }
        if (depthView != null) {
            depthView.close();
            depthView = null;
        }
        if (depthTexture != null) {
            depthTexture.close();
            depthTexture = null;
        }
        depthWidth = depthHeight = 0;
    }
}
