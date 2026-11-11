package com.voxymap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEMPORARY in-game acceptance harness for the 26.2 Fabric build. NOT part of the shipped mod.
 *
 * <p>Activated with {@code -Dvoxymap.selftest=true}; installed from the Fabric client entry point
 * (one guarded call, see {@code VoxyMapFabric.onInitializeClient}). The harness waits for a world,
 * waits for Voxy's live-section stream to go quiet, opens the map screen, then for every requested
 * style: switches with {@link MapData#setStyle(String)}, waits until the tile colour/texture pipeline
 * is really settled, records timings + {@link MapPerf} deltas and takes a screenshot. It then toggles
 * into the real GPU 3D view (the same private {@code MapScreen.toggle3D()} the UI button calls) and
 * screenshots current+blocks in 3D, toggles back, restores the 2D view, writes a report and quits the
 * game so the wrapper script can return.</p>
 *
 * <p>System properties (all optional except the first):</p>
 * <pre>
 *   voxymap.selftest=true
 *   voxymap.selftest.out=&lt;dir&gt;            report.json + screenshots directory
 *   voxymap.selftest.tag=&lt;w1..w5&gt;         short ASCII name used in screenshot/report files
 *   voxymap.selftest.world=&lt;folder name&gt;  world folder (report only)
 *   voxymap.selftest.styles=core|all|a,b    core = current + blocks; all = MapStyles.NAMES
 *   voxymap.selftest.expectBlocks=true      fail when the blocks style is missing (feature build)
 *   voxymap.selftest.cold=true              this is a cold-cache first-open benchmark
 *   voxymap.selftest.coldStyle=&lt;style&gt;    default style applied before the first map open
 *   voxymap.selftest.view3d=true            run the 2D-&gt;3D-&gt;2D and 3D screenshots phase
 *   voxymap.selftest.quietTicks=40          settle window: ticks without pipeline counter changes
 * </pre>
 *
 * <p>Delete this class and the {@code MapSelfTest.install()} call before shipping.</p>
 */
public final class MapSelfTest {
    // ---- phases -----------------------------------------------------------------
    private static final int PHASE_WAIT_WORLD = 0;    // wait for level + player
    private static final int PHASE_WAIT_QUIET = 1;    // wait for Voxy's live stream to stop
    private static final int PHASE_WAIT_FIRST = 2;    // map open -> first settle (first-open timing)
    private static final int PHASE_STYLES = 3;        // style cycle (2D screenshots)
    private static final int PHASE_3D = 4;            // 2D -> 3D screenshots -> 2D
    private static final int PHASE_RESTORE = 5;       // back to current, final 2D screenshot
    private static final int PHASE_FINISH = 6;        // write report, quit

    private static final int STAGE_APPLY = 0;
    private static final int STAGE_SETTLE = 1;
    private static final int STAGE_SHOT = 2;

    private static boolean installed;
    private static boolean finished;

    private static Path outDir;
    private static String tag = "w0";
    private static String worldName = "";
    private static boolean cold;
    private static String coldStyle = "";
    private static boolean want3d;
    private static boolean expectBlocks;
    private static int quietTicks = 40;

    private static long worldWaitTimeoutMs = 180_000L;
    private static long quietTimeoutMs = 150_000L;
    private static long firstOpenTimeoutMs = 300_000L;
    private static long styleTimeoutMs = 150_000L;
    private static long threeDTimeoutMs = 300_000L;
    private static long totalTimeoutMs = 1_500_000L;

    private static long startMs;
    private static boolean quitting;
    private static long worldReadyMs = -1L;
    private static long worldQuietMs = -1L;
    private static long mapOpenMs = -1L;
    private static long firstOpenMs = -1L;
    private static String firstOpenStyle = "";

    private static int tick;
    private static int phase = PHASE_WAIT_WORLD;
    private static long phaseStartMs;

    // style list
    private static List<String> requestedStyles = List.of();
    private static List<String> runStyles = List.of();
    private static List<String> missingStyles = List.of();
    private static boolean blocksAvailable;
    private static int styleIndex;
    private static int styleStage = STAGE_APPLY;
    private static long styleApplyMs;
    private static String styleTarget = "";
    private static boolean skipFirstApply;
    private static final List<StyleRecord> styleRecords = new ArrayList<>();

    // 3D phase
    private static int d3Index;
    private static int d3Stage = STAGE_APPLY;
    private static boolean d3Entered;
    private static boolean d3Parity;
    private static final List<String> d3Screenshots = new ArrayList<>();
    private static final List<String> d3Styles = new ArrayList<>();

    // settle tracker
    private static long lastSignature;
    private static int lastChangeTick;
    private static Method toggle3d;

    // screenshots / diagnostics
    private static final AtomicInteger shotsPending = new AtomicInteger();
    private static final List<String> screenshots = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
    private static String tileTraceFile = "";
    private static boolean traceWasEnabled;

    private MapSelfTest() {
    }

    /** Called once from the Fabric client entry point; no-op unless -Dvoxymap.selftest=true. */
    public static void install() {
        if (!Boolean.getBoolean("voxymap.selftest")) return;
        if (installed) return;
        installed = true;
        startMs = System.currentTimeMillis();
        outDir = Path.of(System.getProperty("voxymap.selftest.out",
                Minecraft.getInstance().gameDirectory.toPath().resolve("voxymap-selftest").toString()));
        tag = System.getProperty("voxymap.selftest.tag", "w0");
        worldName = System.getProperty("voxymap.selftest.world", "");
        cold = Boolean.getBoolean("voxymap.selftest.cold");
        coldStyle = System.getProperty("voxymap.selftest.coldStyle", "");
        want3d = Boolean.getBoolean("voxymap.selftest.view3d");
        expectBlocks = !"false".equalsIgnoreCase(System.getProperty("voxymap.selftest.expectBlocks", "true"));
        quietTicks = Integer.getInteger("voxymap.selftest.quietTicks", 40);
        worldWaitTimeoutMs = Long.getLong("voxymap.selftest.worldTimeoutMs", worldWaitTimeoutMs);
        try { Files.createDirectories(outDir); } catch (Throwable ignored) { }
        resolveStyles();
        traceWasEnabled = VoxyMapConfig.DEBUG_TILE_TRACE.get();
        ClientTickEvents.END_CLIENT_TICK.register(MapSelfTest::tick);
        log("installed outDir=" + outDir + " tag=" + tag + " world='" + worldName + "'"
                + " cold=" + cold + " coldStyle='" + coldStyle + "' view3d=" + want3d
                + " styles=" + requestedStyles + " runStyles=" + runStyles
                + " missing=" + missingStyles + " quietTicks=" + quietTicks);
    }

    private static void resolveStyles() {
        String spec = System.getProperty("voxymap.selftest.styles", "core").trim();
        List<String> req = new ArrayList<>();
        if (spec.equalsIgnoreCase("all")) {
            req.addAll(List.of(MapStyles.NAMES));
        } else if (spec.equalsIgnoreCase("core") || spec.isEmpty()) {
            req.add("current");
            req.add("blocks");
        } else {
            for (String s : spec.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) req.add(t);
            }
        }
        List<String> list = List.of(MapStyles.NAMES);
        blocksAvailable = list.contains("blocks");
        requestedStyles = List.copyOf(req);
        LinkedHashSet<String> present = new LinkedHashSet<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String s : req) {
            if (list.contains(s)) present.add(s); else missing.add(s);
        }
        runStyles = List.copyOf(present);
        missingStyles = List.copyOf(missing);
        if (missing.contains("blocks") || !blocksAvailable) {
            if (expectBlocks) {
                errors.add("style 'blocks' is not present in MapStyles.NAMES (feature build expected)");
            } else {
                warnings.add("style 'blocks' not present (baseline build) - continuing without it");
            }
        }
    }

    // =============================================================================
    // tick / phases
    // =============================================================================
    private static void tick(Minecraft mc) {
        if (!installed || finished) return;
        tick++;
        long now = System.currentTimeMillis();
        try {
            if (now - startMs > totalTimeoutMs) {
                fail(mc, "global timeout after " + (now - startMs) + "ms in phase " + phase);
                return;
            }
            switch (phase) {
                case PHASE_WAIT_WORLD -> waitWorld(mc, now);
                case PHASE_WAIT_QUIET -> waitQuiet(mc, now);
                case PHASE_WAIT_FIRST -> waitFirstOpen(mc, now);
                case PHASE_STYLES -> styles(mc, now);
                case PHASE_3D -> threeD(mc, now);
                case PHASE_RESTORE -> restore(mc, now);
                case PHASE_FINISH -> finish(mc);
                default -> fail(mc, "unknown phase " + phase);
            }
        } catch (Throwable t) {
            log("exception: " + t);
            t.printStackTrace();
            fail(mc, "exception: " + t);
        }
    }

    private static void enterPhase(int next) {
        phase = next;
        phaseStartMs = System.currentTimeMillis();
        tick = 0;
        resetSettle();
    }

    private static void waitWorld(Minecraft mc, long now) {
        if (mc.level == null || mc.player == null) {
            if (now - startMs > worldWaitTimeoutMs) fail(mc, "no world after " + (now - startMs) + "ms");
            return;
        }
        worldReadyMs = now - startMs;
        log("world ready after " + worldReadyMs + "ms; waiting for the Voxy live stream to go quiet");
        enterPhase(PHASE_WAIT_QUIET);
    }

    /** Quickplay streams the SeedGen/LiveProbe fill for a while; opening the map before it stops would
     *  pollute the first-open timing with unrelated live section updates. Both builds wait the same. */
    private static void waitQuiet(Minecraft mc, long now) {
        if (settleCountersOnly()) {
            worldQuietMs = now - startMs;
            log("live stream quiet after " + worldQuietMs + "ms; enabling DEBUG_TILE_TRACE and opening map");
            VoxyMapConfig.DEBUG_TILE_TRACE.set(true);
            if (cold && !coldStyle.isEmpty()) {
                // the per-world state file was removed by the wrapper script, so the map's first open
                // falls back to the configured default style: make that fallback the measured style
                VoxyMapConfig.DEFAULT_STYLE.set(coldStyle);
                log("cold run: DEFAULT_STYLE=" + coldStyle);
            }
            Screen screen = mc.gui.screen();
            if (!(screen instanceof MapScreen)) {
                mc.setScreenAndShow(new MapScreen());
            }
            mapOpenMs = System.currentTimeMillis();
            log("map screen opened; measuring first-open to settled");
            enterPhase(PHASE_WAIT_FIRST);
            return;
        }
        if (now - phaseStartMs > quietTimeoutMs) {
            warnings.add("live stream did not go quiet within " + (now - phaseStartMs) + "ms; opening map anyway");
            log(warnings.get(warnings.size() - 1));
            VoxyMapConfig.DEBUG_TILE_TRACE.set(true);
            if (cold && !coldStyle.isEmpty()) VoxyMapConfig.DEFAULT_STYLE.set(coldStyle);
            mc.setScreenAndShow(new MapScreen());
            mapOpenMs = System.currentTimeMillis();
            enterPhase(PHASE_WAIT_FIRST);
        }
    }

    private static void waitFirstOpen(Minecraft mc, long now) {
        MapScreen screen = mapScreen(mc);
        if (screen == null) {
            fail(mc, "map screen missing while waiting for the first open");
            return;
        }
        if (screen.isGpu3dView()) {
            // a previous run of this world was left in 3D: force the 2D path first
            warnings.add("map restored the 3D view; switching to 2D before the style cycle");
            toggle3d(mc);
            return;
        }
        MapData data = MapData.INSTANCE;
        if (data.isReady() && !data.isLoading() && settle(mc)) {
            firstOpenMs = now - mapOpenMs;
            firstOpenStyle = data.getStyle();
            skipFirstApply = !runStyles.isEmpty() && runStyles.get(0).equals(firstOpenStyle);
            log("first open settled in " + firstOpenMs + "ms style='" + firstOpenStyle + "'"
                    + " colorReq=" + MapPerf.INSTANCE.colorRequests.sum()
                    + " colorDone=" + MapPerf.INSTANCE.colorDone.sum()
                    + " tex=" + MapPerf.INSTANCE.textureBuilds.sum()
                    + " visible=" + MapPerf.INSTANCE.lastVisibleTiles
                    + " skipFirstApply=" + skipFirstApply);
            enterPhase(PHASE_STYLES);
            return;
        }
        if (now - phaseStartMs > firstOpenTimeoutMs) {
            fail(mc, "map did not settle within " + (now - phaseStartMs) + "ms of opening"
                    + " (ready=" + data.isReady() + " loading=" + data.isLoading()
                    + " visible=" + MapPerf.INSTANCE.lastVisibleTiles + ")");
        }
    }

    private static void styles(Minecraft mc, long now) {
        MapScreen screen = mapScreen(mc);
        if (screen == null) {
            fail(mc, "map screen disappeared during the style cycle");
            return;
        }
        if (styleIndex >= runStyles.size()) {
            if (want3d && screen != null) {
                d3Styles.clear();
                if (runStyles.contains("current")) d3Styles.add("current");
                if (runStyles.contains("blocks")) d3Styles.add("blocks");
                if (d3Styles.isEmpty()) d3Styles.addAll(runStyles);
                if (d3Styles.isEmpty()) d3Styles.add(MapData.INSTANCE.getStyle());
                log("style cycle done; entering 3D for " + d3Styles);
                enterPhase(PHASE_3D);
            } else {
                enterPhase(PHASE_RESTORE);
            }
            return;
        }
        String target = runStyles.get(styleIndex);
        switch (styleStage) {
            case STAGE_APPLY -> {
                MapData data = MapData.INSTANCE;
                if (styleIndex == 0 && skipFirstApply && data.getStyle().equals(target)) {
                    // the first requested style is already the settled state measured for first-open:
                    // no style switch (and no recolour) is needed before its screenshot
                    skipFirstApply = false;
                    MapPerf p = MapPerf.INSTANCE;
                    StyleRecord rec = new StyleRecord();
                    rec.name = target;
                    rec.settleMs = 0L;
                    rec.colorRequests = p.colorRequests.sum();
                    rec.colorDone = p.colorDone.sum();
                    rec.colorStale = p.colorStale.sum();
                    rec.textureBuilds = p.textureBuilds.sum();
                    styleRecords.add(rec);
                    String shot0 = tag + "-" + target + "-2d.png";
                    screenshot(mc, shot0);
                    rec.screenshot = shot0;
                    log("style '" + target + "' is already the first-open style; settle=0ms -> " + shot0);
                    styleStage = STAGE_SHOT;
                    return;
                }
                if (data.getStyle().equals(target)) {
                    styleTarget = target;
                    styleApplyMs = now;
                    styleStage = STAGE_SETTLE;
                    resetSettle();
                    return;
                }
                if (data.isLoading() || data.isStyleChanging()) {
                    if (now - phaseStartMs > 30_000L) {
                        addError("style '" + target + "' could not be applied: data busy");
                        styleIndex++;
                        styleStage = STAGE_APPLY;
                        resetSettle();
                    }
                    return;
                }
                data.setStyle(target);
                styleTarget = target;
                styleApplyMs = now;
                styleStage = STAGE_SETTLE;
                resetSettle();
                log("style -> " + target);
            }
            case STAGE_SETTLE -> {
                if (settle(mc)) {
                    long settleMs = now - styleApplyMs;
                    MapPerf p = MapPerf.INSTANCE;
                    StyleRecord rec = new StyleRecord();
                    rec.name = target;
                    rec.settleMs = settleMs;
                    rec.colorRequests = p.colorRequests.sum();
                    rec.colorDone = p.colorDone.sum();
                    rec.colorStale = p.colorStale.sum();
                    rec.textureBuilds = p.textureBuilds.sum();
                    styleRecords.add(rec);
                    String shot = tag + "-" + target + "-2d.png";
                    screenshot(mc, shot);
                    rec.screenshot = shot;
                    log("style '" + target + "' settled in " + settleMs + "ms"
                            + " colorReq=" + rec.colorRequests + " colorDone=" + rec.colorDone
                            + " tex=" + rec.textureBuilds);
                    styleStage = STAGE_SHOT;
                    return;
                }
                if (now - styleApplyMs > styleTimeoutMs) {
                    addError("style '" + target + "' did not settle within " + styleTimeoutMs + "ms");
                    String shot = tag + "-" + target + "-2d-timeout.png";
                    screenshot(mc, shot);
                    styleIndex++;
                    styleStage = STAGE_APPLY;
                    resetSettle();
                }
            }
            case STAGE_SHOT -> {
                if (shotsPending.get() == 0 || now - styleApplyMs > styleTimeoutMs + 10_000L) {
                    styleIndex++;
                    styleStage = STAGE_APPLY;
                    resetSettle();
                }
            }
            default -> {
                styleStage = STAGE_APPLY;
            }
        }
    }

    private static void threeD(Minecraft mc, long now) {
        MapScreen screen = mapScreen(mc);
        if (screen == null) {
            fail(mc, "map screen disappeared during the 3D phase");
            return;
        }
        if (!d3Entered) {
            log("toggling into the 3D view (MapScreen.toggle3D)");
            toggle3d(mc);
            if (screen.isGpu3dView()) {
                d3Entered = true;
                d3Stage = STAGE_APPLY;
                resetSettle();
            }
            if (now - phaseStartMs > 20_000L) {
                addError("map screen did not enter 3D");
                enterPhase(PHASE_RESTORE);
            }
            return;
        }
        if (d3Index >= d3Styles.size()) {
            d3Parity = screen.isGpu3dView() == MapData.INSTANCE.getView3d();
            log("exiting 3D; parity(getView3d==screen.isGpu3dView)=" + d3Parity);
            toggle3d(mc);
            // the exit animation finishes asynchronously; wait in PHASE_RESTORE for 2D
            enterPhase(PHASE_RESTORE);
            return;
        }
        String target = d3Styles.get(d3Index);
        switch (d3Stage) {
            case STAGE_APPLY -> {
                MapData data = MapData.INSTANCE;
                if (!data.getStyle().equals(target)) {
                    if (data.isLoading() || data.isStyleChanging()) return;
                    data.setStyle(target);
                    log("3D style -> " + target);
                }
                d3Stage = STAGE_SETTLE;
                styleApplyMs = now;
                resetSettle();
            }
            case STAGE_SETTLE -> {
                if (settle(mc)) {
                    long settleMs = now - styleApplyMs;
                    String shot = tag + "-" + target + "-3d.png";
                    screenshot(mc, shot);
                    d3Screenshots.add(shot);
                    log("3D style '" + target + "' settled in " + settleMs + "ms -> " + shot);
                    d3Stage = STAGE_SHOT;
                    return;
                }
                if (now - styleApplyMs > threeDTimeoutMs) {
                    addError("3D style '" + target + "' did not settle within " + threeDTimeoutMs + "ms");
                    String shot = tag + "-" + target + "-3d-timeout.png";
                    screenshot(mc, shot);
                    d3Screenshots.add(shot);
                    d3Index++;
                    d3Stage = STAGE_APPLY;
                    resetSettle();
                }
            }
            case STAGE_SHOT -> {
                if (shotsPending.get() == 0 || now - styleApplyMs > threeDTimeoutMs + 10_000L) {
                    d3Index++;
                    d3Stage = STAGE_APPLY;
                    resetSettle();
                }
            }
            default -> d3Stage = STAGE_APPLY;
        }
    }

    private static void restore(Minecraft mc, long now) {
        MapScreen screen = mapScreen(mc);
        if (screen == null) {
            fail(mc, "map screen disappeared while restoring the 2D view");
            return;
        }
        MapData data = MapData.INSTANCE;
        if (screen.isGpu3dView() || data.getView3d()) {
            if (now - phaseStartMs > 15_000L) {
                addError("2D view did not come back after 15s");
                enterPhase(PHASE_FINISH);
            }
            return;
        }
        if (!data.isStyleChanging() && !data.getStyle().equals("current") && runStyles.contains("current")) {
            data.setStyle("current");
            resetSettle();
        }
        if (settle(mc)) {
            screenshot(mc, tag + "-final-2d.png");
            log("2D view restored; final screenshot queued");
            enterPhase(PHASE_FINISH);
            return;
        }
        if (now - phaseStartMs > styleTimeoutMs) {
            warnings.add("2D restore did not settle in time");
            enterPhase(PHASE_FINISH);
        }
    }

    private static void finish(Minecraft mc) {
        if (finished) return;
        finished = true;
        if (shotsPending.get() > 0) {
            // give the copy callback a few frames to complete before we tear the GL context down
            finished = false;
            if (tick < 100) return;
            finished = true;
            warnings.add("some screenshots were still pending at quit");
        }
        VoxyMapConfig.DEBUG_TILE_TRACE.set(traceWasEnabled);
        try { if (TileTrace.logFile() != null) tileTraceFile = TileTrace.logFile().toString(); } catch (Throwable ignored) { }
        writeReport();
        log("DONE result=" + result() + " errors=" + errors.size() + " warnings=" + warnings.size());
        quit(mc);
    }

    private static void fail(Minecraft mc, String message) {
        addError(message);
        log("FAILED: " + message);
        if (finished) return;
        finished = true;
        VoxyMapConfig.DEBUG_TILE_TRACE.set(traceWasEnabled);
        try { if (TileTrace.logFile() != null) tileTraceFile = TileTrace.logFile().toString(); } catch (Throwable ignored) { }
        writeReport();
        quit(mc);
    }

    private static void quit(Minecraft mc) {
        if (quitting) return;
        quitting = true;
        log("quitting client");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2500L);
            } catch (InterruptedException ignored) {
            }
            try {
                Minecraft.getInstance().stop();
            } catch (Throwable e) {
                System.out.println("[selftest] stop failed: " + e);
            }
        }, "voxymap-selftest-quit");
        t.setDaemon(true);
        t.start();
    }

    private static void addError(String message) {
        errors.add(message);
        System.out.println("[selftest] ERROR: " + message);
    }

    // =============================================================================
    // 3D toggle: the private MapScreen.toggle3D() that the UI button calls
    // =============================================================================
    private static void toggle3d(Minecraft mc) {
        MapScreen screen = mapScreen(mc);
        if (screen == null) {
            addError("cannot toggle 3D: map screen missing");
            return;
        }
        try {
            if (toggle3d == null) {
                toggle3d = MapScreen.class.getDeclaredMethod("toggle3D");
                toggle3d.setAccessible(true);
            }
            toggle3d.invoke(screen);
            log("toggle3D invoked; screen.isGpu3dView=" + screen.isGpu3dView()
                    + " data.getView3d=" + MapData.INSTANCE.getView3d());
        } catch (Throwable e) {
            addError("toggle3D failed: " + e);
        }
    }

    // =============================================================================
    // settle detection
    // =============================================================================
    private static void resetSettle() {
        lastSignature = signature();
        lastChangeTick = tick;
    }

    /** Signature of every per-tile pipeline counter that advances while work is in flight. */
    private static long signature() {
        MapPerf p = MapPerf.INSTANCE;
        long h = 1469598103934665603L;
        long[] values = {
                p.colorRequests.sum(), p.colorDone.sum(), p.colorStale.sum(),
                p.textureBuilds.sum(), p.liveSections.sum(), p.resyncs.sum(),
                p.evictions.sum(), p.cacheHits.sum(), p.cacheMisses.sum(),
                p.sourceRequests.sum(), p.sourceHits.sum(), p.sourceEmpty.sum(),
                p.tileObjects.sum(), p.tilesDrawn.sum() > 0 ? 1 : 0,
        };
        for (long v : values) {
            h ^= v;
            h *= 1099511628211L;
        }
        return h;
    }

    private static boolean settleCountersOnly() {
        long s = signature();
        if (s != lastSignature) {
            lastSignature = s;
            lastChangeTick = tick;
        }
        return tick - lastChangeTick >= quietTicks;
    }

    /** True when nothing in the tile colour/texture pipeline has changed for quietTicks. */
    private static boolean settle(Minecraft mc) {
        MapData data = MapData.INSTANCE;
        MapScreen screen = mapScreen(mc);
        boolean base = data.isReady() && !data.isLoading() && !data.isStyleChanging() && screen != null;
        if (!base) {
            // keep the quiet window from starting while the map is still loading
            lastSignature = signature();
            lastChangeTick = tick;
            return false;
        }
        long s = signature();
        if (s != lastSignature) {
            lastSignature = s;
            lastChangeTick = tick;
        }
        if (tick - lastChangeTick < quietTicks) return false;
        if (screen.isGpu3dView()) {
            return MapPerf.INSTANCE.rasterFrames.sum() > 0 && MapPerf.INSTANCE.colorFrameBuilds.sum() > 0;
        }
        return MapPerf.INSTANCE.lastVisibleTiles > 0 || MapPerf.INSTANCE.tilesDrawn.sum() > 0;
    }

    // =============================================================================
    // screenshots
    // =============================================================================
    private static void screenshot(Minecraft mc, String fileName) {
        Path out = outDir.resolve(fileName);
        shotsPending.incrementAndGet();
        try {
            RenderTarget target = mc.gameRenderer.mainRenderTarget();
            Screenshot.takeScreenshot(target, image -> {
                try (NativeImage img = image) {
                    Files.createDirectories(out.getParent());
                    img.writeToFile(out);
                    screenshots.add(fileName);
                    log("screenshot saved " + fileName);
                } catch (Throwable t) {
                    addError("screenshot " + fileName + " failed: " + t);
                } finally {
                    shotsPending.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            shotsPending.decrementAndGet();
            addError("screenshot " + fileName + " could not be requested: " + t);
        }
    }

    // =============================================================================
    // report
    // =============================================================================
    private static void writeReport() {
        StringBuilder b = new StringBuilder(8192);
        b.append("{\n");
        field(b, "tag", tag, true);
        field(b, "world", worldName, true);
        field(b, "cold", cold, true);
        field(b, "coldStyle", coldStyle, true);
        field(b, "expectBlocks", expectBlocks, true);
        field(b, "blocksAvailable", blocksAvailable, true);
        array(b, "styleNames", List.of(MapStyles.NAMES), true);
        array(b, "requestedStyles", requestedStyles, true);
        array(b, "runStyles", runStyles, true);
        array(b, "missingStyles", missingStyles, true);
        field(b, "startEpochMs", startMs, true);
        field(b, "worldReadyMs", worldReadyMs, true);
        field(b, "worldQuietMs", worldQuietMs, true);
        field(b, "firstOpenMs", firstOpenMs, true);
        field(b, "firstOpenStyle", firstOpenStyle, true);
        field(b, "quietTicks", quietTicks, true);
        b.append("  \"styles\": [");
        for (int i = 0; i < styleRecords.size(); i++) {
            StyleRecord r = styleRecords.get(i);
            b.append(i == 0 ? "\n" : ",\n");
            b.append("    {\"name\": \"").append(esc(r.name)).append("\"")
                    .append(", \"settleMs\": ").append(r.settleMs)
                    .append(", \"colorRequests\": ").append(r.colorRequests)
                    .append(", \"colorDone\": ").append(r.colorDone)
                    .append(", \"colorStale\": ").append(r.colorStale)
                    .append(", \"textureBuilds\": ").append(r.textureBuilds)
                    .append(", \"screenshot\": \"").append(esc(r.screenshot)).append("\"}");
        }
        b.append(styleRecords.isEmpty() ? "],\n" : "\n  ],\n");
        b.append("  \"view3d\": {");
        b.append("\"tested\": ").append(want3d)
                .append(", \"entered\": ").append(d3Entered)
                .append(", \"parity\": ").append(d3Parity);
        b.append(", \"styles\": [");
        for (int i = 0; i < d3Styles.size(); i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(esc(d3Styles.get(i))).append('"');
        }
        b.append("], \"screenshots\": [");
        for (int i = 0; i < d3Screenshots.size(); i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(esc(d3Screenshots.get(i))).append('"');
        }
        b.append("]},\n");
        array(b, "screenshots", snapshot(screenshots), true);
        array(b, "errors", snapshot(errors), true);
        array(b, "warnings", snapshot(warnings), true);
        field(b, "tileTraceFile", tileTraceFile, true);
        field(b, "result", result(), true);
        array(b, "log", snapshot(logLines), true);
        b.append("  \"mapDataLines\": [");
        try {
            List<String> lines = MapData.INSTANCE.debugLines();
            if (lines != null) {
                for (int i = 0; i < lines.size(); i++) {
                    b.append(i == 0 ? "\n" : ",\n").append("    \"").append(esc(lines.get(i))).append('"');
                }
                if (!lines.isEmpty()) b.append('\n');
            }
        } catch (Throwable ignored) {
        }
        b.append("  ],\n");
        b.append("  \"mapPerfLines\": [");
        try {
            List<String> lines = MapPerf.INSTANCE.lines(MapData.INSTANCE);
            if (lines != null) {
                for (int i = 0; i < lines.size(); i++) {
                    b.append(i == 0 ? "\n" : ",\n").append("    \"").append(esc(lines.get(i))).append('"');
                }
                if (!lines.isEmpty()) b.append('\n');
            }
        } catch (Throwable ignored) {
        }
        b.append("  ]\n");
        b.append("}\n");
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("report.json"), b.toString(), StandardCharsets.UTF_8);
            System.out.println("[selftest] report written to " + outDir.resolve("report.json"));
        } catch (Throwable t) {
            System.out.println("[selftest] could not write report: " + t);
        }
        // plain-text copy of the harness log, handy when latest.log is rotated
        try {
            Files.writeString(outDir.resolve("selftest.log"),
                    String.join(System.lineSeparator(), List.copyOf(logLines)) + System.lineSeparator()
                            + String.join(System.lineSeparator(), List.copyOf(errors)) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static String result() {
        if (errors.isEmpty() && styleRecords.size() == runStyles.size()) return "PASS";
        return "FAIL";
    }

    private static void field(StringBuilder b, String name, String value, boolean comma) {
        b.append("  \"").append(name).append("\": \"").append(esc(value)).append('"').append(comma ? ",\n" : "\n");
    }

    private static void field(StringBuilder b, String name, long value, boolean comma) {
        b.append("  \"").append(name).append("\": ").append(value).append(comma ? ",\n" : "\n");
    }

    private static void field(StringBuilder b, String name, boolean value, boolean comma) {
        b.append("  \"").append(name).append("\": ").append(value).append(comma ? ",\n" : "\n");
    }

    private static void array(StringBuilder b, String name, List<String> values, boolean comma) {
        b.append("  \"").append(name).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(esc(values.get(i))).append('"');
        }
        b.append(']').append(comma ? ",\n" : "\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    // =============================================================================
    // helpers
    // =============================================================================
    private static MapScreen mapScreen(Minecraft mc) {
        Screen s = mc.gui.screen();
        return s instanceof MapScreen m ? m : null;
    }

    private static void log(String s) {
        String line = String.format(Locale.ROOT, "[selftest +%dms] %s", System.currentTimeMillis() - startMs, s);
        System.out.println(line);
        logLines.add(line);
    }

    private static final class StyleRecord {
        String name = "";
        String screenshot = "";
        long settleMs;
        long colorRequests;
        long colorDone;
        long colorStale;
        long textureBuilds;
    }

    /** Copy of a synchronized list, safe against a screenshot callback appending while we serialize. */
    private static List<String> snapshot(List<String> list) {
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }
}
