package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

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
 * capability groups (drawing / adding), and every failure degrades to "feature off" instead of
 * breaking the map.</p>
 *
 * <p><b>This is the 1.21.1 flavour (Xaero's Minimap 26.6.0).</b> The lookup chain was verified
 * symbol by symbol against {@code xaerominimap-neoforge-1.21.1-26.6.0.jar} with a headless probe,
 * and mirrors what Xaero's own {@code AddWaypointFunction} does:</p>
 * <pre>
 * XaeroMinimapSession.getCurrentSession()            -> XaeroMinimapSession (null outside a world)
 *   .getWaypointsManager()                           -> WaypointsManager (extends MinimapSession)
 *   .getCurrentWorld()                               -> WaypointWorld
 *   .getSets()                                       -> Map&lt;String, WaypointSet&gt;
 *   .getWaypoints() / getList()                      -> Waypoint...   (both exist; same list)
 * </pre>
 *
 * <p>26.2's 26.6.2 build exposes a different shape (BuiltInHudModules.MINIMAP / MinimapWorldManager /
 * getIterableWaypointSets, no {@code getLocalizedName}), so the two loaders deliberately keep
 * separate copies. Never port one chain to the other without re-running {@code javap} on the jar
 * that actually ships.</p>
 *
 * <p><b>Timing is the fragile part.</b> Loading a Xaero class with {@code Class.forName(name)} runs
 * its static initialiser, and Xaero's statics dereference its platform helper (game directory). If
 * that happens before the game is ready, the initialiser throws and the JVM marks the class as
 * erroneous for the rest of the session - every later lookup then fails with
 * {@code NoClassDefFoundError}. The probe therefore loads classes with
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
    private static Method mGetCurrentSession;    // XaeroMinimapSession.getCurrentSession()
    private static Method mGetWaypointsManager;  // XaeroMinimapSession.getWaypointsManager()
    private static Method mGetCurrentWorld;      // WaypointsManager.getCurrentWorld()
    private static Method mGetSets;              // WaypointWorld.getSets()
    private static Method mSetGetWaypoints;      // WaypointSet.getWaypoints() (hud base class)
    private static Method mSetGetList;           // 1.21.1: common WaypointSet.getList()
    private static Method mGetName;
    /** 1.21.1 has getLocalizedName(); 26.6.2 dropped it (name is a translation key there). */
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
    private static Constructor<?> ctorAddWaypoint;   // full: +double fScale +forcedCoordSrcWorld
    private static Constructor<?> ctorCoords;        // without fScale / forced world
    private static Constructor<?> ctorSimple;        // bare: parent/escape/ArrayList/root/world/fromSet/adding
    private static Field fHudModInstance;        // HudMod.INSTANCE (implements IXaeroMinimap)
    private static Method mGetSettings;          // HudMod.getSettings()
    private static Method mWaypointsGui;         // ModSettings.waypointsGUI(MinimapSession)
    private static Method mGetWorldState;        // MinimapSession.getWorldState()
    private static Method mGetCurrentWorldPath;  // MinimapWorldState.getCurrentWorldPath()
    private static Method mGetRoot;              // XaeroPath.getRoot()
    private static Method mPathToString;         // XaeroPath.toString()
    private static Method mGetCurrentSetId;      // WaypointWorld.getCurrent()

    private XaeroWaypoints() {
    }

    /** True when Xaero's Minimap is installed (regardless of whether the bridge resolved). */
    public static boolean installed() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ids of installed mods that look like Xaero builds. Used once, when {@link #MOD_ID} is not
     * found, so an id change (or a loader-specific id) shows up in the log instead of looking like
     * "the feature silently does nothing".
     */
    private static String xaeroLikeMods() {
        try {
            List<String> ids = new ArrayList<>();
            for (var info : ModList.get().getMods()) {
                String id = String.valueOf(info.getModId());
                if (id.contains("xaero")) ids.add(id);
            }
            return ids.isEmpty() ? "none" : String.join(",", ids);
        } catch (Throwable t) {
            return "unknown";
        }
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
     * Loads a Xaero class <b>without initialising it</b>. {@code getMethod}/ {@code getConstructor}
     * only need the linked class; running Xaero's static initialisers from our probe is what used to
     * brick the bridge (see the class javadoc).
     */
    private static Class<?> xaeroClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, XaeroWaypoints.class.getClassLoader());
    }

    private static void probe() {
        if (probed) return;
        if (probeFailures > 0 && System.nanoTime() - lastProbeFailureNanos < PROBE_RETRY_NANOS) return;
        if (!installed()) {
            probed = true;
            if (debug()) {
                System.out.println("[voxymap] xaero bridge off: '" + MOD_ID + "' not installed"
                        + " (xaero-like mods: " + xaeroLikeMods() + ")");
            }
            return;
        }
        try {
            Class<?> cSession = xaeroClass("xaero.common.XaeroMinimapSession");
            Class<?> cManager = xaeroClass("xaero.common.minimap.waypoints.WaypointsManager");
            Class<?> cWorld = xaeroClass("xaero.common.minimap.waypoints.WaypointWorld");
            Class<?> cSetBase = xaeroClass("xaero.hud.minimap.waypoint.set.WaypointSet");
            Class<?> cSetCommon = xaeroClass("xaero.common.minimap.waypoints.WaypointSet");
            Class<?> cWaypoint = xaeroClass("xaero.common.minimap.waypoints.Waypoint");

            mGetCurrentSession = cSession.getMethod("getCurrentSession");
            mGetWaypointsManager = cSession.getMethod("getWaypointsManager");
            mGetCurrentWorld = cManager.getMethod("getCurrentWorld");
            mGetSets = cWorld.getMethod("getSets");
            mSetGetWaypoints = cSetBase.getMethod("getWaypoints");
            // 1.21.1's concrete set exposes its backing ArrayList directly; both accessors return the
            // same inherited list, so either works - keep both for robustness across builds.
            try {
                mSetGetList = cSetCommon.getMethod("getList");
            } catch (Throwable ignored) {
                mSetGetList = null;
            }

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
                System.out.println("[voxymap] xaero bridge ready (minimap " + modVersion()
                        + ", set accessors: getList=" + (mSetGetList != null)
                        + ", localizedName=" + (mGetLocalizedName != null) + ")");
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
            Class<?> cIXaeroMinimap = xaeroClass("xaero.common.IXaeroMinimap");
            Class<?> cManager = xaeroClass("xaero.common.minimap.waypoints.WaypointsManager");
            Class<?> cMinimapSession = xaeroClass("xaero.hud.minimap.module.MinimapSession");
            Class<?> cWorldState = xaeroClass("xaero.hud.minimap.world.state.MinimapWorldState");
            Class<?> cWorld = xaeroClass("xaero.common.minimap.waypoints.WaypointWorld");
            Class<?> cPath = xaeroClass("xaero.hud.path.XaeroPath");
            Class<?> cAdd = xaeroClass("xaero.common.gui.GuiAddWaypoint");
            Class<?> cScreen = xaeroClass("net.minecraft.client.gui.screens.Screen");

            fHudModInstance = cHudMod.getField("INSTANCE");
            mGetSettings = cHudMod.getMethod("getSettings");
            mWaypointsGui = cSettings.getMethod("waypointsGUI", cMinimapSession);
            mGetWorldState = cMinimapSession.getMethod("getWorldState");
            mGetCurrentWorldPath = cWorldState.getMethod("getCurrentWorldPath");
            mGetRoot = cPath.getMethod("getRoot");
            mPathToString = cPath.getMethod("toString");
            mGetCurrentSetId = cWorld.getMethod("getCurrent");

            // Sanity: the settings gate and getWorldState() are declared on MinimapSession, and the
            // receiver we pass at open time is the WaypointsManager (a MinimapSession).
            // XaeroMinimapSession is a plain HudSession, so using it there throws
            // "argument type mismatch" from Method.invoke - that bug shipped once, so the receiver
            // relationship is asserted here instead of being remembered.
            Class<?>[] gateParams = mWaypointsGui.getParameterTypes();
            boolean receiverOk = gateParams.length == 1 && gateParams[0].isAssignableFrom(cManager);
            boolean stateOk = mGetWorldState.getDeclaringClass().isAssignableFrom(cManager);
            if (!receiverOk || !stateOk) {
                System.out.println("[voxymap] xaero dialog: receiver check failed"
                        + " (waypointsGUI=" + receiverOk + ", getWorldState=" + stateOk + ")");
                dialogProbed = true;
                dialogUsable = false;
                return;
            }

            // (IXaeroMinimap mod, WaypointsManager manager, Screen parent, Screen escape,
            //  ArrayList<Waypoint> waypoints, String worldPathRoot, WaypointWorld defaultWorld,
            //  String fromSet, boolean adding, boolean hasForcedPlayerPos,
            //  int fx, int fy, int fz, double fScale, WaypointWorld forcedCoordSrcWorld)
            // Resolved as a chain: 1.21.1 ships several overloads and the full one may throw for
            // reasons inside Xaero, so the narrower ones are kept as fallbacks (all of them take the
            // pre-filled coordinates; the 13-arg one derives the coordinate scale itself).
            ctorAddWaypoint = tryCtor(cAdd, cIXaeroMinimap, cManager, cScreen, cScreen,
                    ArrayList.class, String.class, cWorld, String.class,
                    boolean.class, boolean.class, int.class, int.class, int.class,
                    double.class, cWorld);
            ctorCoords = tryCtor(cAdd, cIXaeroMinimap, cManager, cScreen, cScreen,
                    ArrayList.class, String.class, cWorld, String.class,
                    boolean.class, boolean.class, int.class, int.class, int.class);
            ctorSimple = tryCtor(cAdd, cIXaeroMinimap, cManager, cScreen, cScreen,
                    ArrayList.class, String.class, cWorld, String.class, boolean.class);
            dialogProbed = true;
            dialogUsable = ctorAddWaypoint != null || ctorCoords != null || ctorSimple != null;
            if (debug()) {
                System.out.println("[voxymap] xaero add-waypoint dialog ready"
                        + " (ctors: full=" + (ctorAddWaypoint != null)
                        + " coords=" + (ctorCoords != null)
                        + " simple=" + (ctorSimple != null) + ")");
            }
        } catch (Throwable t) {
            // retried on the next availability check unless the classes are genuinely missing
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

    /** Resolves one constructor overload; returns null when this build does not have it. */
    private static Constructor<?> tryCtor(Class<?> owner, Class<?>... params) {
        try {
            return owner.getConstructor(params);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The waypoints of the world the player is currently in, over all of its sets, de-duplicated.
     * Empty when Xaero is missing, the player is not in a world, or anything failed.
     */
    public static List<Wp> snapshot() {
        if (!available()) return List.of();
        try {
            Object session = currentSession();
            if (session == null) {
                dbgOnce("no-session", "xaero read: no minimap session yet");
                return List.of();
            }
            Object manager = mGetWaypointsManager.invoke(session);
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
            Object sets = mGetSets.invoke(world);
            Iterable<?> iterable;
            if (sets instanceof Map<?, ?> map) {
                iterable = map.values();        // 1.21.1: HashMap<String, WaypointSet>
            } else if (sets instanceof Iterable<?> it) {
                iterable = it;
            } else {
                return List.of();
            }
            int setCount = 0;
            for (Object set : iterable) {
                if (set == null) continue;
                setCount++;
                Object waypoints = waypointList(set);
                if (!(waypoints instanceof Iterable<?> ws)) continue;
                for (Object wp : ws) add(out, seen, wp);
            }
            if (debug() && out.size() != lastLoggedCount) {
                lastLoggedCount = out.size();
                System.out.println("[voxymap] xaero waypoints: " + out.size()
                        + " (sets=" + setCount + ", world=" + worldName(world) + ")");
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

    /** 1.21.1 exposes {@code getList()} on the concrete set; fall back to the hud base accessor. */
    private static Object waypointList(Object set) throws Exception {
        if (mSetGetList != null) {
            try {
                return mSetGetList.invoke(set);
            } catch (Throwable ignored) {
                // fall through to the base accessor
            }
        }
        return mSetGetWaypoints.invoke(set);
    }

    private static String worldName(Object world) {
        try {
            Object id = world.getClass().getMethod("getId").invoke(world);
            return String.valueOf(id);
        } catch (Throwable ignored) {
            return "?";
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
     * colours and reads as 0 (black) for ordinary waypoints — which made every waypoint black in an
     * earlier revision.
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
     * so the raw name is run through the language table; ordinary names come back unchanged. 1.21.1
     * also exposes {@code getLocalizedName()}, which is preferred when it is non-empty.
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
     * name / colour / set and confirms, and Xaero saves the waypoint into the player's own waypoint
     * set — a plain third-party API waypoint would only live in memory.
     *
     * @return true when the dialog was opened
     */
    public static boolean openAddDialog(Screen parent, int x, int y, int z) {
        if (!dialogAvailable()) return false;
        try {
            Object session = currentSession();
            Object mod = fHudModInstance.get(null);
            Object manager = session == null ? null : mGetWaypointsManager.invoke(session);
            if (session == null || mod == null || manager == null) {
                if (debug()) System.out.println("[voxymap] xaero dialog: no session yet");
                return false;
            }

            // Xaero's own gate (fair-play editions / settings can forbid waypoint editing).
            // Careful: on 1.21.1 XaeroMinimapSession extends HudSession, NOT MinimapSession, while
            // waypointsGUI(MinimapSession)/getWorldState() are declared on MinimapSession. The
            // WaypointsManager returned above *is* a MinimapSession, so it is the correct receiver -
            // passing the session object threw "argument type mismatch" from Method.invoke.
            Object settings = mGetSettings.invoke(mod);
            Object allowed = settings == null ? null : mWaypointsGui.invoke(settings, manager);
            if (Boolean.FALSE.equals(allowed)) {
                if (debug()) System.out.println("[voxymap] xaero dialog: editing disabled in Xaero");
                return false;
            }

            Object state = mGetWorldState.invoke(manager);
            Object path = state == null ? null : mGetCurrentWorldPath.invoke(state);
            Object rootPath = path == null ? null : mGetRoot.invoke(path);
            Object world = mGetCurrentWorld.invoke(manager);
            if (rootPath == null || world == null) {
                if (debug()) System.out.println("[voxymap] xaero dialog: no world path yet");
                return false;
            }
            // The 1.21.1 constructor takes the root as a String and rebuilds it with
            // XaeroPath.root(String), so hand it the root node text (verified against 26.6.0's own
            // AddWaypointFunction, which passes XaeroPath.getRoot() to the other ctor overload).
            String root = String.valueOf(mPathToString.invoke(rootPath));
            Object fromSet = mGetCurrentSetId.invoke(world);
            Object screen = null;
            Throwable last = null;

            // Try the full constructor first (explicit coordinate scale + forced source world), then
            // the narrower overloads. Xaero's own key-bind path uses the 7-arg one, so at least that
            // shape is known to work; the pre-filled coordinates survive in every variant.
            if (ctorAddWaypoint != null) {
                Object[] args = {mod, manager, null, null, new ArrayList<>(), root, world, fromSet,
                        true, true, x, y, z, 1.0D, world};
                dumpArgs("full", ctorAddWaypoint, args);
                screen = newInstance(ctorAddWaypoint, args);
            }
            if (screen == null && ctorCoords != null) {
                Object[] args = {mod, manager, null, null, new ArrayList<>(), root, world, fromSet,
                        true, true, x, y, z};
                dumpArgs("coords", ctorCoords, args);
                screen = newInstance(ctorCoords, args);
            }
            if (screen == null && ctorSimple != null) {
                Object[] args = {mod, manager, null, null, new ArrayList<>(), root, world, fromSet, true};
                dumpArgs("simple", ctorSimple, args);
                screen = newInstance(ctorSimple, args);
            }
            if (screen == null) {
                System.out.println("[voxymap] xaero dialog: every constructor overload failed");
                dialogUsable = false;      // keep the read path (dots) alive!
                normaliseGuiState();
                return false;
            }
            if (debug()) System.out.println("[voxymap] xaero dialog: constructed");

            // setScreen only: Minecraft.setScreenAndShow also renders a frame synchronously, which
            // would nest a full render inside our mouse-click handling while Xaero's screen is still
            // initialising. Xaero itself only sets the screen and lets the normal frame draw it.
            Minecraft.getInstance().setScreen((Screen) screen);
            if (debug()) System.out.println("[voxymap] xaero dialog: screen set");
            return true;
        } catch (Throwable t) {
            // A failed open must not cost the (working) waypoint read path: log once, drop the
            // dialog capability for this session and leave the map's GUI state clean.
            System.out.println("[voxymap] xaero dialog failed: " + t);
            if (debug()) t.printStackTrace();
            dialogUsable = false;
            normaliseGuiState();
            return false;
        }
    }

    /** {@code newInstance} that logs the throw site instead of hiding it. */
    private static Object newInstance(Constructor<?> ctor, Object[] args) {
        try {
            return ctor.newInstance(args);
        } catch (Throwable t) {
            System.out.println("[voxymap] xaero dialog ctor failed: " + t);
            if (debug()) t.printStackTrace();
            return null;
        }
    }

    /** One line per attempt: argument classes vs the constructor's parameter classes. */
    private static void dumpArgs(String tag, Constructor<?> ctor, Object[] args) {
        if (!debug()) return;
        Class<?>[] types = ctor.getParameterTypes();
        StringBuilder sb = new StringBuilder("[voxymap] xaero dialog args(").append(tag).append("):");
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            sb.append(' ').append(i).append('=')
                    .append(a == null ? "null" : a.getClass().getSimpleName())
                    .append('/').append(types[i].getSimpleName());
        }
        System.out.println(sb);
    }

    /**
     * Puts the GUI/GL state back to the "nothing special" default. Xaero's dialog constructor loads
     * its screen classes and builds widgets; when it fails halfway it can leave shader/texture/VAO
     * or blend state behind, which showed up as "the map's fills vanish, the text remains".
     */
    private static void normaliseGuiState() {
        try {
            com.mojang.blaze3d.systems.RenderSystem.disableScissor();
            com.mojang.blaze3d.systems.RenderSystem.colorMask(true, true, true, true);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            com.mojang.blaze3d.vertex.BufferUploader.invalidate();
            com.mojang.blaze3d.platform.GlStateManager._glBindVertexArray(0);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, 0);
        } catch (Throwable ignored) {
        }
    }

    /** Small helper so the {@code state == null} check reads the same for both invokes. */

    private static Object currentSession() throws Exception {
        return mGetCurrentSession.invoke(null);
    }

    /** Installed Xaero version, for the one-time "bridge ready" line. */
    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void disable(String where, Throwable t) {
        usable = false;
        if (logged) return;
        logged = true;
        System.out.println("[voxymap] xaero bridge disabled at " + where + ": " + t);
    }
}
