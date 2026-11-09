package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.lang.reflect.Method;
import java.util.List;

/**
 * TEMPORARY in-game smoke test for the NeoForge 1.21.1 port. NOT part of the shipped mod.
 *
 * <p>Activated with {@code -Dvoxymap.selftest=true}. Sequence: wait for a level -> open the map,
 * force 2D -> screenshot 2D -> toggle the 3D view through the private {@code MapScreen.toggle3D()},
 * tilt the camera -> screenshot 3D -> back to 2D -> screenshot (text/GL-state regression check) ->
 * open the config screen -> screenshot -> quit.</p>
 *
 * <p>Delete this file (and the {@code SelfTest.tick(mc)} call in {@code VoxyMapMod}) before shipping.</p>
 */
public final class SelfTest {
    private static boolean enabled = Boolean.getBoolean("voxymap.selftest");
    private static int t;
    private static int phase;
    private static long startedAt = System.currentTimeMillis();
    private static boolean shot3d;

    private SelfTest() {
    }

    public static void tick(Minecraft mc) {
        if (!enabled) return;
        t++;
        try {
            step(mc);
        } catch (Throwable e) {
            log("FAILED: " + e);
            e.printStackTrace();
            enabled = false;
            quit(mc);
        }
    }

    private static void step(Minecraft mc) throws Exception {
        switch (phase) {
            case 0 -> { // wait for the world, open the map in 2D
                if (mc.level == null || mc.player == null) {
                    if (t > 1200) {
                        log("no level after 60s, giving up");
                        enabled = false;
                        quit(mc);
                    }
                    return;
                }
                log("level ready after " + t + " ticks, opening MapScreen");
                mc.setScreen(new MapScreen());
                phase = 1;
                t = 0;
            }
            case 1 -> { // force 2D (the world remembers the 3D view from the previous run)
                if (t == 20) {
                    MapScreen screen = mc.screen instanceof MapScreen m ? m : null;
                    if (screen != null && screen.isGpu3dView()) {
                        log("map restored the 3D view; switching to 2D first");
                        toggle3d(mc);
                        t = 0;
                        return;
                    }
                    log("2D confirmed");
                }
                if (t == 240) {
                    log2d(mc);
                    shot(mc, "selftest-2d");
                }
                if (t == 300) {
                    log("toggling 3D");
                    toggle3d(mc);
                    phase = 2;
                    t = 0;
                }
            }
            case 2 -> { // 3D terrain
                if (t == 20) tilt(mc);
                if (t == 150) {
                    log3d(mc);
                    shot(mc, "selftest-3d");
                    shot3d = true;
                }
                if (t == 420) {
                    log3d(mc);
                    shot(mc, "selftest-3d-late");
                }
                if (t == 480) {
                    if (!shot3d) log("WARNING: no 3D screenshot taken");
                    log("3D done, switching back to 2D");
                    toggle3d(mc);
                    phase = 3;
                    t = 0;
                }
            }
            case 3 -> { // 2D again: text + GL state regression check
                if (t == 80) {
                    log("2D after 3D: view3d=" + view3d(mc));
                    shot(mc, "selftest-2d-after-3d");
                }
                if (t == 140) {
                    log("opening config screen");
                    mc.setScreen(new MapConfigScreen(null));
                    phase = 4;
                    t = 0;
                }
            }
            case 4 -> { // config screen
                if (t == 100) shot(mc, "selftest-config");
                if (t == 160) {
                    log("DONE in " + (System.currentTimeMillis() - startedAt) + "ms");
                    enabled = false;
                    quit(mc);
                }
            }
            default -> {
                enabled = false;
                quit(mc);
            }
        }
    }

    private static boolean view3d(Minecraft mc) {
        return mc.screen instanceof MapScreen m && m.isGpu3dView();
    }

    private static void toggle3d(Minecraft mc) {
        try {
            MapScreen screen = mc.screen instanceof MapScreen m ? m : null;
            if (screen == null) {
                log("map screen vanished");
                return;
            }
            Method toggle = MapScreen.class.getDeclaredMethod("toggle3D");
            toggle.setAccessible(true);
            toggle.invoke(screen);
            log("toggled; view3d=" + screen.isGpu3dView()
                    + " camera=" + (screen.gpu3dCamera() != null));
        } catch (Throwable e) {
            log("toggle3D failed: " + e);
            e.printStackTrace();
        }
    }

    /** Tilts the 3D camera so the screenshot shows real perspective terrain, not the seamless top-down entry. */
    private static void tilt(Minecraft mc) {
        if (!(mc.screen instanceof MapScreen screen)) return;
        Map3D.Camera cam = screen.gpu3dCamera();
        if (cam == null) return;
        cam.pitch = (float) Math.toRadians(-32);
        cam.yaw = (float) Math.toRadians(28);
        cam.distance = 1200f;
        log("camera tilted: pitch=-32 yaw=28 dist=1200");
    }

    private static void log2d(Minecraft mc) {
        MapData data = MapData.INSTANCE;
        log("2D: ready=" + data.isReady() + " loading=" + data.isLoading() + " error=" + data.getError()
                + " tileMode=" + data.isTile2DMode() + " liveStore=" + data.hasLiveStore()
                + " size=" + data.getMapWidth() + "x" + data.getMapHeight()
                + " pb=" + data.getPixelBlocks());
        lines("2D mapData", data.debugLines());
        lines("2D perf", MapPerf.INSTANCE.lines(data));
    }

    private static void log3d(Minecraft mc) {
        MapScreen screen = mc.screen instanceof MapScreen m ? m : null;
        log("3D: screen=" + (screen != null) + " view3d=" + (screen != null && screen.isGpu3dView()));
        lines("gpu3d", Gpu3DMap.debugLines());
        log("gl error after 3D frame: 0x" + Integer.toHexString(org.lwjgl.opengl.GL11.glGetError()));
    }

    private static void lines(String tag, List<String> lines) {
        if (lines == null) return;
        for (String s : lines) log(tag + ": " + s);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> {
        });
        log("screenshot queued: " + name + ".png  glError=0x"
                + Integer.toHexString(org.lwjgl.opengl.GL11.glGetError()));
    }

    private static void quit(Minecraft mc) {
        log("quitting client");
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            Minecraft.getInstance().stop();
        }, "voxymap-selftest-quit").start();
    }

    private static void log(String s) {
        System.out.println("[selftest] " + s);
    }
}
