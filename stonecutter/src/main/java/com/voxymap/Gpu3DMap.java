package com.voxymap;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
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
    /**
     * The marker tick line is drawn with depth testing disabled: it is a UI marker for the player,
     * not terrain, so it must always be visible instead of disappearing behind the mesh it casts
     * from. The "buried part is grey" rule is decided from the map data, not from the depth buffer.
     */
    private static final RenderPipeline MARKER_PIPELINE = markerPipeline();
    /** Textured world-space pipeline for the player arrow (POSITION_TEX, no depth test). */
    private static final RenderPipeline ARROW_PIPELINE = arrowPipeline();
    /** Procedural screen-space sky (NDC gradient bands) and blocks-style horizontal fog. */
    private static RenderPipeline skyPipelineSafe() {
        try {
            return BlazeCompat.advancedSkyPipeline();
        } catch (Throwable t) {
            System.out.println("[voxymap] sky pipeline unavailable: " + t);
            return null;
        }
    }
    private static final RenderPipeline SKY_PIPELINE = skyPipelineSafe();
    private static RenderPipeline terrain3dPipeline;
    private static GpuBuffer skyVbo;
    private static ByteBuffer skyStaging;
    private static int skyVertexCount;
    private static final int SKY_MAX_VERTS = 12;
    private static MappableRingBuffer fogRing;
    private static GpuBufferSlice fogSlice;
    private static GpuBufferSlice fogPrevious;
    private static ByteBuffer fogStaging;
    private static final int SKY_BLACK = 0xFF050508;
    /** Fixed clear-noon overworld colours: no weather, daylight or biome variation. */
    private static final int CLEAR_SKY_COLOR = 0xFF79A7FF;
    private static final int CLEAR_FOG_COLOR = 0xFFC0D8FF;
    private static final int OVERLAY_SEGMENTS = 64;
    /** Upper bound on the marker tick-line segments (the buffer is sized for this). */
    private static final int MARKER_MAX_SEGMENTS = 96;
    /**
     * The distance ribbon + marker line + arrow are <b>rewritten every frame</b>, so they live in a
     * 3-slot ring: overwriting the buffer a frame is still reading is a WAR hazard (undefined on
     * Vulkan, a driver stall on GL). Before a slot is reused its fence is awaited, exactly like
     * vanilla's {@code MappableRingBuffer}. 26.1.2 has no fence API at all, so there the ring alone
     * provides the slack (three frames instead of one).
     */
    private static final int OVERLAY_SLOTS = 3;
    private static final GpuBuffer[] overlayVbos = new GpuBuffer[OVERLAY_SLOTS];
    private static final GpuBuffer[] overlayIbos = new GpuBuffer[OVERLAY_SLOTS];
    /** {@code GpuFence} on 26.2, null on 26.1.2 (kept as Object so the field is version neutral). */
    private static final Object[] overlayFences = new Object[OVERLAY_SLOTS];
    private static int overlaySlot;
    /** Slide index the current frame wrote into; the fence for it is created after the pass. */
    private static int overlaySlotInUse = -1;
    /** Ribbon indices (drawn with {@link #OVERLAY_PIPELINE}, depth-tested). */
    private static int overlayIndexCount;
    /** Marker-line index range inside the same buffer (drawn with {@link #MARKER_PIPELINE}). */
    private static int markerFirstIndex;
    private static int markerIndexCount;
    /** Player arrow: its own POSITION_TEX buffers (a quad + two triangles), redrawn every frame. */
    private static final int ARROW_VERTS = 4;
    private static final int ARROW_INDICES = 6;
    private static final GpuBuffer[] arrowVbos = new GpuBuffer[OVERLAY_SLOTS];
    private static final GpuBuffer[] arrowIbos = new GpuBuffer[OVERLAY_SLOTS];
    private static int arrowIndexCount;
    private static GpuSampler arrowSampler;
    /**
     * Player track overlay: its own buffers, rebuilt only when {@link TrackOverlay#version()} moves
     * (one sampling step, i.e. every couple of blocks) instead of being re-uploaded every frame.
     * A rebuild always allocates fresh buffers and retires the old pair through {@link #RETIRED},
     * so a frame that is still in flight never reads a buffer that was overwritten underneath it.
     */
    private static GpuBuffer trackVbo;
    private static GpuBuffer trackIbo;
    private static int trackIndexCount;
    private static int trackBuiltVersion = -1;
    /**
     * Rebuild throttle: TrackOverlay's version advances every sample (2 blocks / 4 s), and rebuilding
     * the whole 20k-point ribbon that often is pure waste. Rebuild after 8 new samples or 1 s,
     * whichever comes first; a clear (count drop) or a toggle always rebuilds immediately.
     */
    private static int trackBuiltCount;
    private static long trackBuiltMs;
    private static final int TRACK_REBUILD_POINTS = 8;
    private static final long TRACK_REBUILD_MS = 1000L;
    /** Reused staging memory; sized once for MAX_POINTS and never allocated per rebuild. */
    private static ByteBuffer trackStagingVb;
    private static ByteBuffer trackStagingIb;
    /** Half the ribbon width in world blocks; the track is a thin path, not a road. */
    private static final double TRACK_HALF_WIDTH = 1.1;
    /**
     * Small lift above the recorded feet position so the ribbon does not z-fight with the terrain
     * on the frame the player is standing on. It must stay small: the ribbon tracks the player's
     * own height, not the surface.
     */
    private static final double TRACK_LIFT = 0.15;
    /**
     * The distance ribbon re-uses vanilla's debug-quads pipeline: position+color vertices, QUADS
     * topology (mapped to GL_TRIANGLES by the backend), translucent blending, depth testing without
     * depth writes and culling disabled - exactly what the ribbon needs, and per-version correct
     * on both 26.2 (reversed Z) and 26.1.2 (standard Z).
     */
    private static RenderPipeline overlayPipeline() {
        return RenderPipelines.DEBUG_QUADS;
    }

    /**
     * Same vertex format / blending / topology as {@link #OVERLAY_PIPELINE}, but with the depth test
     * forced to ALWAYS_PASS so the marker line draws over the terrain.
     */

    private static RenderPipeline markerPipeline() {
        try {
            return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation("pipeline/voxymap_marker_line")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    // Explicit: 26.2's DEBUG_FILLED_SNIPPET already sets cull(false), but 26.1.2's
                    // snippet leaves culling on (vanilla re-disables it in DEBUG_QUADS for exactly
                    // this reason). Without it the camera-facing marker ribbon is back-face culled
                    // and the tick line silently disappears on 26.1.2.
                    .withCull(false)
                    .build());
        } catch (Throwable t) {
            // never let a pipeline registration problem take the whole 3D view down: the marker
            // line then falls back to the depth-tested overlay pipeline
            System.out.println("[voxymap] marker pipeline unavailable, using the overlay pipeline: " + t);
            return null;
        }
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
        arrowSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
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
        double cy = MapData.INSTANCE.surfaceYAt((int) cx, (int) cz) * vscale;
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
        stamp = stamp * 31L + (VoxyMapConfig.GPU_TERRAIN_VOXEL.get() ? 1L : 0L);
        stamp = stamp * 31L + (VoxyMapConfig.ADVANCED_3D_RENDER.get() ? 1L : 0L);
        stamp = stamp * 31L + VoxyMapConfig.ADVANCED_3D_AO.get();
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
        return (MapData.INSTANCE.isBiomeOverlay() ? 1L : 0L)
                | (VoxyMapConfig.ADVANCED_3D_RENDER.get() ? 2L : 0L);
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
    /** Experimental voxel mesher: one flat top per cell + vertical side walls where neighbours are lower. */
    private static BuildResult buildVoxel(int gen, long key, long stamp, Slot slot, int step,
                                          LiveTileStore.LiveTile tile, long[] pixels,
                                          LiveTileStore live, int pb, int sea, double vscale,
                                          long buildStart) {
        final int grid = LiveTileStore.TILE_PIXELS / step;
        final int cells = grid * grid;
        final int texMax = LiveTileStore.TILE_PIXELS - 1;
        final int sx = (slot.index % COLS) * CELL;
        final int sy = (slot.index / COLS) * CELL;
        final double seaY = sea * vscale;
        final double skirt = VoxyMapConfig.GPU_TERRAIN_SKIRT_DEPTH.get();
        VoxelScratch s = VoxelScratch.get(cells);
        float[] h = s.h;
        boolean[] empty = s.emptyCell;
        boolean[] water = s.waterCell;
        boolean[] visited = s.visited;
        int[] aoCell = s.aoCell;
        for (int i = 0; i < cells; i++) {
            h[i] = 0f;
            empty[i] = true;
            water[i] = false;
            visited[i] = false;
        }
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int gz = 0; gz < grid; gz++) {
            int z = gz * step;
            for (int gx = 0; gx < grid; gx++) {
                int x = gx * step;
                long packed = sampleBoundary(tile, pixels, x, z,
                        Math.min(texMax, x), Math.min(texMax, z), live);
                int i = gz * grid + gx;
                int top = packed == VoxyMerge.LONG_EMPTY ? VoxyMerge.NO_TOP
                        : (int) ((packed >>> 24) & 0xFFFF) - 32768;
                int floor = packed == VoxyMerge.LONG_EMPTY ? VoxyMerge.NO_SOLID
                        : (int) (packed & 0xFFFF) - 32768;
                boolean e = packed == VoxyMerge.LONG_EMPTY
                        || (top == VoxyMerge.NO_TOP && floor == VoxyMerge.NO_SOLID);
                boolean w = !e && (packed & (1L << 52)) != 0;
                float y;
                if (e) {
                    y = 0f;
                } else if (top == VoxyMerge.NO_TOP) {
                    // top-face convention, same as the smooth mesher (land is one block above the
                    // stored block Y; water already uses its surface level)
                    y = (float) ((floor + 1) * vscale);
                } else {
                    y = (float) ((top + 1) * vscale);
                }
                empty[i] = e;
                water[i] = w;
                h[i] = y;
                aoCell[i] = 255;
                if (!e) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        // ---- per-corner AO (Minecraft-style smooth lighting) ----
        // Corner brightness is derived from the columns around the shared grid-vertex position, so
        // the two cells that meet at a vertex compute the same value and the interpolation across
        // each face is continuous. Water stays at full brightness for now.
        int aoStrength = VoxyMapConfig.ADVANCED_3D_AO.get();
        boolean advancedLook = VoxyMapConfig.ADVANCED_3D_RENDER.get();
        boolean useAo = advancedLook && aoStrength > 0;
        byte[] vao = s.vao;
        if (vao.length < cells * 4) {
            vao = new byte[cells * 4];
            s.vao = vao;
        }
        if (!useAo) {
            // No AO requested: skip the border height ring and the per-cell corner pass entirely.
            Arrays.fill(vao, 0, cells * 4, (byte) 0xFF);
            Arrays.fill(aoCell, 0, cells, 255);
        } else {
            int gw2 = grid + 2;
            float[] hb = s.hb;
            if (hb.length < gw2 * gw2) {
                hb = new float[gw2 * gw2];
                s.hb = hb;
            }
            for (int bgz = 0; bgz < gw2; bgz++) {
                int bz = (bgz - 1) * step;
                for (int bgx = 0; bgx < gw2; bgx++) {
                    int bx = (bgx - 1) * step;
                    float hv;
                    if (bgx >= 1 && bgx <= grid && bgz >= 1 && bgz <= grid) {
                        hv = h[(bgz - 1) * grid + (bgx - 1)];
                    } else {
                        hv = voxNeighborY(tile, pixels, live, bx, bz, sea, vscale, texMax);
                    }
                    hb[bgz * gw2 + bgx] = Float.isNaN(hv) ? Float.NEGATIVE_INFINITY : hv;
                }
            }
            for (int gz = 0; gz < grid; gz++) {
                for (int gx = 0; gx < grid; gx++) {
                    int i = gz * grid + gx;
                    int base = i * 4;
                    if (empty[i] || water[i]) {
                        vao[base] = (byte) 0xFF;
                        vao[base + 1] = (byte) 0xFF;
                        vao[base + 2] = (byte) 0xFF;
                        vao[base + 3] = (byte) 0xFF;
                        aoCell[i] = 255;
                        continue;
                    }
                    float cy = h[i];
                    int c0 = cornerAo(hb, gw2, gx, gz, 0, cy, aoStrength);
                    int c1 = cornerAo(hb, gw2, gx, gz, 1, cy, aoStrength);
                    int c2 = cornerAo(hb, gw2, gx, gz, 2, cy, aoStrength);
                    int c3 = cornerAo(hb, gw2, gx, gz, 3, cy, aoStrength);
                    vao[base] = (byte) c0;
                    vao[base + 1] = (byte) c1;
                    vao[base + 2] = (byte) c2;
                    vao[base + 3] = (byte) c3;
                    aoCell[i] = (c0 + c1 + c2 + c3) / 4;
                }
            }
        }        // ---- flat tops, greedily merged over equal-height cells ----
        for (int gz = 0; gz < grid; gz++) {
            for (int gx = 0; gx < grid; gx++) {
                int i0 = gz * grid + gx;
                if (empty[i0] || visited[i0]) continue;
                float y0 = h[i0];
                int aoBase = i0 * 4;
                int w = 1;
                while (gx + w < grid) {
                    int i = i0 + w;
                    if (empty[i] || visited[i] || h[i] != y0 || !sameAo(vao, i, i0)) break;
                    w++;
                }
                int d = 1;
                outer:
                while (gz + d < grid) {
                    int row = (gz + d) * grid + gx;
                    for (int k = 0; k < w; k++) {
                        int i = row + k;
                        if (empty[i] || visited[i] || h[i] != y0 || !sameAo(vao, i, i0)) break outer;
                    }
                    d++;
                }
                for (int zz = 0; zz < d; zz++) {
                    int row = (gz + zz) * grid + gx;
                    for (int xx = 0; xx < w; xx++) visited[row + xx] = true;
                }
                double wx0 = tile.minWX + (long) (gx * step) * pb;
                double wx1 = tile.minWX + (long) ((gx + w) * step) * pb;
                double wz0 = tile.minWZ + (long) (gz * step) * pb;
                double wz1 = tile.minWZ + (long) ((gz + d) * step) * pb;
                double u0 = (sx + (double) Math.min(texMax + 1, gx * step)) / ATLAS;
                double u1 = (sx + (double) Math.min(texMax + 1, (gx + w) * step)) / ATLAS;
                double v0 = (sy + (double) Math.min(texMax + 1, gz * step)) / ATLAS;
                double v1 = (sy + (double) Math.min(texMax + 1, (gz + d) * step)) / ATLAS;
                int b = s.vi;
                s.curAo = vao[aoBase] & 0xFF;
                voxAdd(s, wx0, y0, wz0, u0, v0);
                s.curAo = vao[aoBase + 2] & 0xFF;
                voxAdd(s, wx0, y0, wz1, u0, v1);
                s.curAo = vao[aoBase + 1] & 0xFF;
                voxAdd(s, wx1, y0, wz0, u1, v0);
                s.curAo = vao[aoBase + 3] & 0xFF;
                voxAdd(s, wx1, y0, wz1, u1, v1);
                voxTri(s, b, b + 1, b + 2);
                voxTri(s, b + 2, b + 1, b + 3);
            }
        }
        // ---- vertical side walls (every column, water included: its stored surface can be above or below sea level) ----
        for (int gx = 0; gx < grid; gx++) {
            int x = gx * step;
            // east faces (constant X, merged along Z); the west face of the neighbour tile is skipped,
            // so drawing the boundary here does not duplicate walls between tiles.
            int gz = 0;
            while (gz < grid) {
                int i = gz * grid + gx;
                if (empty[i]) { gz++; continue; }
                float y = h[i];
                float ny = voxNeighborY(tile, pixels, live, x + step, gz * step, sea, vscale, texMax);
                if (Float.isNaN(ny) || ny >= y) { gz++; continue; }
                int run = 1;
                while (gz + run < grid) {
                    int j = (gz + run) * grid + gx;
                    if (empty[j] || h[j] != y) break;
                    float n2 = voxNeighborY(tile, pixels, live, x + step, (gz + run) * step, sea, vscale, texMax);
                    if (!(Float.isNaN(n2) || n2 != ny) && n2 < y) { run++; } else { break; }
                }
                double z0 = tile.minWZ + (long) (gz * step) * pb;
                double z1 = tile.minWZ + (long) ((gz + run) * step) * pb;
                double xEdge = tile.minWX + (long) ((gx + 1) * step) * pb;
                double u = (sx + Math.min(texMax, x) + 0.5) / ATLAS;
                double v0 = (sy + (double) Math.min(texMax + 1, gz * step)) / ATLAS;
                double v1 = (sy + (double) Math.min(texMax + 1, (gz + run) * step)) / ATLAS;
                s.curAo = advancedLook ? (int) (aoCell[i] * 0.68f) : 255;
                voxWallX(s, xEdge, ny, y, z0, z1, u, v0, v1, true);
                minY = Math.min(minY, ny);
                gz += run;
            }
            // west faces
            gz = 0;
            while (gz < grid) {
                int i = gz * grid + gx;
                if (empty[i]) { gz++; continue; }
                float y = h[i];
                float ny = voxNeighborY(tile, pixels, live, x - step, gz * step, sea, vscale, texMax);
                if (Float.isNaN(ny) || ny >= y) { gz++; continue; }
                int run = 1;
                while (gz + run < grid) {
                    int j = (gz + run) * grid + gx;
                    if (empty[j] || h[j] != y) break;
                    float n2 = voxNeighborY(tile, pixels, live, x - step, (gz + run) * step, sea, vscale, texMax);
                    if (!(Float.isNaN(n2) || n2 != ny) && n2 < y) { run++; } else { break; }
                }
                double z0 = tile.minWZ + (long) (gz * step) * pb;
                double z1 = tile.minWZ + (long) ((gz + run) * step) * pb;
                double xEdge = tile.minWX + (long) (gx * step) * pb;
                double u = (sx + Math.min(texMax, x) + 0.5) / ATLAS;
                double v0 = (sy + (double) Math.min(texMax + 1, gz * step)) / ATLAS;
                double v1 = (sy + (double) Math.min(texMax + 1, (gz + run) * step)) / ATLAS;
                s.curAo = advancedLook ? (int) (aoCell[i] * 0.68f) : 255;
                voxWallX(s, xEdge, ny, y, z0, z1, u, v0, v1, true);
                minY = Math.min(minY, ny);
                gz += run;
            }
        }
        for (int gz = 0; gz < grid; gz++) {
            int z = gz * step;
            // south faces
            int gx = 0;
            while (gx < grid) {
                int i = gz * grid + gx;
                if (empty[i]) { gx++; continue; }
                float y = h[i];
                float ny = voxNeighborY(tile, pixels, live, gx * step, z + step, sea, vscale, texMax);
                if (Float.isNaN(ny) || ny >= y) { gx++; continue; }
                int run = 1;
                while (gx + run < grid) {
                    int j = gz * grid + gx + run;
                    if (empty[j] || h[j] != y) break;
                    float n2 = voxNeighborY(tile, pixels, live, (gx + run) * step, z + step, sea, vscale, texMax);
                    if (!(Float.isNaN(n2) || n2 != ny) && n2 < y) { run++; } else { break; }
                }
                double x0 = tile.minWX + (long) (gx * step) * pb;
                double x1 = tile.minWX + (long) ((gx + run) * step) * pb;
                double zEdge = tile.minWZ + (long) ((gz + 1) * step) * pb;
                double v = (sy + Math.min(texMax, z) + 0.5) / ATLAS;
                double u0 = (sx + (double) Math.min(texMax + 1, gx * step)) / ATLAS;
                double u1 = (sx + (double) Math.min(texMax + 1, (gx + run) * step)) / ATLAS;
                s.curAo = advancedLook ? (int) (aoCell[i] * 0.85f) : 255;
                voxWallZ(s, zEdge, ny, y, x0, x1, v, u0, u1, true);
                minY = Math.min(minY, ny);
                gx += run;
            }
            // north faces
            gx = 0;
            while (gx < grid) {
                int i = gz * grid + gx;
                if (empty[i]) { gx++; continue; }
                float y = h[i];
                float ny = voxNeighborY(tile, pixels, live, gx * step, z - step, sea, vscale, texMax);
                if (Float.isNaN(ny) || ny >= y) { gx++; continue; }
                int run = 1;
                while (gx + run < grid) {
                    int j = gz * grid + gx + run;
                    if (empty[j] || h[j] != y) break;
                    float n2 = voxNeighborY(tile, pixels, live, (gx + run) * step, z - step, sea, vscale, texMax);
                    if (!(Float.isNaN(n2) || n2 != ny) && n2 < y) { run++; } else { break; }
                }
                double x0 = tile.minWX + (long) (gx * step) * pb;
                double x1 = tile.minWX + (long) ((gx + run) * step) * pb;
                double zEdge = tile.minWZ + (long) (gz * step) * pb;
                double v = (sy + Math.min(texMax, z) + 0.5) / ATLAS;
                double u0 = (sx + (double) Math.min(texMax + 1, gx * step)) / ATLAS;
                double u1 = (sx + (double) Math.min(texMax + 1, (gx + run) * step)) / ATLAS;
                s.curAo = advancedLook ? (int) (aoCell[i] * 0.85f) : 255;
                voxWallZ(s, zEdge, ny, y, x0, x1, v, u0, u1, true);
                minY = Math.min(minY, ny);
                gx += run;
            }
        }
        // ---- border skirt: hides cracks between neighbouring tiles with a different LOD step ----
        // Emit exactly one curtain per tile boundary: this tile's west/north edge, and the east/south
        // edge only when that neighbour is not loaded. Emitting from both sides used to draw two
        // coincident curtains with different UVs, and the resulting z-fighting looked like dark seams
        // along tile edges. Curtains are double sided so a LOD crack stays covered from both sides.
        LiveTileStore.LiveTile eastN = live == null ? null : live.get(tile.tx + 1, tile.tz);
        LiveTileStore.LiveTile southN = live == null ? null : live.get(tile.tx, tile.tz + 1);
        for (int gz = 0; gz < grid; gz++) {
            for (int gx = 0; gx < grid; gx++) {
                int i = gz * grid + gx;
                if (empty[i]) continue;
                boolean west = gx == 0;
                boolean east = gx == grid - 1 && eastN == null;
                boolean north = gz == 0;
                boolean south = gz == grid - 1 && southN == null;
                if (!west && !east && !north && !south) continue;
                float y = h[i];
                double x0 = tile.minWX + (long) (gx * step) * pb;
                double x1 = tile.minWX + (long) ((gx + 1) * step) * pb;
                double z0 = tile.minWZ + (long) (gz * step) * pb;
                double z1 = tile.minWZ + (long) ((gz + 1) * step) * pb;
                double u = (sx + Math.min(texMax, gx * step) + 0.5) / ATLAS;
                double v = (sy + Math.min(texMax, gz * step) + 0.5) / ATLAS;
                float low = (float) (y - skirt);
                s.curAo = advancedLook ? (int) (aoCell[i] * 0.55f) : 255;
                if (west) {
                    voxWallX(s, x0, low, y, z0, z1, u, v, v, true);
                }
                if (east) {
                    voxWallX(s, x1, low, y, z0, z1, u, v, v, true);
                }
                if (north) {
                    voxWallZ(s, z0, low, y, x0, x1, v, u, u, true);
                }
                if (south) {
                    voxWallZ(s, z1, low, y, x0, x1, v, u, u, true);
                }
                minY = Math.min(minY, low);
            }
        }        double cx = tile.minWX + LiveTileStore.TILE_PIXELS * pb * 0.5;
        double cz = tile.minWZ + LiveTileStore.TILE_PIXELS * pb * 0.5;
        if (s.ii == 0) {
            return new BuildResult(gen, key, stamp, slot, null, null, 0, step,
                    cx, cz, 0.0, 0.0, System.nanoTime() - buildStart);
        }
        VertexFormat format = BlazeCompat.vertexFormat(PIPELINE);
        if (format == null) return null;
        ByteBufferBuilder alloc = new ByteBufferBuilder(Math.max(1024, s.vi * format.getVertexSize()));
        BufferBuilder bb = BlazeCompat.newBufferBuilder(alloc, format);
        for (int i = 0; i < s.vi; i++) {
            bb.addVertex(s.px[i], s.py[i], s.pz[i])
                    .setColor(s.col[i], s.col[i], s.col[i], 255)
                    .setUv(s.pu[i], s.pv[i])
                    .setUv2(240, 240);
        }
        MeshData mesh = bb.buildOrThrow();
        ByteBuffer vdata = copy(mesh.vertexBuffer());
        mesh.close();
        alloc.close();
        ByteBuffer idata = ByteBuffer.allocateDirect(s.ii * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < s.ii; i++) idata.putInt(s.idx[i]);
        idata.flip();
        return new BuildResult(gen, key, stamp, slot, vdata, idata, s.ii, step,
                cx, cz,
                minY == Double.MAX_VALUE ? 0.0 : minY,
                maxY == -Double.MAX_VALUE ? 0.0 : maxY,
                System.nanoTime() - buildStart);
    }

    /**
     * Neighbour cell height for a voxel wall; NaN when the cell is empty/unloaded. Coordinates may be
     * negative or >= 256: the pixel is then looked up in the neighbouring tile, so tile-border walls
     * on the west/north sides work exactly like interior ones (the old version returned NaN there,
     * which left a hole whenever the neighbouring tile was higher than this one).
     */
    private static float voxNeighborY(LiveTileStore.LiveTile tile, long[] pixels, LiveTileStore live,
                                      int x, int z, int sea, double vscale, int texMax) {
        int ntx = tile.tx;
        int ntz = tile.tz;
        int nx = x;
        int nz = z;
        if (x < 0) {
            ntx--;
            nx += LiveTileStore.TILE_PIXELS;
        } else if (x >= LiveTileStore.TILE_PIXELS) {
            ntx++;
            nx -= LiveTileStore.TILE_PIXELS;
        }
        if (z < 0) {
            ntz--;
            nz += LiveTileStore.TILE_PIXELS;
        } else if (z >= LiveTileStore.TILE_PIXELS) {
            ntz++;
            nz -= LiveTileStore.TILE_PIXELS;
        }
        long[] src = pixels;
        if (ntx != tile.tx || ntz != tile.tz) {
            LiveTileStore.LiveTile n = live == null ? null : live.get(ntx, ntz);
            if (n == null || n.pixels == null) return Float.NaN;
            src = n.pixels;
        }
        long packed = src[nz * LiveTileStore.TILE_PIXELS + nx];
        int top = packed == VoxyMerge.LONG_EMPTY ? VoxyMerge.NO_TOP
                : (int) ((packed >>> 24) & 0xFFFF) - 32768;
        int floor = packed == VoxyMerge.LONG_EMPTY ? VoxyMerge.NO_SOLID
                : (int) (packed & 0xFFFF) - 32768;
        if (packed == VoxyMerge.LONG_EMPTY || (top == VoxyMerge.NO_TOP && floor == VoxyMerge.NO_SOLID)) {
            return Float.NaN;
        }
        return (float) ((top == VoxyMerge.NO_TOP ? floor + 1 : top + 1) * vscale);
    }

    /** Per-corner AO brightness (0..255) for one voxel top-face vertex. */
    private static int cornerAo(float[] hb, int gw2, int gx, int gz, int corner, float y, int strength) {
        int vx = gx + ((corner == 1 || corner == 3) ? 1 : 0);
        int vz = gz + ((corner == 2 || corner == 3) ? 1 : 0);
        int occ = 0;
        if (hb[vz * gw2 + vx] > y + 0.5f) occ++;
        if (hb[vz * gw2 + vx + 1] > y + 0.5f) occ++;
        if (hb[(vz + 1) * gw2 + vx] > y + 0.5f) occ++;
        if (hb[(vz + 1) * gw2 + vx + 1] > y + 0.5f) occ++;
        float factor = switch (occ) {
            case 1 -> 0.75f;
            case 2 -> 0.55f;
            case 3 -> 0.40f;
            default -> 1f;
        };
        float f = 1f - (strength / 100f) * (1f - factor);
        return Math.max(40, Math.round(255f * f));
    }

    /** True when two cells share exactly the same per-corner AO pattern (used by greedy merging). */
    private static boolean sameAo(byte[] vao, int a, int b) {
        int oa = a * 4;
        int ob = b * 4;
        return vao[oa] == vao[ob] && vao[oa + 1] == vao[ob + 1]
                && vao[oa + 2] == vao[ob + 2] && vao[oa + 3] == vao[ob + 3];
    }

    /**
     * Ambient-occlusion tint (0..255) for one terrain sample. Valleys, cliff feet and wall corners
     * are surrounded by higher columns, so they accumulate occlusion and get darker; the tint is
     * written into the vertex colour (the shader multiplies the atlas colour by it).
     */
    /** AO byte for an occlusion value (0..1); shared by the smooth-vertex AO pass. */
    private static int aoColour(float occ, int strength) {
        float amount = (strength / 100f) * 0.75f * (occ / (occ + 0.6f));
        return Math.max(40, Math.round(255f * (1f - amount)));
    }
    private static void voxAdd(VoxelScratch s, double x, double y, double z, double u, double v) {
        s.ensureVerts(s.vi + 1);
        s.col[s.vi] = s.curAo;
        s.px[s.vi] = (float) x;
        s.py[s.vi] = (float) y;
        s.pz[s.vi] = (float) z;
        s.pu[s.vi] = (float) u;
        s.pv[s.vi] = (float) v;
        s.vi++;
    }

    private static void voxTri(VoxelScratch s, int a, int b, int c) {
        s.ensureIndices(s.ii + 3);
        s.idx[s.ii] = a;
        s.idx[s.ii + 1] = b;
        s.idx[s.ii + 2] = c;
        s.ii += 3;
    }

    /** Vertical wall on a constant-X plane (east/west or a skirt face); both windings when cullSafe. */
    private static void voxWallX(VoxelScratch s, double xEdge, float yLow, float yHigh,
                                 double z0, double z1, double u, double v0, double v1, boolean cullSafe) {
        int b = s.vi;
        voxAdd(s, xEdge, yLow, z0, u, v0);
        voxAdd(s, xEdge, yLow, z1, u, v1);
        voxAdd(s, xEdge, yHigh, z0, u, v0);
        voxAdd(s, xEdge, yHigh, z1, u, v1);
        voxTri(s, b, b + 1, b + 2);
        voxTri(s, b + 2, b + 1, b + 3);
        if (cullSafe) {
            voxTri(s, b + 2, b + 1, b);
            voxTri(s, b + 3, b + 1, b + 2);
        }
    }

    /** Vertical wall on a constant-Z plane (south/north or a skirt face); both windings when cullSafe. */
    private static void voxWallZ(VoxelScratch s, double zEdge, float yLow, float yHigh,
                                 double x0, double x1, double v, double u0, double u1, boolean cullSafe) {
        int b = s.vi;
        voxAdd(s, x0, yLow, zEdge, u0, v);
        voxAdd(s, x1, yLow, zEdge, u1, v);
        voxAdd(s, x0, yHigh, zEdge, u0, v);
        voxAdd(s, x1, yHigh, zEdge, u1, v);
        voxTri(s, b, b + 1, b + 2);
        voxTri(s, b + 2, b + 1, b + 3);
        if (cullSafe) {
            voxTri(s, b + 2, b + 1, b);
            voxTri(s, b + 3, b + 1, b + 2);
        }
    }

    /** Growable per-thread scratch for the voxel mesher (vertex/index/level arrays). */
    private static final class VoxelScratch {
        private static final ThreadLocal<VoxelScratch> LOCAL = ThreadLocal.withInitial(VoxelScratch::new);
        float[] px = new float[0], py = new float[0], pz = new float[0], pu = new float[0], pv = new float[0];
        int[] col = new int[0];
        int[] idx = new int[0];
        float[] h = new float[0];
        boolean[] emptyCell = new boolean[0], waterCell = new boolean[0], visited = new boolean[0];
        int[] aoCell = new int[0];
        float[] hb = new float[0];
        byte[] vao = new byte[0];
        int vi, ii;
        int curAo = 255;

        static VoxelScratch get(int cells) {
            VoxelScratch s = LOCAL.get();
            if (s.h.length < cells) {
                s.h = new float[cells];
                s.emptyCell = new boolean[cells];
                s.waterCell = new boolean[cells];
                s.visited = new boolean[cells];
                s.aoCell = new int[cells];
                int g = (int) Math.round(Math.sqrt(cells));
                s.hb = new float[(g + 2) * (g + 2)];
                s.vao = new byte[cells * 4];
            }
            s.curAo = 255;
            s.vi = 0;
            s.ii = 0;
            return s;
        }

        void ensureVerts(int n) {
            if (px.length >= n) return;
            int cap = Math.max(4096, (int) (n * 1.5));
            px = java.util.Arrays.copyOf(px, cap);
            py = java.util.Arrays.copyOf(py, cap);
            pz = java.util.Arrays.copyOf(pz, cap);
            pu = java.util.Arrays.copyOf(pu, cap);
            pv = java.util.Arrays.copyOf(pv, cap);
            col = java.util.Arrays.copyOf(col, cap);
        }

        void ensureIndices(int n) {
            if (idx.length >= n) return;
            idx = java.util.Arrays.copyOf(idx, Math.max(4096, (int) (n * 1.5)));
        }
    }

    private static final class BuildScratch {
        private static final int MAX_CACHED_VERTS = 5000;
        private static final int MAX_CACHED_INDICES = 30000;
        private static final ThreadLocal<BuildScratch> LOCAL = ThreadLocal.withInitial(BuildScratch::new);

        float[] px = new float[0], py = new float[0], pz = new float[0], pu = new float[0], pv = new float[0];
        int[] col = new int[0];
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
            s.col = grow(s.col, verts);
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
            col = new int[verts];
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
        // Experimental voxel terrain is limited to the highest-precision (near) LOD step: larger
        // steps would render huge blocks and look worse than the smooth height field.
        boolean nearLod = step <= meshStep(VoxyMapConfig.GPU_TERRAIN_NEAR_STEP.get());
        if (VoxyMapConfig.GPU_TERRAIN_VOXEL.get()
                && step == meshStep(VoxyMapConfig.GPU_TERRAIN_NEAR_STEP.get())) {
            return buildVoxel(gen, key, stamp, slot, step, tile, pixels, live, pb, sea, vscale, buildStart);
        }
        // AO is a near-range effect: mid/far LOD tiles cover only a few screen pixels per cell, so
        // skip the per-vertex occlusion sampling there to save mesh-build time.
        final boolean aoEnabled = nearLod
                && VoxyMapConfig.ADVANCED_3D_RENDER.get()
                && VoxyMapConfig.ADVANCED_3D_AO.get() > 0;
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
        int[] col = scratch.col;
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
                } else if (top == VoxyMerge.NO_TOP) {
                    // Surface = top face of the highest non-air block. Water columns store their
                    // real water surface (not sea level), so lakes/rivers above or below sea level
                    // are drawn at the correct height as well.
                    y = (float) ((floor + 1) * vscale);
                } else {
                    y = (float) ((top + 1) * vscale);
                }
                px[vi] = wx;
                py[vi] = y;
                pz[vi] = wz;
                pu[vi] = (sx + Math.min(texMax, x) + 0.5f) / ATLAS;
                pv[vi] = v;
                col[vi] = 255;
                empty[vi++] = e;
                if (!e) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        // Per-vertex AO from the already sampled vertex heights: interior neighbours come straight
        // from py[], only the tile border samples across the seam once. The old code called
        // voxNeighborY four times for every vertex (about 260k lookups for a 256-cell near tile).
        if (aoEnabled && vi > 0) {
            int aoStrength = VoxyMapConfig.ADVANCED_3D_AO.get();
            for (int gz = 0; gz <= grid; gz++) {
                int z = gz * step;
                int row = gz * gw;
                for (int gx = 0; gx <= grid; gx++) {
                    int i = row + gx;
                    if (empty[i]) {
                        col[i] = 255;
                        continue;
                    }
                    float h = py[i];
                    float occ = 0f;
                    int n = 0;
                    float nh;
                    nh = gx < grid ? py[i + 1]
                            : voxNeighborY(tile, pixels, live, gx * step + step, z, sea, vscale, texMax);
                    if (!Float.isNaN(nh)) {
                        float d = nh - h;
                        if (d > 0f) occ += Math.min(1f, d / Math.max(1f, step));
                        n++;
                    }
                    nh = gx > 0 ? py[i - 1]
                            : voxNeighborY(tile, pixels, live, gx * step - step, z, sea, vscale, texMax);
                    if (!Float.isNaN(nh)) {
                        float d = nh - h;
                        if (d > 0f) occ += Math.min(1f, d / Math.max(1f, step));
                        n++;
                    }
                    nh = gz < grid ? py[i + gw]
                            : voxNeighborY(tile, pixels, live, gx * step, z + step, sea, vscale, texMax);
                    if (!Float.isNaN(nh)) {
                        float d = nh - h;
                        if (d > 0f) occ += Math.min(1f, d / Math.max(1f, step));
                        n++;
                    }
                    nh = gz > 0 ? py[i - gw]
                            : voxNeighborY(tile, pixels, live, gx * step, z - step, sea, vscale, texMax);
                    if (!Float.isNaN(nh)) {
                        float d = nh - h;
                        if (d > 0f) occ += Math.min(1f, d / Math.max(1f, step));
                        n++;
                    }
                    col[i] = n == 0 ? 255 : aoColour(occ / 4f, aoStrength);
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
            col[ba] = col[ta];
            col[bb] = col[tb];
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
                    .setColor(col[i], col[i], col[i], 255)
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
     * Builds the per-frame world-space decorations drawn in the terrain pass: the yellow
     * render-distance ribbon (optional) and the player marker's vertical tick line (always).
     *
     * <p>The tick line lives here rather than in {@link MapScreen} because it has to be real 3D
     * geometry: each segment is coloured by whether it sits inside the terrain mesh, and world-space
     * dashes keep the "distance to the ground" look at every zoom. Drawing it with 2D fills could
     * not shade the buried part or keep the dash length stable.</p>
     */
    /** Static max sizes of the overlay staging/GPU buffers (ribbon + marker line). */
    private static final int OVERLAY_MAX_VERTS =
            4 * (OVERLAY_SEGMENTS + 1) * 2 + MARKER_MAX_SEGMENTS * 4;
    private static final int OVERLAY_MAX_INDICES =
            4 * OVERLAY_SEGMENTS * 6 + MARKER_MAX_SEGMENTS * 6;
    /** Reused staging memory: the overlay is rebuilt every frame, so nothing may allocate here. */
    private static ByteBuffer overlayStagingVb;
    private static ByteBuffer overlayStagingIb;
    private static ByteBuffer arrowStagingVb;
    private static ByteBuffer arrowStagingIb;

    /**
     * Builds this frame's distance ribbon + marker line into the current ring slot and records the
     * uploads into {@code encoder}. Nothing is submitted here: the copy and the draws share one
     * encoder, so a frame keeps vanilla's submit budget, and the 3-slot ring (+ fence on 26.2) is
     * what makes overwriting the previous frames' buffers safe.
     */
    private static boolean prepareOverlay(com.mojang.blaze3d.systems.CommandEncoder encoder,
                                          MapScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        MapData data = MapData.INSTANCE;
        var player = mc.player;
        boolean ribbon = player != null && data.isReady() && data.isRenderDistanceOverlay();
        double[] marker = screen.markerGeometry3d();
        double[] line = marker == null ? null : markerLineSpan(marker);
        if (!ribbon && line == null) {
            overlaySlotInUse = -1;
            return false;
        }

        // take the next ring slot; wait for the frame that used it three frames ago
        int slot = overlaySlot;
        overlaySlot = (overlaySlot + 1) % OVERLAY_SLOTS;
        BlazeCompat.awaitAndCloseFence(overlayFences[slot]);
        overlayFences[slot] = null;
        if (overlayVbos[slot] == null) {
            final int s = slot;
            overlayVbos[slot] = RenderSystem.getDevice().createBuffer(
                    () -> "voxymap_gpu3d_overlay_v" + s,
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, OVERLAY_MAX_VERTS * 16);
            overlayIbos[slot] = RenderSystem.getDevice().createBuffer(
                    () -> "voxymap_gpu3d_overlay_i" + s,
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, OVERLAY_MAX_INDICES * 4);
        }
        if (overlayStagingVb == null) {
            overlayStagingVb = ByteBuffer.allocateDirect(OVERLAY_MAX_VERTS * 16)
                    .order(ByteOrder.nativeOrder());
            overlayStagingIb = ByteBuffer.allocateDirect(OVERLAY_MAX_INDICES * 4)
                    .order(ByteOrder.nativeOrder());
            arrowStagingVb = ByteBuffer.allocateDirect(ARROW_VERTS * 20)
                    .order(ByteOrder.nativeOrder());
            arrowStagingIb = ByteBuffer.allocateDirect(ARROW_INDICES * 4)
                    .order(ByteOrder.nativeOrder());
        }
        ByteBuffer vb = overlayStagingVb;
        ByteBuffer ib = overlayStagingIb;
        vb.clear();
        ib.clear();
        int vi = 0, ii = 0;
        /** Index range of the ribbon; the marker line follows it in the same buffer. */
        int ribbonIndexCount = 0;
        if (ribbon) {
            int pb = Math.max(1, data.getPixelBlocks());
            double vscale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
            double dist = mc.options.renderDistance().get() * 16.0;
            double cx = player.getX();
            double cz = player.getZ();
            double width = Math.max(1.0, pb * 1.5);
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
                    double y = data.surfaceYAt((int) Math.floor(wx), (int) Math.floor(wz)) * vscale + 0.05;
                    float ax = (float) (wx + nx * width * 0.5);
                    float az = (float) (wz + nz * width * 0.5);
                    float bx = (float) (wx - nx * width * 0.5);
                    float bz = (float) (wz - nz * width * 0.5);
                    putOverlayVertex(vb, ax, (float) y, az, 0xFF, 0xFF, 0x00, 0xC0);
                    putOverlayVertex(vb, bx, (float) y, bz, 0xFF, 0xFF, 0x00, 0xC0);
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
        }
        ribbonIndexCount = ii;
        int markerFirst = ii;
        int[] cursor = {vi, ii};
        if (line != null) {
            appendMarkerLine(screen, line, vb, ib, cursor);
        }
        ii = cursor[1];
        boolean anyOverlay = ii > 0;
        // The arrow lives in its own buffers: it is POSITION_TEX (20-byte stride) while everything
        // else here is POSITION_COLOR (16-byte stride), and an indexed draw has no per-draw vertex
        // stride offset, so the two cannot share one vertex buffer.
        arrowIndexCount = 0;
        int arrowIndices = 0;
        if (marker != null) {
            ByteBuffer avb = arrowStagingVb;
            ByteBuffer aib = arrowStagingIb;
            avb.clear();
            aib.clear();
            arrowIndices = appendMarkerArrow(screen, marker, avb, aib);
            if (arrowIndices > 0) {
                avb.flip();
                aib.flip();
                if (arrowVbos[slot] == null) {
                    final int s = slot;
                    arrowVbos[slot] = RenderSystem.getDevice().createBuffer(
                            () -> "voxymap_marker_arrow_v" + s,
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, ARROW_VERTS * 20);
                    arrowIbos[slot] = RenderSystem.getDevice().createBuffer(
                            () -> "voxymap_marker_arrow_i" + s,
                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, ARROW_INDICES * 4);
                }
                encoder.writeToBuffer(new GpuBufferSlice(arrowVbos[slot], 0L, avb.remaining()), avb);
                encoder.writeToBuffer(new GpuBufferSlice(arrowIbos[slot], 0L, aib.remaining()), aib);
                arrowIndexCount = arrowIndices;
            }
        }
        overlaySlotInUse = slot;
        if (!anyOverlay) {
            overlayIndexCount = 0;
            markerIndexCount = 0;
            return arrowIndexCount > 0;
        }
        vb.flip();
        ib.flip();
        encoder.writeToBuffer(new GpuBufferSlice(overlayVbos[slot], 0L, vb.remaining()), vb);
        encoder.writeToBuffer(new GpuBufferSlice(overlayIbos[slot], 0L, ib.remaining()), ib);
        overlayIndexCount = ribbonIndexCount;
        markerFirstIndex = markerFirst;
        markerIndexCount = ii - markerFirst;
        return true;
    }

    /**
     * Vertical tick line under the player marker, as a camera-facing ribbon.
     *
     * <p>Colour rules (the line is always drawn on top, so the colour carries the information):</p>
     * <ul>
     *     <li>visible from the camera — white;</li>
     *     <li>hidden behind the terrain (a ridge between the camera and the line, or the line part
     *         that sits inside the mesh) — light grey;</li>
     *     <li>the airborne drop between the player and the ground — dashes, in whichever of the two
     *         colours applies (so a dashed part behind a hill is dashed <em>and</em> grey).</li>
     * </ul>
     *
     * <p>Occlusion is sampled from the map data, not from the depth buffer, which is why the line
     * can be drawn with the depth test disabled and still report what the terrain hides.</p>
     */
    private static int appendMarkerLine(MapScreen screen, double[] span, ByteBuffer vb, ByteBuffer ib,
                                        int[] cursor) {
        Map3D.Camera cam = screen.gpu3dCamera();
        if (cam == null) return cursor[1];
        MapData data = MapData.INSTANCE;
        double vscale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
        double px = span[0];
        double pz = span[1];
        double feet = span[2];
        double surface = span[3];
        double surfaceTop = span[4];
        double bottom = span[5];
        double top = span[6];
        boolean airborne = span[7] > 0.5;
        double eyeX = cam.eyeX();
        double eyeY = cam.eyeY();
        double eyeZ = cam.eyeZ();

        // billboard: offset perpendicular to the camera -> player horizontal direction
        double vx = px - cam.eyeX();
        double vz = pz - cam.eyeZ();
        double vlen = Math.hypot(vx, vz);
        if (vlen < 1.0e-3) {
            vx = 0;
            vz = 1;
            vlen = 1;
        }
        double rx = -vz / vlen;
        double rz = vx / vlen;
        // Screen-constant thickness: the world size grows with the camera distance, so zooming out
        // does not turn the line into a fat band (and zooming in does not make it vanish).
        double halfW = cam.distance * 0.0018;

        double spanLen = top - bottom;
        if (spanLen < 0.05) return cursor[1];
        // Dashes are uniform world-space cells measured from the TOP of the drop. That fixes three
        // things at once: every cell is the same length (the old per-segment toggle quantised the
        // boundaries to a step that was not a divisor of the period, so cells alternated n / n+1),
        // the pattern no longer depends on the camera distance (which is why it slid around while
        // zooming), and the first cell under the solid part is empty.
        double drop = airborne ? Math.max(0.0, feet - surface) : 0.0;
        double cell = Math.max(0.75, drop / 10.0);

        // Occlusion is sampled at a dozen heights and looked up per piece: one ray per piece
        // would be ~100 ray marches per frame for a line a few blocks tall, all of them almost
        // the same ray. 13 samples is visually indistinguishable at this size.
        int occSamples = 13;
        boolean[] occluded = new boolean[occSamples];
        for (int i = 0; i < occSamples; i++) {
            double y = bottom + spanLen * i / (occSamples - 1);
            occluded[i] = rayBlocked(data, vscale, eyeX, eyeY, eyeZ, px, y, pz);
        }

        int vi = cursor[0];
        int ii = cursor[1];
        int quads = 0;
        // 1) solid head: from the arrow down to the player's feet (the whole line when standing)
        double solidLow = airborne ? Math.max(feet, bottom) : bottom;
        if (top > solidLow + 1.0e-4) {
            double len = top - solidLow;
            int segs = Math.max(2, Math.min(48, (int) Math.ceil(len)));
            double step = len / segs;
            for (int s = 0; s < segs && quads < MARKER_MAX_SEGMENTS; s++) {
                double y1 = top - s * step;
                double y0 = Math.max(solidLow, y1 - step);
                double mid = (y0 + y1) * 0.5;
                int occIndex = Math.max(0, Math.min(occSamples - 1,
                        (int) Math.round((mid - bottom) / spanLen * (occSamples - 1))));
                // "inside the mesh" means below the rendered surface (the mesh draws the surface at
                // `surface`, i.e. heightAt), not below surfaceTop — that is the standing offset.
                boolean buried = mid < surface;
                boolean hidden = buried || occluded[occIndex];
                // light grey: still readable over the terrain, but clearly "behind something"
                int c = hidden ? 0xBE : 0xFF;
                putOverlayVertex(vb, px + rx * halfW, y0, pz + rz * halfW, c, c, c, 0xFF);
                putOverlayVertex(vb, px - rx * halfW, y0, pz - rz * halfW, c, c, c, 0xFF);
                putOverlayVertex(vb, px + rx * halfW, y1, pz + rz * halfW, c, c, c, 0xFF);
                putOverlayVertex(vb, px - rx * halfW, y1, pz - rz * halfW, c, c, c, 0xFF);
                ib.putInt(vi).putInt(vi + 1).putInt(vi + 2);
                ib.putInt(vi + 1).putInt(vi + 3).putInt(vi + 2);
                vi += 4;
                ii += 6;
                quads++;
            }
        }
        // 2) dashed drop: from the feet down to the ground, first cell empty
        if (airborne && drop > 1.0e-3) {
            int cells = (int) Math.ceil(drop / cell - 1.0e-6);
            for (int k = 0; k < cells && quads < MARKER_MAX_SEGMENTS; k++) {
                if ((k & 1) == 0) continue;              // the first cell (k = 0) is a gap
                double y1 = feet - k * cell;
                double y0 = Math.max(surface, y1 - cell);
                if (y1 - y0 < 1.0e-3) continue;
                double mid = (y0 + y1) * 0.5;
                int occIndex = Math.max(0, Math.min(occSamples - 1,
                        (int) Math.round((mid - bottom) / spanLen * (occSamples - 1))));
                int c = occluded[occIndex] ? 0xBE : 0xFF;   // never buried: below feet, above surface
                putOverlayVertex(vb, px + rx * halfW, y0, pz + rz * halfW, c, c, c, 0xFF);
                putOverlayVertex(vb, px - rx * halfW, y0, pz - rz * halfW, c, c, c, 0xFF);
                putOverlayVertex(vb, px + rx * halfW, y1, pz + rz * halfW, c, c, c, 0xFF);
                putOverlayVertex(vb, px - rx * halfW, y1, pz - rz * halfW, c, c, c, 0xFF);
                ib.putInt(vi).putInt(vi + 1).putInt(vi + 2);
                ib.putInt(vi + 1).putInt(vi + 3).putInt(vi + 2);
                vi += 4;
                ii += 6;
                quads++;
            }
        }
        cursor[0] = vi;
        cursor[1] = ii;
        return ii;
    }

    /**
     * True when the terrain mesh blocks the straight line between the camera and a point.
     *
     * <p>Walked against the map data ({@code heightAt}) rather than the depth buffer, so it can be
     * evaluated for the whole marker line before it is drawn — the line itself is drawn without a
     * depth test and only uses this to pick its colour. Samples are capped so a very distant
     * marker cannot turn this into a per-frame hotspot.</p>
     */
    private static boolean rayBlocked(MapData data, double vscale, double ex, double ey, double ez,
                                      double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-3) return false;
        int steps = (int) Math.max(10, Math.min(160, Math.round(len / 6.0)));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            double x = ex + dx * t;
            double y = ey + dy * t;
            double z = ez + dz * t;
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            if (data.isEmptyAt(bx, bz)) continue;
            if (y <= data.surfaceYAt(bx, bz) * vscale) return true;
        }
        return false;
    }

    /**
     * Player arrow: a horizontal textured quad on the marker height, sampled straight from the
     * arrow PNG by the GPU.
     *
     * <p>This replaced a CPU rasteriser that inverse-mapped the quad per pixel into a 128²
     * {@code NativeImage} and uploaded it as a DynamicTexture every time the projection changed
     * (the GUI's affine blit cannot show a tilted quad correctly). With a world-space textured
     * pipeline the perspective correction is free and the per-frame 16k-pixel work + upload are
     * gone.</p>
     */
    private static int appendMarkerArrow(MapScreen screen, double[] m, ByteBuffer vb, ByteBuffer ib) {
        Map3D.Camera cam = screen.gpu3dCamera();
        var player = Minecraft.getInstance().player;
        if (cam == null || player == null) return 0;
        double px = m[0];
        double pz = m[1];
        double markY = m[5];
        double yaw = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = Math.cos(yaw);
        double rz = Math.sin(yaw);
        double scale = Math.max(0.2, VoxyMapConfig.PLAYER_MARKER_SCALE_3D.get());
        // Both terms are relative to the camera distance, so the arrow keeps a constant fraction
        // of the view (no absolute block floor). The explicit GUI-scale factor is what makes the 3D
        // arrow grow exactly like the 2D one, which is a fixed-size GUI blit: physical size =
        // config scale * (guiScale / 4).
        double half = Math.max(cam.distance * 0.004, cam.distance * 0.022 * scale)
                * MapScreen.uiScaleFactor();
        double ex = rx * half;
        double ez = rz * half;
        double ax = fx * half;
        double az = fz * half;
        // v = 0 is the tip (facing end); same UV assignment the CPU rasteriser used
        putTexVertex(vb, px + ax - ex, markY, pz + az - ez, 0f, 0f);
        putTexVertex(vb, px + ax + ex, markY, pz + az + ez, 1f, 0f);
        putTexVertex(vb, px - ax + ex, markY, pz - az + ez, 1f, 1f);
        putTexVertex(vb, px - ax - ex, markY, pz - az - ez, 0f, 1f);
        ib.putInt(0).putInt(1).putInt(2);
        ib.putInt(0).putInt(2).putInt(3);
        return ARROW_INDICES;
    }

    /** POSITION_TEX vertex (3 floats + 2 floats = 20 bytes). */
    private static void putTexVertex(ByteBuffer vb, double x, double y, double z, float u, float v) {
        vb.putFloat((float) x);
        vb.putFloat((float) y);
        vb.putFloat((float) z);
        vb.putFloat(u);
        vb.putFloat(v);
    }

    /**
     * Reduces the shared marker geometry ({@code {px, pz, feet, surface, surfaceTop, markY, hasModel}})
     * to what the tick line needs: {@code {px, pz, feet, surface, surfaceTop, bottom, top, airborne}}.
     * Without map data there is nothing to shade against, so the line stops at the player.
     */
    private static double[] markerLineSpan(double[] m) {
        double px = m[0];
        double pz = m[1];
        double feet = m[2];
        double surface = m[3];
        double surfaceTop = m[4];
        double markY = m[5];
        boolean hasModel = m[6] > 0.5;
        double bottom = hasModel ? Math.min(feet, surface) : feet;
        double scale = Math.max(0.01, VoxyMapConfig.VERTICAL_SCALE_3D.get());
        boolean airborne = hasModel && feet > surfaceTop + 0.6 * scale;
        return new double[]{px, pz, feet, surface, surfaceTop, bottom, markY, airborne ? 1 : 0};
    }


    private static void putOverlayVertex(ByteBuffer vb, double x, double y, double z,
                                         int r, int g, int b, int a) {
        vb.putFloat((float) x);
        vb.putFloat((float) y);
        vb.putFloat((float) z);
        vb.put((byte) r);
        vb.put((byte) g);
        vb.put((byte) b);
        vb.put((byte) a);
    }

    /**
     * World-space textured pipeline for the player arrow: GLOBALS + projection + Sampler0, the
     * vanilla {@code core/position_tex} shader, POSITION_TEX vertices, translucent blending and no
     * depth test (the arrow is a UI marker and must stay visible).
     */
    private static RenderPipeline arrowPipeline() {
        try {
            return BlazeCompat.arrowPipeline();
        } catch (Throwable t) {
            System.out.println("[voxymap] arrow pipeline unavailable, marker arrow disabled: " + t);
            return null;
        }
    }

    private static void drawOverlay(RenderPass pass, GpuBufferSlice transforms) {
        if (overlaySlotInUse < 0) return;
        GpuBuffer overlayVb = overlayVbos[overlaySlotInUse];
        GpuBuffer overlayIb = overlayIbos[overlaySlotInUse];
        if (overlayVb == null || overlayIb == null) return;
        if (overlayIndexCount > 0) {
            pass.setPipeline(OVERLAY_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            BlazeCompat.setVertexBuffer(pass, 0, overlayVb);
            BlazeCompat.setIndexBuffer(pass, overlayIb);
            BlazeCompat.drawIndexed(pass, 0, overlayIndexCount);
        }
        if (markerIndexCount > 0) {
            // no depth test: the marker line is a UI annotation, not terrain. If the custom pipeline
            // could not be registered, fall back to the depth-tested overlay pipeline so the line
            // still shows (just occluded like everything else).
            RenderPipeline pipeline = MARKER_PIPELINE != null ? MARKER_PIPELINE : OVERLAY_PIPELINE;
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            BlazeCompat.setVertexBuffer(pass, 0, overlayVb);
            BlazeCompat.setIndexBuffer(pass, overlayIb);
            BlazeCompat.drawIndexed(pass, markerFirstIndex, markerIndexCount);
        }
        if (arrowIndexCount > 0 && ARROW_PIPELINE != null) {
            GpuTextureView arrow = arrowTextureView();
            if (arrow != null) {
                pass.setPipeline(ARROW_PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.bindTexture("Sampler0", arrow, arrowSampler);
                BlazeCompat.setVertexBuffer(pass, 0, arrowVbos[overlaySlotInUse]);
                BlazeCompat.setIndexBuffer(pass, arrowIbos[overlaySlotInUse]);
                BlazeCompat.drawIndexed(pass, arrowIndexCount);
            }
        }
    }

    /** The arrow PNG resolved to a GPU view, honouring the configured marker style. */
    private static GpuTextureView arrowTextureView() {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager()
                .getTexture(MapScreen.arrowTextureId());
        return texture == null ? null : texture.getTextureView();
    }

    /**
     * Rebuilds the recorded-track ribbon. Only runs when {@link TrackOverlay#version()} changes, so
     * the cost is one mesh upload per sampling step rather than per frame; the vertex layout and
     * pipeline are the same as the render-distance ribbon (POSITION_COLOR quads).
     */
    private static void ensureTrackMesh() {
        int want = TrackOverlay.enabled() ? TrackOverlay.version() : 0;
        if (want == trackBuiltVersion) return;
        int n = TrackOverlay.count();
        long now = System.currentTimeMillis();
        boolean cleared = !TrackOverlay.enabled() || n < trackBuiltCount;
        if (!cleared && trackIndexCount > 0 && n - trackBuiltCount < TRACK_REBUILD_POINTS
                && now - trackBuiltMs < TRACK_REBUILD_MS) {
            // Keep the previous geometry until the next threshold instead of rebuilding for every
            // single 2-block sample.
            return;
        }
        trackBuiltVersion = want;
        trackBuiltCount = n;
        trackBuiltMs = now;
        retireTrackBuffers();
        trackIndexCount = 0;
        if (!TrackOverlay.enabled() || n < 2) return;
        double vscale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
        if (trackStagingVb == null) {
            trackStagingVb = ByteBuffer.allocateDirect(TrackOverlay.MAX_POINTS * 4 * 16)
                    .order(ByteOrder.nativeOrder());
            trackStagingIb = ByteBuffer.allocateDirect(TrackOverlay.MAX_POINTS * 6 * 4)
                    .order(ByteOrder.nativeOrder());
        }
        ByteBuffer vb = trackStagingVb;
        ByteBuffer ib = trackStagingIb;
        vb.clear();
        ib.clear();
        int vi = 0;
        int ii = 0;
        for (int i = 1; i < n; i++) {
            if (TrackOverlay.startsSegment(i)) continue;
            int x0 = TrackOverlay.x(i - 1);
            int z0 = TrackOverlay.z(i - 1);
            int x1 = TrackOverlay.x(i);
            int z1 = TrackOverlay.z(i);
            double dx = x1 - x0;
            double dz = z1 - z0;
            double len = Math.hypot(dx, dz);
            if (len < 1.0e-3) continue;
            double nx = -dz / len * TRACK_HALF_WIDTH;
            double nz = dx / len * TRACK_HALF_WIDTH;
            // the recorded player Y (not the terrain surface): flying paths float, cave paths stay
            // down in the cave instead of being lifted onto the mountain above
            double y0 = TrackOverlay.y(i - 1) * vscale + TRACK_LIFT;
            double y1 = TrackOverlay.y(i) * vscale + TRACK_LIFT;
            // only the start of the path fades in; the rest stays opaque (see TrackOverlay.alphaAt)
            int alpha = TrackOverlay.alphaAt(i);
            putTrackVertex(vb, x0 + nx, y0, z0 + nz, alpha);
            putTrackVertex(vb, x0 - nx, y0, z0 - nz, alpha);
            putTrackVertex(vb, x1 + nx, y1, z1 + nz, alpha);
            putTrackVertex(vb, x1 - nx, y1, z1 - nz, alpha);
            ib.putInt(vi).putInt(vi + 1).putInt(vi + 2);
            ib.putInt(vi + 1).putInt(vi + 3).putInt(vi + 2);
            vi += 4;
            ii += 6;
        }
        if (ii == 0) return;
        vb.flip();
        ib.flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        trackVbo = upload(encoder, "voxymap_track_v", GpuBuffer.USAGE_VERTEX, vb);
        trackIbo = upload(encoder, "voxymap_track_i", GpuBuffer.USAGE_INDEX, ib);
        BlazeCompat.submit(encoder);
        trackIndexCount = ii;
    }

    /** Track colour: red with a per-vertex alpha (the geometry carries the start fade). */
    private static void putTrackVertex(ByteBuffer vb, double x, double y, double z, int alpha) {
        vb.putFloat((float) x);
        vb.putFloat((float) y);
        vb.putFloat((float) z);
        vb.put((byte) 0xFF);
        vb.put((byte) 0x40);
        vb.put((byte) 0x40);
        vb.put((byte) alpha);
    }

    private static void drawTrack(RenderPass pass, GpuBufferSlice transforms) {
        if (trackIndexCount <= 0 || trackVbo == null || trackIbo == null) return;
        pass.setPipeline(OVERLAY_PIPELINE);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
        BlazeCompat.setVertexBuffer(pass, 0, trackVbo);
        BlazeCompat.setIndexBuffer(pass, trackIbo);
        BlazeCompat.drawIndexed(pass, trackIndexCount);
    }

    private static void retireTrackBuffers() {
        if (trackVbo != null) {
            RETIRED.add(new RetiredBuffer(trackVbo, frameCounter));
            trackVbo = null;
        }
        if (trackIbo != null) {
            RETIRED.add(new RetiredBuffer(trackIbo, frameCounter));
            trackIbo = null;
        }
    }


    // ------------------------------------------------------------ blocks-style sky / fog (advanced 3D)

    private static RenderPipeline terrain3dPipeline() {
        RenderPipeline p = terrain3dPipeline;
        if (p == null) {
            try {
                p = BlazeCompat.advancedTerrain3dPipeline();
            } catch (Throwable t) {
                System.out.println("[voxymap] 3d fog pipeline unavailable, falling back to solid block: " + t);
                p = PIPELINE;
            }
            terrain3dPipeline = p;
        }
        return p;
    }

    /** Sky sphere + horizontal fog only exist for the experimental renderer and the blocks style. */
    private static boolean skyFogActive() {
        return VoxyMapConfig.ADVANCED_3D_RENDER.get()
                && BlazeCompat.advanced3dSupported()
                && "blocks".equals(MapData.INSTANCE.getStyle());
    }

    private static int lerpColor(int from, int to, float t) {
        int r0 = (from >> 16) & 0xFF, g0 = (from >> 8) & 0xFF, b0 = from & 0xFF;
        int r1 = (to >> 16) & 0xFF, g1 = (to >> 8) & 0xFF, b1 = to & 0xFF;
        int r = Math.round(r0 + (r1 - r0) * t);
        int g = Math.round(g0 + (g1 - g0) * t);
        int b = Math.round(b0 + (b1 - b0) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Number of bytes per POSITION_COLOR vertex (3 floats + RGBA). */
    private static final int SKY_VERTEX_BYTES = 16;

    private static void skyVertex(ByteBuffer buf, float x, float y, int argb) {
        buf.putFloat(x).putFloat(y).putFloat(0f);
        buf.put((byte) ((argb >> 16) & 0xFF)).put((byte) ((argb >> 8) & 0xFF))
                .put((byte) (argb & 0xFF)).put((byte) 0xFF);
    }

    /** Two triangles filling the horizontal band from y0 (top colour) to y1 (bottom colour). */
    private static void skyBand(ByteBuffer buf, float y0, float y1, int topColor, int bottomColor) {
        skyVertex(buf, -1f, y0, topColor);
        skyVertex(buf, 1f, y0, topColor);
        skyVertex(buf, 1f, y1, bottomColor);
        skyVertex(buf, -1f, y0, topColor);
        skyVertex(buf, 1f, y1, bottomColor);
        skyVertex(buf, -1f, y1, bottomColor);
    }

    /**
     * Builds the procedural screen-space sky for this frame. The horizon's NDC y comes from the
     * camera pitch and the FOV, so no sphere geometry (and no depth/culling tricks) are involved.
     * Everything is uploaded before the render pass opens.
     */
    private static void prepareSky(CommandEncoder encoder, float fov, Map3D.Camera cam,
                                   int skyColor, int fogColor) {
        skyVertexCount = 0;
        if (SKY_PIPELINE == null) return;
        if (skyVbo == null) {
            skyVbo = RenderSystem.getDevice().createBuffer(
                    () -> "voxymap_3d_sky", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    SKY_MAX_VERTS * SKY_VERTEX_BYTES);
        }
        if (skyStaging == null) {
            skyStaging = ByteBuffer.allocateDirect(SKY_MAX_VERTS * SKY_VERTEX_BYTES)
                    .order(ByteOrder.nativeOrder());
        }
        // Below the horizon darkens towards black as the camera tilts down. The blend is gradual
        // (fully coloured above -60 degrees, fully black at -85 degrees), so tilting up from a
        // top-down view fades from black to the horizon colour instead of switching abruptly.
        double pitchDeg = Math.toDegrees(cam.pitch);
        float straight = (float) Math.max(0.0, Math.min(1.0, (-pitchDeg - 60.0) / 25.0));
        straight = straight * straight * (3f - 2f * straight);
        int belowColor = lerpColor(fogColor, SKY_BLACK, straight);
        double tanHalfFov = Math.tan(Math.toRadians(fov * 0.5));
        double horizon = -Math.tan(cam.pitch) / Math.max(1e-4, tanHalfFov);
        skyStaging.clear();
        if (horizon >= 1.0) {
            // Horizon is above the screen: everything visible is below it.
            skyBand(skyStaging, 1f, -1f, belowColor, belowColor);
        } else if (horizon <= -1.0) {
            // Whole screen is above the horizon: fade sky -> horizon colour top to bottom.
            skyBand(skyStaging, 1f, -1f, skyColor, fogColor);
        } else {
            float hz = (float) horizon;
            skyBand(skyStaging, 1f, hz, skyColor, fogColor);
            // Fade from the horizon colour at the horizon line to the pitch-dependent below colour
            // at the bottom of the screen (terrain is drawn over most of it).
            skyBand(skyStaging, hz, -1f, fogColor, belowColor);
        }
        skyStaging.flip();
        int bytes = skyStaging.remaining();
        if (bytes == 0) return;
        encoder.writeToBuffer(new GpuBufferSlice(skyVbo, 0L, bytes), skyStaging);
        skyVertexCount = bytes / SKY_VERTEX_BYTES;
    }

    private static void drawSky(RenderPass pass) {
        if (SKY_PIPELINE == null || skyVertexCount == 0) return;
        pass.setPipeline(SKY_PIPELINE);
        BlazeCompat.setVertexBuffer(pass, 0, skyVbo);
        BlazeCompat.drawNonIndexed(pass, skyVertexCount);
    }
    private static void beginFog(float start, float end, int color) {
        if (fogRing == null) {
            fogRing = new MappableRingBuffer(() -> "voxymap_3d_fog", 130, FogRenderer.FOG_UBO_SIZE);
        }
        if (fogStaging == null) {
            fogStaging = ByteBuffer.allocateDirect(FogRenderer.FOG_UBO_SIZE).order(ByteOrder.nativeOrder());
        }
        fogStaging.clear();
        Std140Builder.intoBuffer(fogStaging)
                .putVec4(new Vector4f(((color >> 16) & 0xFF) / 255f,
                        ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, 1f))
                .putFloat(start).putFloat(end)
                .putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE)
                .putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE);
        fogStaging.flip();
        //? if >=26.2 {
        GpuBuffer buffer = fogRing.currentBuffer();
        try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
            view.data().clear();
            view.data().put(fogStaging);
        }
        fogPrevious = RenderSystem.getShaderFog();
        fogSlice = buffer.slice(0L, FogRenderer.FOG_UBO_SIZE);
        RenderSystem.setShaderFog(fogSlice);
        //?} else {
        /*fogSlice = null;*/
        //?}

    }

    private static void endFog() {
        if (fogPrevious != null) {
            RenderSystem.setShaderFog(fogPrevious);
            fogPrevious = null;
        }
        if (fogRing != null) fogRing.rotate();
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
        boolean skyFog = skyFogActive();
        int skyColor = CLEAR_SKY_COLOR;
        int horizonColor = CLEAR_FOG_COLOR;
        float fogStart = 0f;
        float fogEnd = 0f;
        if (skyFog) {
            // Fog is horizontal only; scale the distances with the camera height/distance so
            // zooming out never hides the terrain edge behind fog.
            float camHeight = Math.max(1f, (float) (cam.eyeY() - cam.targetY));
            float fogScale = Math.max(cam.distance, camHeight * 1.6f);
            fogEnd = Math.max(1500f, Math.min(20000f, fogScale * 5f));
            fogStart = fogEnd * 0.35f;
        }
        overlayIndexCount = 0;
        // One encoder for the whole 3D frame: the overlay upload, the terrain pass and the
        // ring fence all ride in it, so the mod does not add a submit of its own.
        var frameEncoder = RenderSystem.getDevice().createCommandEncoder();
        boolean drawOverlay = prepareOverlay(frameEncoder, screen);
        ensureTrackMesh();

        boolean fogStarted = false;
        RenderSystem.backupProjectionMatrix();
        try {
            RenderSystem.setProjectionMatrix(projection.getBuffer(proj),
                    com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(view, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
            if (skyFog) {
                // The sky buffer upload and the fog-buffer map must happen BEFORE the render pass:
                // writing/mapping GPU buffers while a pass is open is illegal and aborted the pass.
                try {
                    prepareSky(frameEncoder, fov, cam, skyColor, horizonColor);
                    beginFog(fogStart, fogEnd, horizonColor);
                    fogStarted = true;
                } catch (Throwable t) {
                    System.out.println("[voxymap] 3d sky/fog setup failed, continuing without it: " + t);
                }
            }
            try (RenderPass pass = BlazeCompat.beginTerrainPass(frameEncoder, target, depthView)) {
                if (fogStarted) {
                    drawSky(pass);
                }
                pass.setPipeline(skyFog ? terrain3dPipeline() : PIPELINE);
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
                drawTrack(pass, transforms);
            }
            // The fence is taken after the draws, so the next reuse of this slot waits for them.
            if (overlaySlotInUse >= 0) {
                overlayFences[overlaySlotInUse] = BlazeCompat.createFence(frameEncoder);
                overlaySlotInUse = -1;
            }
        } finally {
            if (fogStarted) {
                endFog();
            }
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
        out.add("adv3d: " + (VoxyMapConfig.ADVANCED_3D_RENDER.get() ? "on" : "off")
                + "  supported=" + BlazeCompat.advanced3dSupported());
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
        if (skyVbo != null) {
            skyVbo.close();
            skyVbo = null;
        }
        skyVertexCount = 0;
        if (fogRing != null) {
            fogRing.close();
            fogRing = null;
        }
        fogSlice = null;
        fogPrevious = null;
        // overlay/arrow ring: close every slot and its fence
        for (int slot = 0; slot < OVERLAY_SLOTS; slot++) {
            BlazeCompat.awaitAndCloseFence(overlayFences[slot]);
            overlayFences[slot] = null;
            if (overlayVbos[slot] != null) {
                overlayVbos[slot].close();
                overlayVbos[slot] = null;
            }
            if (overlayIbos[slot] != null) {
                overlayIbos[slot].close();
                overlayIbos[slot] = null;
            }
            if (arrowVbos[slot] != null) {
                arrowVbos[slot].close();
                arrowVbos[slot] = null;
            }
            if (arrowIbos[slot] != null) {
                arrowIbos[slot].close();
                arrowIbos[slot] = null;
            }
        }
        overlaySlot = 0;
        overlaySlotInUse = -1;
        overlayIndexCount = 0;
        if (trackVbo != null) {
            trackVbo.close();
            trackVbo = null;
        }
        if (trackIbo != null) {
            trackIbo.close();
            trackIbo = null;
        }
        trackIndexCount = 0;
        trackBuiltVersion = -1;
        trackBuiltCount = 0;
        trackBuiltMs = 0L;
        trackStagingVb = null;
        trackStagingIb = null;
        arrowIndexCount = 0;
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