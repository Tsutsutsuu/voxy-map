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
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
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
    /**
     * Player marker line: depth test off entirely (a UI annotation, not terrain - the "buried part
     * is grey" rule is decided from map data). 26.2 expressed this as a DEBUG_FILLED_SNIPPET with
     * {@code CompareOp.ALWAYS_PASS}; the compat pipeline carries the same flags.
     */
    private static final RenderPipeline MARKER_PIPELINE = RenderPipelines.MARKER;
    /** World-space textured quad for the player arrow (vanilla {@code position_tex}). */
    private static final RenderPipeline ARROW_PIPELINE = RenderPipelines.ARROW;
    // ---- advanced 3D look: procedural screen-space sky + horizontal fog (26.2 parity) ----------
    /** Fixed clear-noon overworld colours: no weather, daylight or biome variation. */
    private static final int CLEAR_SKY_COLOR = 0xFF79A7FF;
    private static final int CLEAR_FOG_COLOR = 0xFFC0D8FF;
    private static final int SKY_BLACK = 0xFF050508;
    /** Above this camera height (with a 500 block fade) fog is off and the sky goes black. */
    private static final float HIGH_CAMERA_START = 4000f;
    private static final float HIGH_CAMERA_FADE = 500f;
    /** POSITION_COLOR vertex: 3 floats + RGBA, i.e. the same 16-byte stride as the overlay. */
    private static final int SKY_VERTEX_BYTES = 16;
    private static final int SKY_MAX_VERTS = 12;
    /** Draw order of the sky triangles: sequential indices, so indexCount == vertexCount. */
    private static final int[] SKY_INDICES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static GpuBuffer skyVbo;
    private static GpuBuffer skyIbo;
    private static ByteBuffer skyStaging;
    private static int skyVertexCount;

    private static final int OVERLAY_SEGMENTS = 64;
    /**
     * The ribbon + marker line + arrow are rebuilt every frame, so they live in a 3-slot ring.
     * Overwriting the buffer the GPU is still reading stalls the driver on GL (undefined on a
     * Vulkan backend); 1.21.1 has no fence API, so the three frames of slack are the protection.
     */
    private static final int OVERLAY_SLOTS = 3;
    private static final GpuBuffer[] overlayVbos = new GpuBuffer[OVERLAY_SLOTS];
    private static final GpuBuffer[] overlayIbos = new GpuBuffer[OVERLAY_SLOTS];
    private static int overlaySlot;
    private static int overlaySlotInUse = -1;
    /** Reused staging memory: nothing may allocate in a per-frame path. */
    private static ByteBuffer overlayStagingVb;
    private static ByteBuffer overlayStagingIb;
    private static ByteBuffer arrowStagingVb;
    private static ByteBuffer arrowStagingIb;
    /** Ribbon indices in the shared POSITION_COLOR buffer. */
    private static int overlayIndexCount;
    /** Marker-line index range inside the same buffer (drawn with {@link #MARKER_PIPELINE}). */
    private static int markerFirstIndex;
    private static int markerIndexCount;
    /** Arrow geometry lives in its own POSITION_TEX buffers (20-byte stride vs 16). */
    private static final int ARROW_VERTS = 4;
    private static final int ARROW_INDICES = 6;
    private static final GpuBuffer[] arrowVbos = new GpuBuffer[OVERLAY_SLOTS];
    private static final GpuBuffer[] arrowIbos = new GpuBuffer[OVERLAY_SLOTS];
    private static int arrowIndexCount;
    /** Wrapped GL id of the arrow PNG; re-resolved when the configured style changes. */
    private static GpuTexture arrowTexture;
    private static GpuTextureView arrowView;
    private static int arrowTextureKey = -1;
    /** Recorded track ribbon (POSITION_COLOR, same pipeline as the distance ribbon). */
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
    private static final double TRACK_HALF_WIDTH = 1.1;
    private static final double TRACK_LIFT = 0.15;
    /**
     * Upper bound on marker-line quads per frame (solid head + dashed cells). The shared overlay
     * buffer is sized from it, so the append loops must never exceed it.
     */
    private static final int MARKER_MAX_SEGMENTS = 96;
    /** Static max sizes of the overlay ring buffers (ribbon + marker line). */
    private static final int OVERLAY_MAX_VERTS =
            4 * (OVERLAY_SEGMENTS + 1) * 2 + MARKER_MAX_SEGMENTS * 4;
    private static final int OVERLAY_MAX_INDICES =
            4 * OVERLAY_SEGMENTS * 6 + MARKER_MAX_SEGMENTS * 6;
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
        stamp = stamp * 31L + (VoxyMapConfig.GPU_TERRAIN_VOXEL.get() ? 1L : 0L);
        stamp = stamp * 31L + (VoxyMapConfig.ADVANCED_3D_RENDER.get() ? 1L : 0L);
        stamp = stamp * 31L + (VoxyMapConfig.IGNORE_PLANT_HEIGHT.get() ? 1L : 0L);
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
     * Experimental voxel mesher (1.21.1 port of the 26.2 implementation): one flat top per cell plus
     * vertical side walls wherever a neighbour is lower. Tops are greedily merged over equal-height,
     * equal-AO cells; walls use a direction-dependent face factor and a vertical gradient, which is
     * what gives the blocks-style look its "sun from the north-west" shading.
     */
    private static BuildResult buildVoxel(int gen, long key, long stamp, Slot slot, int step,
                                          LiveTileStore.LiveTile tile, long[] pixels,
                                          LiveTileStore live, int pb, int sea, double vscale,
                                          long buildStart) {
        final int grid = LiveTileStore.TILE_PIXELS / step;
        final int cells = grid * grid;
        final int texMax = LiveTileStore.TILE_PIXELS - 1;
        final int sx = (slot.index % COLS) * CELL;
        final int sy = (slot.index / COLS) * CELL;
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
                top = SurfaceClassTable.INSTANCE.adjustedTop(packed, top);
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
            java.util.Arrays.fill(vao, 0, cells * 4, (byte) 0xFF);
            java.util.Arrays.fill(aoCell, 0, cells, 255);
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
        }
        // ---- flat tops, greedily merged over equal-height cells ----
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
        // ---- vertical side walls (every column, water included: its stored surface can be above or
        // below sea level) ----
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
                wallColors(s, aoCell[i], 0.74f, advancedLook); // east: shadow side
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
                wallColors(s, aoCell[i], 0.92f, advancedLook); // west: lit side
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
                wallColors(s, aoCell[i], 0.76f, advancedLook); // south: shadow side
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
                wallColors(s, aoCell[i], 0.90f, advancedLook); // north: lit side
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
                wallColors(s, aoCell[i], 0.605f, advancedLook); // skirt
                // On the west/north edges the skirt is always emitted, but the regular wall loop may
                // already have drawn a wall here when the neighbouring tile is lower. Drawing both
                // puts two coplanar surfaces at the same edge (z-fighting). Skip the skirt where the
                // wall already covers the height difference.
                boolean westWall = false;
                boolean northWall = false;
                if (west) {
                    float nwy = voxNeighborY(tile, pixels, live, gx * step - step, gz * step, sea, vscale, texMax);
                    westWall = !Float.isNaN(nwy) && nwy < y;
                }
                if (north) {
                    float nnz = voxNeighborY(tile, pixels, live, gx * step, gz * step - step, sea, vscale, texMax);
                    northWall = !Float.isNaN(nnz) && nnz < y;
                }
                if (west && !westWall) {
                    voxWallX(s, x0, low, y, z0, z1, u, v, v, true);
                }
                if (east) {
                    voxWallX(s, x1, low, y, z0, z1, u, v, v, true);
                }
                if (north && !northWall) {
                    voxWallZ(s, z0, low, y, x0, x1, v, u, u, true);
                }
                if (south) {
                    voxWallZ(s, z1, low, y, x0, x1, v, u, u, true);
                }
                minY = Math.min(minY, low);
            }
        }
        double cx = tile.minWX + LiveTileStore.TILE_PIXELS * pb * 0.5;
        double cz = tile.minWZ + LiveTileStore.TILE_PIXELS * pb * 0.5;
        if (s.ii == 0) {
            return new BuildResult(gen, key, stamp, slot, null, null, 0, step,
                    cx, cz, 0.0, 0.0, System.nanoTime() - buildStart);
        }
        // 1.21.1 vertex layout: Position, UV0, Color (POSITION_TEX_COLOR); no UV2/Normal.
        ByteBufferBuilder alloc = new ByteBufferBuilder(Math.max(1024, s.vi * 24));
        BufferBuilder bb = new BufferBuilder(alloc, VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < s.vi; i++) {
            bb.addVertex(s.px[i], s.py[i], s.pz[i])
                    .setColor(s.col[i], s.col[i], s.col[i], 255)
                    .setUv(s.pu[i], s.pv[i]);
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
     * on the west/north sides work exactly like interior ones.
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
        top = SurfaceClassTable.INSTANCE.adjustedTop(packed, top);
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

    /** AO byte for an occlusion value (0..1); shared by the smooth-vertex AO pass. */
    private static int aoColour(float occ, int strength) {
        float amount = (strength / 100f) * 0.75f * (occ / (occ + 0.6f));
        return Math.max(40, Math.round(255f * (1f - amount)));
    }

    /**
     * Wall colours: a direction-dependent face factor (sun comes from the north-west, so the west
     * and north walls are the lit sides) plus a vertical gradient (bottom ~18% darker than the top).
     */
    private static void wallColors(VoxelScratch s, int ao, float factor, boolean advancedLook) {
        if (!advancedLook) {
            s.curAoLow = 255;
            s.curAoHigh = 255;
            return;
        }
        int high = Math.max(40, Math.round(ao * factor));
        s.curAoHigh = high;
        s.curAoLow = Math.max(40, Math.round(high * 0.82f));
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
        s.curAo = s.curAoLow;
        voxAdd(s, xEdge, yLow, z0, u, v0);
        voxAdd(s, xEdge, yLow, z1, u, v1);
        s.curAo = s.curAoHigh;
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
        s.curAo = s.curAoLow;
        voxAdd(s, x0, yLow, zEdge, u0, v);
        voxAdd(s, x1, yLow, zEdge, u1, v);
        s.curAo = s.curAoHigh;
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
        int curAoLow = 255;
        int curAoHigh = 255;

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
            s.curAoLow = 255;
            s.curAoHigh = 255;
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
                top = SurfaceClassTable.INSTANCE.adjustedTop(packed, top);
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
        // from py[], only the tile border samples across the seam once. Calling voxNeighborY four
        // times for every vertex would be about 260k lookups for a 256-cell near tile.
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
        // 1.21.1 has no POSITION_COLOR_TEX; POSITION_TEX_COLOR is the same data in a different
        // element order (Position, UV0, Color). UV2/Normal are gone: the shader is full-bright.
        VertexFormat format = DefaultVertexFormat.POSITION_TEX_COLOR;
        ByteBufferBuilder alloc = new ByteBufferBuilder(Math.max(1024, vi * format.getVertexSize()));
        BufferBuilder bb = new BufferBuilder(alloc, VertexFormat.Mode.TRIANGLES, format);
        for (int i = 0; i < vi; i++) {
            bb.addVertex(px[i], py[i], pz[i])
                    .setColor(col[i], col[i], col[i], 255)
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
        return uploadVertices(encoder, label, data, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static GpuBuffer uploadVertices(CommandEncoder encoder, String label, ByteBuffer data,
                                            VertexFormat format) {
        int size = data.remaining();
        GpuBuffer buffer = GpuBuffer.createVertex(label, size, format);
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
    private static boolean prepareOverlay(MapScreen screen) {
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
        // next ring slot (no fence API on 1.21.1: three frames of slack is the protection)
        int slot = overlaySlot;
        overlaySlot = (overlaySlot + 1) % OVERLAY_SLOTS;
        if (overlayVbos[slot] == null) {
            overlayVbos[slot] = GpuBuffer.createVertex("voxymap_gpu3d_overlay_v" + slot, OVERLAY_MAX_VERTS * 16,
                    DefaultVertexFormat.POSITION_COLOR);
            overlayIbos[slot] = GpuBuffer.createIndex("voxymap_gpu3d_overlay_i" + slot, OVERLAY_MAX_INDICES * 4);
        }
        if (overlayStagingVb == null) {
            overlayStagingVb = ByteBuffer.allocateDirect(OVERLAY_MAX_VERTS * 16).order(ByteOrder.nativeOrder());
            overlayStagingIb = ByteBuffer.allocateDirect(OVERLAY_MAX_INDICES * 4).order(ByteOrder.nativeOrder());
            arrowStagingVb = ByteBuffer.allocateDirect(ARROW_VERTS * 20).order(ByteOrder.nativeOrder());
            arrowStagingIb = ByteBuffer.allocateDirect(ARROW_INDICES * 4).order(ByteOrder.nativeOrder());
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
                    double y = data.heightAt((int) Math.floor(wx), (int) Math.floor(wz)) * vscale + 1.05;
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
                    arrowVbos[slot] = GpuBuffer.createVertex("voxymap_marker_arrow_v" + slot, ARROW_VERTS * 20,
                            DefaultVertexFormat.POSITION_TEX);
                    arrowIbos[slot] = GpuBuffer.createIndex("voxymap_marker_arrow_i" + slot, ARROW_INDICES * 4);
                }
                var arrowEncoder = CommandEncoder.create();
                arrowEncoder.writeToBuffer(new GpuBufferSlice(arrowVbos[slot], 0L, avb.remaining()), avb);
                arrowEncoder.writeToBuffer(new GpuBufferSlice(arrowIbos[slot], 0L, aib.remaining()), aib);
                arrowEncoder.submit();
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
        var encoder = CommandEncoder.create();
        encoder.writeToBuffer(new GpuBufferSlice(overlayVbos[slot], 0L, vb.remaining()), vb);
        encoder.writeToBuffer(new GpuBufferSlice(overlayIbos[slot], 0L, ib.remaining()), ib);
        encoder.submit();
        overlayIndexCount = ribbonIndexCount;
        markerFirstIndex = markerFirst;
        markerIndexCount = ii - markerFirst;
        return true;
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

    private static void drawOverlay(RenderPass pass) {
        if (overlaySlotInUse < 0) return;
        GpuBuffer overlayVb = overlayVbos[overlaySlotInUse];
        GpuBuffer overlayIb = overlayIbos[overlaySlotInUse];
        if (overlayVb != null && overlayIb != null) {
            if (overlayIndexCount > 0) {
                pass.setPipeline(OVERLAY_PIPELINE);
                pass.setVertexBuffer(0, overlayVb);
                pass.setIndexBuffer(overlayIb);
                pass.drawIndexed(0, overlayIndexCount);
            }
            if (markerIndexCount > 0) {
                // no depth test: the marker line is a UI annotation, not terrain
                pass.setPipeline(MARKER_PIPELINE);
                pass.setVertexBuffer(0, overlayVb);
                pass.setIndexBuffer(overlayIb);
                pass.drawIndexed(markerFirstIndex, markerIndexCount);
            }
        }
        if (arrowIndexCount > 0 && arrowVbos[overlaySlotInUse] != null && arrowIbos[overlaySlotInUse] != null) {
            GpuTextureView texture = arrowTextureView();
            if (texture != null) {
                pass.setPipeline(ARROW_PIPELINE);
                // 1.21.1 has no sampler objects; the PNG is already NEAREST + clamp.
                pass.bindTexture("Sampler0", texture, null);
                pass.setVertexBuffer(0, arrowVbos[overlaySlotInUse]);
                pass.setIndexBuffer(arrowIbos[overlaySlotInUse]);
                pass.drawIndexed(0, arrowIndexCount);
            }
        }
    }

    /**
     * The arrow PNG wrapped for the pass. The id belongs to vanilla's TextureManager, so the wrapper
     * is non-owning; it is rebuilt only when the resolved texture changes (arrow style switch).
     */
    private static GpuTextureView arrowTextureView() {
        ResourceLocation location = MapScreen.arrowTexture();
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (texture == null) return null;
        int id = texture.getId();
        if (id <= 0) return null;
        if (id == arrowTextureKey && arrowView != null) return arrowView;
        if (arrowView != null) {
            arrowView.close();
            arrowView = null;
        }
        if (arrowTexture != null) {
            arrowTexture.close();
            arrowTexture = null;
        }
        arrowTexture = GpuTexture.wrapExisting("voxymap_marker_arrow", id, 32, 32);
        arrowView = arrowTexture.createView();
        arrowTextureKey = id;
        return arrowView;
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
            if (y <= data.heightAt(bx, bz) * vscale) return true;
        }
        return false;
    }

    /**
     * Player arrow: a horizontal textured quad on the marker height, sampled straight from the
     * arrow PNG by the GPU.
     *
     * <p>This replaces 0.4.0's CPU rasteriser that inverse-mapped the quad per pixel into a 128²
     * {@code NativeImage} and uploaded it as a DynamicTexture every time the projection changed.</p>
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
        var encoder = CommandEncoder.create();
        trackVbo = uploadVertices(encoder, "voxymap_track_v", vb, DefaultVertexFormat.POSITION_COLOR);
        trackIbo = uploadIndices(encoder, "voxymap_track_i", ib);
        encoder.submit();
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

    private static void drawTrack(RenderPass pass) {
        if (trackIndexCount <= 0 || trackVbo == null || trackIbo == null) return;
        pass.setPipeline(OVERLAY_PIPELINE);
        pass.setVertexBuffer(0, trackVbo);
        pass.setIndexBuffer(trackIbo);
        pass.drawIndexed(0, trackIndexCount);
    }

    private static void retireTrackBuffers() {
        retireBuffer(trackVbo);
        trackVbo = null;
        retireBuffer(trackIbo);
        trackIbo = null;
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

    // ------------------------------------------------------------ blocks-style sky / fog (advanced 3D)

    /**
     * The sky and the horizontal fog only exist for the experimental advanced renderer and the
     * blocks style (same gate as the 26.2 build, where {@code advanced3dSupported()} additionally
     * gates it off for 26.1.2; 1.21.1 implements the shaded look with its own core shaders).
     */
    private static boolean skyFogActive() {
        return VoxyMapConfig.ADVANCED_3D_RENDER.get() && "blocks".equals(MapData.INSTANCE.getStyle());
    }

    private static int lerpColor(int from, int to, float t) {
        int r0 = (from >> 16) & 0xFF, g0 = (from >> 8) & 0xFF, b0 = from & 0xFF;
        int r1 = (to >> 16) & 0xFF, g1 = (to >> 8) & 0xFF, b1 = to & 0xFF;
        int r = Math.round(r0 + (r1 - r0) * t);
        int g = Math.round(g0 + (g1 - g0) * t);
        int b = Math.round(b0 + (b1 - b0) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

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
     * 1.21.1 has no deferred encoder: the 192-byte upload executes immediately, before the pass.
     */
    private static void prepareSky(int skyColor, int fogColor, Map3D.Camera cam, float fov) {
        skyVertexCount = 0;
        if (skyVbo == null) {
            skyVbo = GpuBuffer.createVertex("voxymap_3d_sky", SKY_MAX_VERTS * SKY_VERTEX_BYTES,
                    DefaultVertexFormat.POSITION_COLOR);
            skyIbo = GpuBuffer.createIndex("voxymap_3d_sky_idx", SKY_MAX_VERTS * Integer.BYTES);
            ByteBuffer idx = ByteBuffer.allocateDirect(SKY_MAX_VERTS * Integer.BYTES)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < SKY_MAX_VERTS; i++) idx.putInt(SKY_INDICES[i]);
            idx.flip();
            CommandEncoder.create().writeToBuffer(new GpuBufferSlice(skyIbo, 0L, idx.remaining()), idx);
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
        CommandEncoder.create().writeToBuffer(new GpuBufferSlice(skyVbo, 0L, bytes), skyStaging);
        skyVertexCount = bytes / SKY_VERTEX_BYTES;
    }

    /** Draws the pre-uploaded sky band; the pass switches back to the terrain pipeline afterwards. */
    private static void drawSky(RenderPass pass) {
        if (skyVbo == null || skyIbo == null || skyVertexCount == 0) return;
        pass.setPipeline(RenderPipelines.SKY);
        pass.setVertexBuffer(0, skyVbo);
        pass.setIndexBuffer(skyIbo);
        pass.drawIndexed(0, skyVertexCount);
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
        // ---- advanced 3D look: fixed sky colours + height-based horizontal fog ----
        boolean skyFog = skyFogActive();
        int skyColor = CLEAR_SKY_COLOR;
        int horizonColor = CLEAR_FOG_COLOR;
        float fogStart = 0f;
        float fogEnd = 0f;
        float fogStrength = 0f;
        if (skyFog) {
            // Camera height above the terrain: close to the ground = more immersive, so fog starts
            // closer; high camera = fog starts farther away.
            double groundY = cam.targetY;
            MapData map = MapData.INSTANCE;
            if (map.isReady()) {
                groundY = map.surfaceYAt((int) Math.round(cam.eyeX()), (int) Math.round(cam.eyeZ()));
            }
            float camHeight = Math.max(1f, (float) (cam.eyeY() - groundY));
            // High camera: over a 500 block band above 4000 blocks, turn the fog off and fade the
            // whole sky (above and below the horizon) to black.
            float highFade = (camHeight - HIGH_CAMERA_START) / HIGH_CAMERA_FADE;
            highFade = Math.max(0f, Math.min(1f, highFade));
            highFade = highFade * highFade * (3f - 2f * highFade);
            skyColor = lerpColor(CLEAR_SKY_COLOR, SKY_BLACK, highFade);
            horizonColor = lerpColor(CLEAR_FOG_COLOR, SKY_BLACK, highFade);
            // Fog end is FIXED (independent of camera distance/target): the map always keeps the
            // same far fading range. Only the start changes.
            float lookDownDeg = (float) Math.toDegrees(-cam.pitch);
            // Vertical-down gate: fully off at the top-down camera, fading in over ~7 degrees of
            // tilt so rotating up does not pop.
            float gate = (85f - lookDownDeg) / 7f;
            gate = Math.max(0f, Math.min(1f, gate));
            fogStrength = gate * gate * (3f - 2f * gate) * (1f - highFade);
            fogEnd = Math.max(1000f, Math.min(60000f, VoxyMapConfig.FOG_DISTANCE_3D.get()));
            fogStart = Math.max(600f, Math.min(fogEnd * 0.85f, camHeight * 2.0f));
        }
        depthWidth = target.width;
        depthHeight = target.height;
        overlayIndexCount = 0;
        boolean drawOverlay = prepareOverlay(screen);
        ensureTrackMesh();

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
        // Sky and fog are set up before the pass opens: the compat layer executes GL immediately,
        // and the fog uniforms are read by setDefaultUniforms when the terrain pipeline is applied.
        // The fog values are always written when the advanced look is on (alpha 0 = no fog), so the
        // shader never picks up the world renderer's leftover fog state.
        float oldFogStart = RenderSystem.getShaderFogStart();
        float oldFogEnd = RenderSystem.getShaderFogEnd();
        float[] oldFogColor = RenderSystem.getShaderFogColor().clone();
        boolean skyDrawn = false;
        boolean fogSet = false;
        if (skyFog) {
            try {
                prepareSky(skyColor, horizonColor, cam, fov);
                skyDrawn = skyVertexCount > 0;
                RenderSystem.setShaderFogStart(fogStart);
                RenderSystem.setShaderFogEnd(fogEnd);
                RenderSystem.setShaderFogColor(((horizonColor >> 16) & 0xFF) / 255f,
                        ((horizonColor >> 8) & 0xFF) / 255f, (horizonColor & 0xFF) / 255f, fogStrength);
                fogSet = true;
            } catch (Throwable t) {
                System.out.println("[voxymap] 3d sky/fog setup failed, continuing without it: " + t);
                skyDrawn = false;
            }
        }
        RenderPipeline terrainPipeline = skyFog ? RenderPipelines.TERRAIN3D : PIPELINE;
        try (RenderPass pass = new RenderPass(terrainPipeline, view, proj)) {
            if (skyDrawn) {
                // Sky first (no depth test/write), then the terrain over it.
                drawSky(pass);
                pass.setPipeline(terrainPipeline);
            }
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
            drawTrack(pass);
        } finally {
            if (fogSet) {
                RenderSystem.setShaderFogStart(oldFogStart);
                RenderSystem.setShaderFogEnd(oldFogEnd);
                RenderSystem.setShaderFogColor(oldFogColor[0], oldFogColor[1], oldFogColor[2], oldFogColor[3]);
            }
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
        out.add("adv3d: " + (VoxyMapConfig.ADVANCED_3D_RENDER.get() ? "on" : "off")
                + "  voxel=" + (VoxyMapConfig.GPU_TERRAIN_VOXEL.get() ? "on" : "off")
                + "  ao=" + VoxyMapConfig.ADVANCED_3D_AO.get());
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
        if (skyIbo != null) {
            skyIbo.close();
            skyIbo = null;
        }
        skyVertexCount = 0;
        for (int slot = 0; slot < OVERLAY_SLOTS; slot++) {
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
        markerFirstIndex = 0;
        markerIndexCount = 0;
        arrowIndexCount = 0;
        if (arrowView != null) {
            arrowView.close();
            arrowView = null;
        }
        if (arrowTexture != null) {
            arrowTexture.close();     // non-owning wrapper: never deletes vanilla's GL texture
            arrowTexture = null;
        }
        arrowTextureKey = -1;
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
        depthWidth = depthHeight = 0;
    }
}

