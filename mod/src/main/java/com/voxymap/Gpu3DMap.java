package com.voxymap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.voxymap.compat.gpu.CommandEncoder;
import com.voxymap.compat.gpu.GpuBuffer;
import com.voxymap.compat.gpu.GpuBufferSlice;
import com.voxymap.compat.gpu.GpuSampler;
import com.voxymap.compat.gpu.GpuTexture;
import com.voxymap.compat.gpu.GpuTextureView;
import com.voxymap.compat.gpu.RenderPass;
import com.voxymap.compat.gpu.RenderPipeline;
import com.voxymap.compat.gpu.RenderPipelines;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

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
 *
 * <p><b>1.21.1 port notes.</b> 1.21.1 has no Blaze3D {@code GpuDevice}/{@code GpuBuffer}/
 * {@code GpuTexture}/{@code RenderPass}/{@code RenderPipeline} layer, so this class drives the
 * equivalent GL objects through {@code com.voxymap.compat.gpu}: one VBO+IBO+VAO per tile, one
 * RGBA8 atlas texture per page, and two custom core shaders ({@code voxymap:terrain},
 * {@code voxymap:overlay}). Everything else (worker meshing, stamp invalidation, LOD steps, atlas
 * slot management, eviction, debug stats) is unchanged from the 26.2 implementation.</p>
 *
 * <p>Differences that are forced by the older API, all documented at their use sites:</p>
 * <ul>
 *   <li>Depth uses the main render target's depth attachment (standard Z, clear 1.0, LEQUAL).
 *       26.2 created a private reverse-Z D32 attachment; that is unnecessary here.</li>
 *   <li>Tile indices are int GL indices in a self-managed VAO (1.21.1's {@code VertexBuffer} cannot
 *       take an arbitrary index count).</li>
 *   <li>The terrain is back-face culled exactly like 26.2's {@code SOLID_BLOCK} (winding verified
 *       offscreen and in game; the tile skirts carry both windings so seams stay closed). See
 *       {@link com.voxymap.compat.gpu.RenderPipelines#TERRAIN}.</li>
 *   <li>No lightmap sampler: the shader is full-bright by construction, matching the "deliberately
 *       full-bright" rule of the 26.2 pass.</li>
 * </ul>
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
    private static final RenderPipeline PIPELINE = RenderPipelines.TERRAIN;
    private static final RenderPipeline OVERLAY_PIPELINE = RenderPipelines.OVERLAY;
    private static final int OVERLAY_SEGMENTS = 64;
    private static GpuBuffer overlayVbo;
    private static GpuBuffer overlayIbo;
    private static int overlayIndexCount;
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
    /**
     * Size of the main target's depth attachment, which this pass now shares (26.2 kept a private
     * D32 texture). Only used for the Q debug panel line.
     */
    private static int depthWidth, depthHeight;
    private static long lastLog;
    /** Last frame a GL error was reported (debug only), so the diagnostic cannot spam. */
    private static long lastGlErrorFrame;

    private static volatile int lastVisible, lastTotal;
    private static volatile int generation;
    private static volatile long lastBuildNanos, lastUploadNanos;
    private static volatile int lastUploadTiles, lastDrawTiles, lastAtlasPages;
    private static volatile long lastGpuBytes, lastAtlasBytes, lastDepthBytes;
    private static long gpuBytes;

    private Gpu3DMap() {
    }

    private static final class Page {
        final GpuTexture texture;
        final GpuTextureView view;
        final boolean[] used = new boolean[SLOTS];

        Page() {
            texture = GpuTexture.createColor("voxymap_gpu3d_atlas",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, ATLAS, ATLAS);
            view = texture.createView();
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
        // 1.21.1 has no sampler objects; the atlas textures carry NEAREST + clamp-to-edge.
        sampler = GpuSampler.CLAMP_NEAREST;
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

    /**
     * VRAM of the depth attachment this pass renders against. On 1.21.1 that is the main render
     * target's own D32 texture instead of a private attachment, so the number is reported for
     * parity with the 26.2 debug panel rather than as extra VRAM owned by this class.
     */
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
        // A failed/cancelled worker build releases its (never drawn) slot from the build thread;
        // bounce that onto the render thread so PAGES/used[] are only ever touched by one thread and
        // the GL texture deletion in GpuTexture.close() happens with a live context.
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> releaseSlot(slot));
            return;
        }
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

    public static void tick(MapData data, Map3D.Camera camera) {
        // currentLiveTiles() is a volatile read; getLiveTiles() would run findStorage() (directory
        // IO) on the render thread every frame. Only fall back when no store exists yet.
        LiveTileStore found = data.currentLiveTiles();
        if (found == null) found = data.getLiveTiles();
        if (found == null || camera == null) return;
        // Dedupe: the pre-GUI GameRenderer hook ticks first; MapScreen's own tick call is a no-op
        // for the rest of that frame (and vice versa if the hook is unavailable).
        if (tickedThisFrame) return;
        tickedThisFrame = true;
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
        var encoder = CommandEncoder.create();
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
            GpuBuffer vbuf = uploadVertices(encoder, "voxymap_gpu3d_v", b.vertices);
            GpuBuffer ibuf = uploadIndices(encoder, "voxymap_gpu3d_i", b.indices);
            TileGpu old = TILES.put(b.key, new TileGpu(vbuf, ibuf, b.indexCount,
                    b.step, b.stamp, b.centerX, b.centerZ, b.minY, b.maxY, b.slot, bytes));
            EMPTY.remove(b.key);
            gpuBytes += bytes;
            if (old != null) retireTile(old);
            uploaded++;
            buildNanos += b.buildNanos;
        }
        encoder.submit();
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
        var encoder = CommandEncoder.create();
        int budget = 32;
        while (budget-- > 0 && !TEXTURE_REFRESH.isEmpty()) {
            long key2 = TEXTURE_REFRESH.removeFirst();
            TileGpu tile = TILES.get(key2);
            if (tile == null) continue;
            LiveTileStore.LiveTile current = live.get((int) (key2 >> 32), (int) key2);
            if (current == null || current.rgb == null) continue;
            uploadTileTexture(encoder, tile.slot, current.rgb, current, live);
        }
        encoder.submit();
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
        // 1.21.1 has no POSITION_COLOR_TEX; POSITION_TEX_COLOR is the same data in a different
        // element order (Position, UV0, Color). UV2/Normal are gone: the shader is full-bright.
        VertexFormat format = DefaultVertexFormat.POSITION_TEX_COLOR;
        ByteBufferBuilder alloc = new ByteBufferBuilder(Math.max(1024, vi * format.getVertexSize()));
        BufferBuilder bb = new BufferBuilder(alloc, VertexFormat.Mode.TRIANGLES, format);
        for (int i = 0; i < vi; i++) {
            bb.addVertex(px[i], py[i], pz[i])
                    .setColor(255, 255, 255, 255)
                    .setUv(pu[i], pv[i]);
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

    private static GpuBuffer uploadVertices(CommandEncoder encoder, String label, ByteBuffer data) {
        int size = data.remaining();
        GpuBuffer buffer = GpuBuffer.createVertex(label, size, DefaultVertexFormat.POSITION_TEX_COLOR);
        encoder.writeToBuffer(new GpuBufferSlice(buffer, 0L, size), data);
        return buffer;
    }

    private static GpuBuffer uploadIndices(CommandEncoder encoder, String label, ByteBuffer data) {
        int size = data.remaining();
        GpuBuffer buffer = GpuBuffer.createIndex(label, size);
        encoder.writeToBuffer(new GpuBufferSlice(buffer, 0L, size), data);
        return buffer;
    }

    private static void uploadTileTexture(CommandEncoder encoder,
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
        // 1.21.1: the atlas is a mutable RGBA8 texture, so this is a glTexSubImage2D of the slot.
        encoder.writeToTexture(page.texture, cell, ox, oy, CELL, CELL);
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
            overlayVbo = GpuBuffer.createVertex("voxymap_gpu3d_overlay_v", maxVerts * 16,
                    DefaultVertexFormat.POSITION_COLOR);
            overlayIbo = GpuBuffer.createIndex("voxymap_gpu3d_overlay_i", maxIndices * 4);
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
        var encoder = CommandEncoder.create();
        encoder.writeToBuffer(new GpuBufferSlice(overlayVbo, 0L, vb.remaining()), vb);
        encoder.writeToBuffer(new GpuBufferSlice(overlayIbo, 0L, ib.remaining()), ib);
        encoder.submit();
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

    private static void drawOverlay(RenderPass pass) {
        if (overlayIndexCount <= 0 || overlayVbo == null) return;
        pass.setPipeline(OVERLAY_PIPELINE);
        pass.setVertexBuffer(0, overlayVbo);
        pass.setIndexBuffer(overlayIbo);
        pass.drawIndexed(overlayIndexCount);
    }

    /**
     * True when the current frame's 3D pass has already run, so the frame's two possible call sites
     * (the {@code GameRenderer} hook that runs before the GUI batch starts, and the legacy
     * {@code MapScreen.render()} call) draw the pass exactly once.
     */
    private static boolean renderedThisFrame;

    /**
     * True when this frame's {@link #tick(MapData, Map3D.Camera)} has already run. Driving the tile
     * builds / GPU uploads from the same pre-GUI hook keeps <em>all</em> of this mod's GL calls out
     * of vanilla's GUI immediate-mode batching window (the uploads bind buffers and textures, which
     * is exactly the state third-party GUI batching mods cache).
     */
    private static boolean tickedThisFrame;

    /**
     * Called once per frame by the {@code GameRenderer} hook before the 3D pass. The 3D view is
     * rendered before the GUI batch is built so that third-party GUI batching mods never see their
     * immediate-draw caches invalidated mid-batch by this pass.
     */
    public static void beginFrame() {
        renderedThisFrame = false;
        tickedThisFrame = false;
    }

    /** Called at the end of the engine frame so a missed hook can never wedge the dedupe flags. */
    public static void endFrame() {
        renderedThisFrame = false;
        tickedThisFrame = false;
    }

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        // 26.2 moved the current screen behind Gui; 1.21.1 keeps it in Minecraft.screen.
        if (!RenderSystem.isOnRenderThread()) return;
        if (renderedThisFrame) return;
        if (!(mc.screen instanceof MapScreen screen)) return;
        if (!screen.isGpu3dView()) return;
        Map3D.Camera cam = screen.gpu3dCamera();
        if (cam == null) return;
        renderedThisFrame = true;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) return;
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) return;
        if (TILES.isEmpty()) {
            // A keepalive world frame (GameRendererMixin lets one through per second while the map
            // is open) can paint the world behind the still-empty 3D view; clear it here, before
            // the GUI phase, so the loading view never flashes the world for one frame. This is a
            // background clear, not a UI overlay: labels and the terrain (once tiles exist) are
            // drawn later and stay fully visible.
            depthWidth = target.width;
            depthHeight = target.height;
            float[] savedClearColor = new float[4];
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClearColor);
            float[] savedClearDepth = new float[1];
            GL11.glGetFloatv(GL11.GL_DEPTH_CLEAR_VALUE, savedClearDepth);
            RenderSystem.disableScissor();
            target.bindWrite(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.clearColor(0.02f, 0.02f, 0.03f, 1f);
            RenderSystem.clearDepth(1.0);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.clearDepth(savedClearDepth[0]);
            RenderSystem.clearColor(savedClearColor[0], savedClearColor[1],
                    savedClearColor[2], savedClearColor[3]);
            return;
        }
        ensureAtlas();
        float fov = Math.max(30, Math.min(110, VoxyMapConfig.FOV_3D.get()));
        // 1.21.1 uses standard OpenGL depth: 0 at the near plane, 1 at the far plane, cleared to
        // 1.0 and compared with LEQUAL. (26.2 used reversed Z and a private D32_FLOAT attachment;
        // the shared main-target depth buffer is enough for a single terrain pass.)
        //
        // TODO(device): z-fighting at long range. With standard Z, a 0.05 near plane and a 60000
        // far plane, the 24-bit fixed-point depth buffer the main target actually gets (probed
        // offscreen on the target driver: unsized GL_DEPTH_COMPONENT + GL_FLOAT resolves to
        // GL_UNSIGNED_NORMALIZED/24 bits) stops separating surfaces that are ~30 blocks apart in
        // depth at 5k distance and ~500 blocks at 20k. Reversed Z would NOT help on that target
        // (it only pays off with a float depth buffer), so the cheap fix is to raise the near plane
        // to ~2-4 (the 3D camera keeps a 64..32768 block distance). A private D32F target + 26.2's
        // reversed-Z convention (near=1, far=0, GREATER, clear 0) is the bigger alternative.
        // 1.21.1 shares the main target's fixed-point 24-bit depth buffer (verified on the local
        // driver), so the near plane has to carry the whole range: 0.05 would leave ~30 blocks of
        // depth quantisation at 5k blocks and look like z-fighting. The 3D camera never gets closer
        // than minDistance3d (>=64 blocks), so a 4-block near plane clips nothing.
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), w / (float) h,
                4.0f, 60000f);
        Matrix4f view = cam.viewMatrix();
        Matrix4f viewProj = new Matrix4f(proj).mul(view);
        depthWidth = target.width;
        depthHeight = target.height;
        overlayIndexCount = 0;
        boolean drawOverlay = prepareOverlay();

        // The pass now runs from the GameRenderer hook (right after the engine's world-frame
        // clear), so the engine's own clear colour / clear depth / viewport must be restored
        // afterwards - the GUI phase and the next frame's clears still use them. Read them straight
        // from GL: RenderSystem exposes setters but no getters for these.
        float[] savedClearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClearColor);
        float[] savedClearDepth = new float[1];
        GL11.glGetFloatv(GL11.GL_DEPTH_CLEAR_VALUE, savedClearDepth);
        int[] savedViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        // The screen may have left an active scissor and a disabled depth write behind (GUI render
        // types toggle both), and a masked clear silently keeps the old depth, so normalise first.
        RenderSystem.disableScissor();
        target.bindWrite(true);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.clearColor(0.02f, 0.02f, 0.03f, 1f);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        float[] oldShaderColor = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        try (RenderPass pass = new RenderPass(PIPELINE, view, proj)) {
            // Full-bright is intentional: the terrain re-uses the 2D map colours, which are not
            // lit, so the shader does not sample a lightmap at all (26.2 bound the 1x1 UI lightmap
            // and deliberately not the level lightmap for exactly the same reason).
            float half = mc.level == null ? 128f
                    : LiveTileStore.TILE_PIXELS * MapData.INSTANCE.getPixelBlocks() * 0.5f;
            int visible = 0;
            for (TileGpu tile : TILES.values()) {
                if (!boxVisible(viewProj, tile.centerX, tile.centerZ,
                        tile.minY, tile.maxY, half)) continue;
                visible++;
                pass.bindTexture("Sampler0", tile.slot.page.view, sampler);
                pass.setVertexBuffer(0, tile.vertices);
                pass.setIndexBuffer(tile.indices);
                pass.drawIndexed(tile.indexCount);
            }
            lastVisible = visible;
            lastTotal = TILES.size();
            lastDrawTiles = visible;
            lastAtlasPages = PAGES.size();
            lastAtlasBytes = atlasBytes();
            lastDepthBytes = depthBytes();
            if (drawOverlay) drawOverlay(pass);
        } finally {
            // Diagnostic for the on-device check: a GL error here usually means a state/attrib
            // mistake in the compat layer. Rate-limited so a permanent failure cannot spam.
            if (VoxyMapConfig.DEBUG_ENABLED.get()) {
                int err = GlStateManager._getError();
                if (err != 0 && frameCounter != lastGlErrorFrame) {
                    lastGlErrorFrame = frameCounter;
                    System.out.println("[voxymap] gpu3d GL error 0x" + Integer.toHexString(err)
                            + " tiles=" + TILES.size() + " visible=" + lastVisible);
                }
            }
            // Put the engine/GUI state back. The GUI batch of this frame (and any third-party mod
            // that batches GUI draws) is built and flushed right after this hook, so every piece of
            // state this pass touches has to look untouched:
            //   - a clean depth buffer (RenderType.GUI depth-tests LEQUAL and terrain can be nearer
            //     than the GUI's own depth if the camera is clipped into a hill),
            //   - the engine's clear colour/depth and viewport (restored from GL, not assumed),
            //   - no shader program, no custom VAO, immediate-draw caches invalidated so vanilla
            //     re-binds its own VAO before its next draw,
            //   - active texture unit 0 and the shader texture slot reset.
            RenderSystem.setShaderColor(oldShaderColor[0], oldShaderColor[1],
                    oldShaderColor[2], oldShaderColor[3]);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.clearDepth(1.0);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.clearDepth(savedClearDepth[0]);
            RenderSystem.clearColor(savedClearColor[0], savedClearColor[1],
                    savedClearColor[2], savedClearColor[3]);
            RenderSystem.viewport(savedViewport[0], savedViewport[1],
                    savedViewport[2], savedViewport[3]);
            GlStateManager._glBindVertexArray(0);
            // Re-sync vanilla's immediate-draw VAO cache (see RenderPass.close()).
            BufferUploader.invalidate();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.setShaderTexture(0, 0);
        }
    }

    private static boolean boxVisible(Matrix4f m, double centerX, double centerZ,
                                      double minY, double maxY, float half) {
        // Six clip planes from projection * view. 1.21.1 is standard Z (0 <= z/w <= 1, so the
        // clip halfspaces are -w <= z <= w): near = row3 + row2, far = row3 - row2. (26.2's
        // reversed Z had near = row2 and far = row3 - row2, which is NOT usable here.)
        float[] planes = CLIP_PLANES;
        setPlane(planes, 0, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        setPlane(planes, 4, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        setPlane(planes, 8, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        setPlane(planes, 12, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        setPlane(planes, 16, m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22(), m.m33() + m.m32());
        setPlane(planes, 20, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());
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
        depthWidth = depthHeight = 0;
    }
}

