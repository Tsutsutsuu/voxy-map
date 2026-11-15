package com.voxymap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional bridge to Xaero's Minimap, by reflection only.
 *
 * <p>voxy-map must not depend on Xaero: the mod has to load and work when Xaero is absent, and it
 * has to survive Xaero version bumps. Everything is therefore looked up lazily, in two independent
 * capability groups (drawing / adding), and every failure degrades to "feature off" with one log
 * line instead of breaking the map.</p>
 *
 * <p>The lookup chain mirrors what Xaero itself does (see its {@code AddWaypointFunction}):</p>
 * <pre>
 * BuiltInHudModules.MINIMAP.getCurrentSession()      -> MinimapSession   (null outside a world)
 *   .getWorldManager().getCurrentWorld()             -> MinimapWorld
 *   .getIterableWaypointSets()                       -> WaypointSet...
 *   .getWaypoints()                                  -> Waypoint...
 * </pre>
 *
 * <p><b>Version note:</b> this chain was verified against the installed Xaero 26.6.2 jar. The
 * 1.21.1 26.6.0 build is different again ({@code XaeroMinimapSession.getWaypointsManager()} /
 * {@code WaypointWorld.getSets()}), and the NeoForge tree keeps its own copy for exactly that
 * reason - re-verify against the jar you ship with rather than trusting another loader's copy.</p>
 *
 * <p><b>Timing is the fragile part.</b> Loading a Xaero class with {@code Class.forName(name)} runs
 * its static initialiser, and Xaero's statics dereference its platform helper (game directory). If
 * that happens before the game is ready, the initialiser throws and the JVM marks the class as
 * erroneous for the rest of the session. The probe therefore loads classes with
 * {@code initialize = false} and retries transient failures with a short backoff.</p>
 */
public final class XaeroWaypoints {
    public static final String MOD_ID = "xaerominimap";
    /**
     * Console tracing for the bridge. Quiet unless the mod's own "debug" config option is on, so a
     * release build logs nothing while a report can still get the full stage-by-stage trace without
     * a rebuild. Genuine failures are logged unconditionally (once each).
     */
    private static boolean debug() {
        try {
            return VoxyMapConfig.DEBUG_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }
    /** Retry delay after a transient probe failure (a static initialiser ran too early). */
    private static final long PROBE_RETRY_NANOS = 2_000_000_000L;

    /** One waypoint as drawn on the map (name already localized, colour already resolved). */
    public static final class Wp {
        public final String name;
        public final String initials;
        public final int x;
        public final int y;
        public final int z;
        public final int rgb;
        public final boolean server;
        /** False when the waypoint has no meaningful Y (Xaero's "y not included"): draw on terrain. */
        public final boolean hasY;

        Wp(String name, String initials, int x, int y, int z, int rgb, boolean server, boolean hasY) {
            this.name = name;
            this.initials = initials;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rgb = rgb;
            this.server = server;
            this.hasY = hasY;
        }
    }

    private static boolean probed;
    private static boolean usable;
    private static boolean dialogProbed;
    private static boolean dialogUsable;
    private static boolean logged;
    private static int probeFailures;
    private static long lastProbeFailureNanos;
    /** Last waypoint count written to the log (only changes are logged). */
    private static int lastLoggedCount = -1;
    /** States already logged once, so a repeating condition cannot spam the log. */
    private static final Set<String> LOGGED_STATES = new java.util.HashSet<>();

    private static void dbgOnce(String state, String message) {
        if (!debug()) return;
        if (LOGGED_STATES.add(state)) System.out.println("[voxymap] " + message);
    }

    private static void clearDbg(String state) {
        LOGGED_STATES.remove(state);
    }

    // --- drawing capability ------------------------------------------------------------------
    private static Field fBuiltInMinimap;        // BuiltInHudModules.MINIMAP (static HudModule)
    private static Method mGetCurrentSession;    // HudModule.getCurrentSession()
    private static Method mGetWorldManager;      // MinimapSession.getWorldManager()
    private static Method mGetCurrentWorld;      // MinimapWorldManager.getCurrentWorld()
    private static Method mGetWaypointSets;      // MinimapWorld.getIterableWaypointSets()
    private static Method mSetGetWaypoints;      // WaypointSet.getWaypoints()
    private static Method mGetName;
    /** 26.6.2 dropped getLocalizedName; older builds still have it, so it is optional. */
    private static Method mGetLocalizedName;
    private static Method mGetInitials;
    private static Method mGetX;
    private static Method mGetY;
    private static Method mGetZ;
    /** {@code Waypoint.getColor()} is the palette ordinal; {@code getActualColor()} is often 0. */
    private static Method mGetColor;
    /** ARGB per palette ordinal, resolved once from the WaypointColor enum. */
    private static int[] palette;
    private static Method mIsDisabled;
    private static Method mIsServerWaypoint;
    private static Method mIsYIncluded;

    // --- add-waypoint dialog capability ------------------------------------------------------
    private static Constructor<?> ctorAddWaypoint;
    private static Field fHudModInstance;        // HudMod.INSTANCE
    private static Method mGetSettings;          // HudMod.getSettings()
    private static Method mWaypointsGui;         // ModSettings.waypointsGUI(MinimapSession)
    private static Method mGetWorldState;        // MinimapSession.getWorldState()
    private static Method mGetCurrentWorldPath;  // MinimapWorldState.getCurrentWorldPath()
    private static Method mGetRoot;              // XaeroPath.getRoot()
    private static Method mGetCurrentSetId;      // MinimapWorld.getCurrentWaypointSetId()

    private XaeroWaypoints() {
    }

    /** True when Xaero's Minimap is installed (regardless of whether the bridge resolved). */
    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** True when Xaero's Minimap is installed and the read path resolved. */
    public static boolean available() {
        probe();
        return usable;
    }

    /** True when Xaero's own add-waypoint dialog can be opened (a subset of {@link #available()}). */
    public static boolean dialogAvailable() {
        probe();
        probeDialog();
        return usable && dialogUsable;
    }

    /**
     * Loads a Xaero class <b>without initialising it</b>: {@code getMethod} only needs the linked
     * class, and running Xaero's static initialisers from our probe is what used to brick the bridge
     * (see the class javadoc).
     */
    private static Class<?> xaeroClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, XaeroWaypoints.class.getClassLoader());
    }

    private static void probe() {
        if (probed) return;
        if (probeFailures > 0 && System.nanoTime() - lastProbeFailureNanos < PROBE_RETRY_NANOS) return;
        if (!installed()) {
            probed = true;
            return;
        }
        try {
            Class<?> cHudModule = xaeroClass("xaero.hud.module.HudModule");
            Class<?> cBuiltIn = xaeroClass("xaero.hud.minimap.BuiltInHudModules");
            Class<?> cMinimapSession = xaeroClass("xaero.hud.minimap.module.MinimapSession");
            Class<?> cWorldManager = xaeroClass("xaero.hud.minimap.world.MinimapWorldManager");
            Class<?> cWorld = xaeroClass("xaero.hud.minimap.world.MinimapWorld");
            Class<?> cSet = xaeroClass("xaero.hud.minimap.waypoint.set.WaypointSet");
            Class<?> cWaypoint = xaeroClass("xaero.common.minimap.waypoints.Waypoint");

            fBuiltInMinimap = cBuiltIn.getField("MINIMAP");
            mGetCurrentSession = cHudModule.getMethod("getCurrentSession");
            mGetWorldManager = cMinimapSession.getMethod("getWorldManager");
            mGetCurrentWorld = cWorldManager.getMethod("getCurrentWorld");
            mGetWaypointSets = cWorld.getMethod("getIterableWaypointSets");
            mSetGetWaypoints = cSet.getMethod("getWaypoints");

            mGetName = cWaypoint.getMethod("getName");
            try {
                mGetLocalizedName = cWaypoint.getMethod("getLocalizedName");
            } catch (Throwable ignored) {
                mGetLocalizedName = null;
            }
            mGetInitials = cWaypoint.getMethod("getInitials");
            mGetX = cWaypoint.getMethod("getX");
            mGetY = cWaypoint.getMethod("getY");
            mGetZ = cWaypoint.getMethod("getZ");
            mGetColor = cWaypoint.getMethod("getColor");
            mIsDisabled = cWaypoint.getMethod("isDisabled");
            mIsServerWaypoint = cWaypoint.getMethod("isServerWaypoint");
            mIsYIncluded = cWaypoint.getMethod("isYIncluded");
            palette = readPalette();
            usable = true;
            probed = true;
            probeFailures = 0;
            if (debug()) {
                System.out.println("[voxymap] xaero bridge ready (localizedName="
                        + (mGetLocalizedName != null) + ")");
            }
        } catch (ClassNotFoundException e) {
            // A missing class will never appear later: give up for this session, once.
            probed = true;
            disable("probe (class missing)", e);
        } catch (Throwable t) {
            // Transient (a static initialiser ran before the game was ready): retry with backoff.
            probeFailures++;
            lastProbeFailureNanos = System.nanoTime();
            if (!logged) {
                logged = true;
                System.out.println("[voxymap] xaero probe failed, will retry: " + t);
            }
        }
        if (usable) probeDialog();
    }

    private static void probeDialog() {
        if (!usable || dialogProbed) return;
        try {
            Class<?> cHudMod = xaeroClass("xaero.common.HudMod");
            Class<?> cSettings = xaeroClass("xaero.common.settings.ModSettings");
            Class<?> cMinimapSession = xaeroClass("xaero.hud.minimap.module.MinimapSession");
            Class<?> cWorldState = xaeroClass("xaero.hud.minimap.world.state.MinimapWorldState");
            Class<?> cWorld = xaeroClass("xaero.hud.minimap.world.MinimapWorld");
            Class<?> cPath = xaeroClass("xaero.hud.path.XaeroPath");
            Class<?> cAdd = xaeroClass("xaero.common.gui.GuiAddWaypoint");

            fHudModInstance = cHudMod.getField("INSTANCE");
            mGetSettings = cHudMod.getMethod("getSettings");
            mWaypointsGui = cSettings.getMethod("waypointsGUI", cMinimapSession);
            mGetWorldState = cMinimapSession.getMethod("getWorldState");
            mGetCurrentWorldPath = cWorldState.getMethod("getCurrentWorldPath");
            mGetRoot = cPath.getMethod("getRoot");
            mGetCurrentSetId = cWorld.getMethod("getCurrentWaypointSetId");

            // (HudMod, MinimapSession, Screen parent, Screen escape, ArrayList<Waypoint>,
            //  XaeroPath root, MinimapWorld defaultWorld, String fromSet, boolean adding,
            //  boolean hasForcedPlayerPos, int fx, int fy, int fz, double fScale,
            //  MinimapWorld forcedCoordSrcWorld)
            ctorAddWaypoint = cAdd.getConstructor(cHudMod, cMinimapSession, Screen.class, Screen.class,
                    ArrayList.class, cPath, cWorld, String.class, boolean.class,
                    boolean.class, int.class, int.class, int.class, double.class, cWorld);
            dialogProbed = true;
            dialogUsable = true;
            if (debug()) System.out.println("[voxymap] xaero add-waypoint dialog ready");
        } catch (Throwable t) {
            if (t instanceof ClassNotFoundException) {
                dialogProbed = true;
                dialogUsable = false;
            }
            if (!logged) {
                logged = true;
                System.out.println("[voxymap] xaero add-waypoint dialog unavailable: " + t);
            }
        }
    }

    /**
     * The waypoints of the world the player is currently in, over all of its sets, de-duplicated.
     * Empty when Xaero is missing, the player is not in a world, or anything failed.
     */
    public static List<Wp> snapshot() {
        if (!available()) return List.of();
        try {
            Object minimap = currentMinimapSession();
            if (minimap == null) {
                dbgOnce("no-session", "xaero read: no minimap session yet");
                return List.of();
            }
            Object manager = mGetWorldManager.invoke(minimap);
            Object world = manager == null ? null : mGetCurrentWorld.invoke(manager);
            if (world == null) {
                dbgOnce("no-world", "xaero read: no current world yet"
                        + " (manager=" + (manager != null) + ")");
                return List.of();
            }
            clearDbg("no-session");
            clearDbg("no-world");

            List<Wp> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            int setCount = 0;
            Object sets = mGetWaypointSets.invoke(world);
            Iterable<?> iterable;
            if (sets instanceof Map<?, ?> map) {
                iterable = map.values();
            } else if (sets instanceof Iterable<?> it) {
                iterable = it;
            } else {
                return List.of();
            }
            for (Object set : iterable) {
                if (set == null) continue;
                setCount++;
                Object waypoints = mSetGetWaypoints.invoke(set);
                if (!(waypoints instanceof Iterable<?> ws)) continue;
                for (Object wp : ws) add(out, seen, wp);
            }
            if (debug() && out.size() != lastLoggedCount) {
                lastLoggedCount = out.size();
                System.out.println("[voxymap] xaero waypoints: " + out.size() + " (sets=" + setCount + ")");
            }
            return out;
        } catch (Throwable t) {
            // transient failures (world still loading) must not disable the feature for the session
            if (!logged) {
                logged = true;
                System.out.println("[voxymap] xaero waypoint read failed: " + t);
            }
            return List.of();
        }
    }

    private static void add(List<Wp> out, Set<String> seen, Object wp) throws Exception {
        if (wp == null) return;
        if (Boolean.TRUE.equals(mIsDisabled.invoke(wp))) return;
        int x = (Integer) mGetX.invoke(wp);
        int y = (Integer) mGetY.invoke(wp);
        int z = (Integer) mGetZ.invoke(wp);
        String name = localized(wp);
        if (!seen.add(x + ":" + y + ":" + z + ":" + name)) return;
        int rgb = colorOf((Integer) mGetColor.invoke(wp));
        boolean server = Boolean.TRUE.equals(mIsServerWaypoint.invoke(wp));
        boolean hasY = Boolean.TRUE.equals(mIsYIncluded.invoke(wp));
        out.add(new Wp(name, initials(wp), x, y, z, rgb, server, hasY));
    }

    /**
     * Resolves the palette ordinal to RGB. Xaero keeps the colour as a {@code WaypointColor} enum
     * ordinal ({@code getColor()}); the {@code getActualColor()} field is only populated for custom
     * colours and reads as 0 (black) for ordinary waypoints — which is exactly what made every
     * waypoint on the map black.
     */
    private static int colorOf(int index) {
        int[] table = palette;
        if (table != null && index >= 0 && index < table.length) {
            return table[index] & 0xFFFFFF;
        }
        return 0xFFFFFF;
    }

    private static int[] readPalette() {
        try {
            Class<?> cColor = xaeroClass("xaero.hud.minimap.waypoint.WaypointColor");
            Object[] values = (Object[]) cColor.getMethod("values").invoke(null);
            Method hex = cColor.getMethod("getHex");
            int[] table = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                table[i] = (Integer) hex.invoke(values[i]);
            }
            return table;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Xaero stores deathpoints and other built-ins as translation keys ({@code gui.xaero_deathpoint}),
     * so the raw name is run through the language table; ordinary names come back unchanged.
     * Builds that still expose {@code getLocalizedName()} are preferred (26.6.2 dropped it).
     */
    private static String localized(Object wp) {
        if (mGetLocalizedName != null) {
            try {
                Object n = mGetLocalizedName.invoke(wp);
                if (n instanceof String s && !s.isEmpty()) return s;
            } catch (Throwable ignored) {
            }
        }
        try {
            Object n = mGetName.invoke(wp);
            if (n instanceof String s && !s.isEmpty()) {
                return Component.translatable(s).getString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String initials(Object wp) {
        try {
            Object n = mGetInitials.invoke(wp);
            if (n instanceof String s && !s.isEmpty()) return s;
        } catch (Throwable ignored) {
        }
        String name = localized(wp);
        return name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
    }

    /**
     * Opens Xaero's own "add waypoint" dialog with {@code x/y/z} pre-filled. The user then picks
     * name / colour / set and confirms, and Xaero
     * saves the waypoint into the player's own waypoint set — a plain third-party API waypoint
     * would only live in memory.
     *
     * @return true when the dialog was opened
     */
    public static boolean openAddDialog(Screen parent, int x, int y, int z) {
        if (!dialogAvailable()) return false;
        try {
            Object minimap = currentMinimapSession();
            Object mod = fHudModInstance.get(null);
            if (minimap == null || mod == null) {
                if (debug()) System.out.println("[voxymap] xaero dialog: no session yet");
                return false;
            }

            // Xaero's own gate (fair-play editions / settings can forbid waypoint editing)
            Object settings = mGetSettings.invoke(mod);
            Object allowed = settings == null ? null : mWaypointsGui.invoke(settings, minimap);
            if (Boolean.FALSE.equals(allowed)) {
                if (debug()) System.out.println("[voxymap] xaero dialog: editing disabled in Xaero");
                return false;
            }

            Object state = mGetWorldState.invoke(minimap);
            Object path = state == null ? null : mGetCurrentWorldPath.invoke(state);
            Object root = path == null ? null : mGetRoot.invoke(path);
            Object manager = mGetWorldManager.invoke(minimap);
            Object world = manager == null ? null : mGetCurrentWorld.invoke(manager);
            if (root == null || world == null) {
                if (debug()) System.out.println("[voxymap] xaero dialog: no world path yet");
                return false;
            }
            Object fromSet = mGetCurrentSetId.invoke(world);

            // ScreenBase(parent, escape): Xaero's own key-bind path passes null for both, and the
            // dialog then returns to the game when it closes. Passing the calling map screen here
            // made the open path unreliable (the map's removed()/re-init chain runs while the
            // third-party screen is still initialising), so stay on the exact shape Xaero uses.
            if (debug()) System.out.println("[voxymap] xaero dialog: constructing");
            Object screen = ctorAddWaypoint.newInstance(mod, minimap, null, null,
                    new ArrayList<>(), root, world, fromSet,
                    true,          // adding
                    true, x, y, z, 1.0D, world);   // pre-filled coordinates
            if (debug()) System.out.println("[voxymap] xaero dialog: constructed");

            // Gui.setScreen, not Minecraft.setScreenAndShow: the latter also renders a frame
            // synchronously (renderFrame(false)), which nests a full render inside our mouse-click
            // handling while another mod's screen is still initialising. Xaero itself only sets the
            // screen and lets the normal frame draw it.
            BlazeCompat.openScreen((Screen) screen);
            if (debug()) System.out.println("[voxymap] xaero dialog: screen set");
            return true;
        } catch (Throwable t) {
            // A failed open must not cost the (working) waypoint read path: keep `usable`, drop the
            // dialog capability for this session and leave the map's GUI state clean.
            System.out.println("[voxymap] xaero dialog failed: " + t);
            if (debug()) t.printStackTrace();
            dialogUsable = false;
            return false;
        }
    }

    private static Object currentMinimapSession() throws Exception {
        Object module = fBuiltInMinimap.get(null);
        if (module == null) return null;
        return mGetCurrentSession.invoke(module);
    }

    private static void disable(String where, Throwable t) {
        usable = false;
        if (logged) return;
        logged = true;
        System.out.println("[voxymap] xaero bridge disabled at " + where + ": " + t);
    }
}
