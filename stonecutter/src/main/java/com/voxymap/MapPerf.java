package com.voxymap;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, allocation-free performance counters for the map.
 * Counters are always collected; only formatting creates objects when the debug panel is open.
 */
public final class MapPerf {
    public static final MapPerf INSTANCE = new MapPerf();

    private MapPerf() {
    }

    // 2D frames / tiles
    public final LongAdder frames2d = new LongAdder();
    public final LongAdder frame2dNanos = new LongAdder();
    public final LongAdder tilesDrawn = new LongAdder();
    public volatile int lastVisibleTiles;
    public volatile int lastResidentTiles;

    // tile pipeline
    public final LongAdder tileObjects = new LongAdder();
    public final LongAdder cacheHits = new LongAdder();
    public final LongAdder cacheMisses = new LongAdder();
    public final LongAdder sourceRequests = new LongAdder();
    public final LongAdder sourceHits = new LongAdder();
    public final LongAdder sourceEmpty = new LongAdder();
    public final LongAdder evictions = new LongAdder();

    // colour / texture
    public final LongAdder colorRequests = new LongAdder();
    public final LongAdder colorDone = new LongAdder();
    public final LongAdder colorStale = new LongAdder();
    public final LongAdder textureBuilds = new LongAdder();
    public final LongAdder gpuTextureReleases = new LongAdder();
    public final LongAdder textureUploadNanos = new LongAdder();

    // live events
    public final LongAdder liveSections = new LongAdder();
    public final LongAdder liveClosedSkipped = new LongAdder();
    public final LongAdder resyncs = new LongAdder();

    // 3D
    public final LongAdder meshBuilds = new LongAdder();
    public final LongAdder tileMeshBuilds = new LongAdder();
    public final LongAdder meshNanos = new LongAdder();
    public volatile int meshVerts;
    public volatile int meshTris;
    public volatile int tileMeshCount;
    public volatile int tileMeshVerts;
    public volatile int tileMeshTris;
    public final LongAdder colorFrameBuilds = new LongAdder();
    public final LongAdder colorFrameNanos = new LongAdder();
    public volatile int colorFramePixels;
    public final LongAdder rasterFrames = new LongAdder();
    public final LongAdder rasterNanos = new LongAdder();
    public final LongAdder rasterTris = new LongAdder();
    public final LongAdder fxaaFrames = new LongAdder();
    public final LongAdder fxaaNanos = new LongAdder();

    private int frameTileDraws;
    private long frameStartNs;

    public void beginFrame2d() {
        frameStartNs = System.nanoTime();
        frameTileDraws = 0;
    }

    public void tileDrawn() {
        frameTileDraws++;
        tilesDrawn.increment();
    }

    public void endFrame2d(int visibleTiles, int residentTiles) {
        lastVisibleTiles = visibleTiles;
        lastResidentTiles = residentTiles;
        frames2d.increment();
        if (frameStartNs != 0L) frame2dNanos.add(System.nanoTime() - frameStartNs);
    }

    public void meshBuilt(long nanos, int verts, int tris) {
        meshBuilds.increment();
        meshNanos.add(Math.max(0L, nanos));
        meshVerts = verts;
        meshTris = tris;
    }

    public void colorFrameBuilt(long nanos, int pixels) {
        colorFrameBuilds.increment();
        colorFrameNanos.add(Math.max(0L, nanos));
        colorFramePixels = pixels;
    }

    public void rasterFrame(long nanos, int tris) {
        rasterFrames.increment();
        rasterNanos.add(Math.max(0L, nanos));
        rasterTris.add(Math.max(0, tris));
    }

    public void fxaaFrame(long nanos) {
        fxaaFrames.increment();
        fxaaNanos.add(Math.max(0L, nanos));
    }

    private static double msPer(LongAdder total, LongAdder count) {
        long n = count.sum();
        return n == 0 ? 0.0 : total.sum() / 1_000_000.0 / n;
    }

    public List<String> lines(MapData data) {
        List<String> out = new ArrayList<>();
        if (data != null && data.isTile2DMode()) {
            double frameMs = msPer(frame2dNanos, frames2d);
            out.add(String.format(Locale.ROOT,
                    "p2d %.2fms vis%d res%d | cache %d/%d src %d(%d/%d) ev%d",
                    frameMs, lastVisibleTiles, lastResidentTiles,
                    cacheHits.sum(), cacheMisses.sum(), sourceRequests.sum(),
                    sourceHits.sum(), sourceEmpty.sum(), evictions.sum()));
            out.add(String.format(Locale.ROOT,
                    "pcol %d/%d/%d tex%d | live%d skip%d cal%d | tiles%d",
                    colorRequests.sum(), colorDone.sum(), colorStale.sum(), textureBuilds.sum(),
                    liveSections.sum(), liveClosedSkipped.sum(), resyncs.sum(),
                    tileObjects.sum()));
        }
        if (meshBuilds.sum() > 0 || colorFrameBuilds.sum() > 0 || rasterFrames.sum() > 0) {
            out.add(String.format(Locale.ROOT,
                    "p3d mesh%d %.0fms %dk/%dk | cf%d %.1fms %dMpx | ras%d %.1fms fx%d | tm%d b%d %dk/%dk",
                    meshBuilds.sum(), msPer(meshNanos, meshBuilds),
                    meshVerts / 1024, meshTris / 1024,
                    colorFrameBuilds.sum(), msPer(colorFrameNanos, colorFrameBuilds),
                    colorFramePixels / 1_000_000,
                    rasterFrames.sum(), msPer(rasterNanos, rasterFrames), fxaaFrames.sum(),
                    tileMeshCount, tileMeshBuilds.sum(),
                    tileMeshVerts / 1024, tileMeshTris / 1024));
        }
        if (data != null) out.add("pmem " + data.perfMemoryLine());
        if (data != null) {
            out.add(String.format(Locale.ROOT, "pgpu tex=%d %d/%dMB rel=%d",
                    data.tileTextureCount(), data.tileTextureBytes() >> 20,
                    VoxyMapConfig.GPU_TEXTURE_BUDGET_MB.get(), gpuTextureReleases.sum()));
            out.add(String.format(Locale.ROOT, "pdisk files=%d %d/%dMB",
                    data.tileDiskFiles(), data.tileDiskBytes() >> 20,
                    VoxyMapConfig.DISK_CACHE_MAX_MB.get()));
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out.add(String.format(Locale.ROOT, "pheap %d/%dMB",
                heap.getUsed() >> 20, heap.getMax() >> 20));
        return out;
    }
}
