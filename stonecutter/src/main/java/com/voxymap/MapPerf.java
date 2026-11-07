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
    public final LongAdder textureUploadNanos = new LongAdder();

    // live events
    public final LongAdder liveSections = new LongAdder();
    public final LongAdder liveClosedSkipped = new LongAdder();
    public final LongAdder resyncs = new LongAdder();

    // 3D
    public final LongAdder meshBuilds = new LongAdder();
    public final LongAdder meshNanos = new LongAdder();
    public volatile int meshVerts;
    public volatile int meshTris;
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
                    "perf 2d: frames=%d avg=%.2fms tiles=%d vis=%d resident=%d",
                    frames2d.sum(), frameMs, frameTileDraws, lastVisibleTiles, lastResidentTiles));
            out.add(String.format(Locale.ROOT,
                    "perf tile: cache(hit=%d miss=%d) source(req=%d hit=%d empty=%d) evict=%d",
                    cacheHits.sum(), cacheMisses.sum(), sourceRequests.sum(),
                    sourceHits.sum(), sourceEmpty.sum(), evictions.sum()));
            long colors = colorRequests.sum();
            out.add(String.format(Locale.ROOT,
                    "perf color: req=%d done=%d stale=%d textures=%d",
                    colors, colorDone.sum(), colorStale.sum(), textureBuilds.sum()));
            out.add(String.format(Locale.ROOT,
                    "perf live: sections=%d closedSkipped=%d resyncs=%d",
                    liveSections.sum(), liveClosedSkipped.sum(), resyncs.sum()));
        }
        double meshMs = msPer(meshNanos, meshBuilds);
        if (meshBuilds.sum() > 0) {
            out.add(String.format(Locale.ROOT,
                    "perf 3d mesh: builds=%d avg=%.1fms verts=%d tris=%d",
                    meshBuilds.sum(), meshMs, meshVerts, meshTris));
        }
        double cfMs = msPer(colorFrameNanos, colorFrameBuilds);
        if (colorFrameBuilds.sum() > 0) {
            out.add(String.format(Locale.ROOT,
                    "perf 3d colorFrame: builds=%d avg=%.1fms px=%d",
                    colorFrameBuilds.sum(), cfMs, colorFramePixels));
        }
        double rasterMs = msPer(rasterNanos, rasterFrames);
        if (rasterFrames.sum() > 0) {
            out.add(String.format(Locale.ROOT,
                    "perf 3d raster: frames=%d avg=%.1fms tris=%d fxaa=%d/%.1fms",
                    rasterFrames.sum(), rasterMs, rasterTris.sum(), fxaaFrames.sum(),
                    msPer(fxaaNanos, fxaaFrames)));
        }
        if (data != null) {
            out.add("perf mem: " + data.perfMemoryLine());
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out.add(String.format(Locale.ROOT, "perf heap: used=%dMB max=%dMB",
                heap.getUsed() >> 20, heap.getMax() >> 20));
        return out;
    }
}
