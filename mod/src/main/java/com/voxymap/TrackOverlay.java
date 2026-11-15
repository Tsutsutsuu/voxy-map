package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/**
 * Session-only recording of the player's path, drawn as a map overlay.
 *
 * <p>Deliberately <b>not</b> persisted: the overlay is a "measure while it is switched on" tool, so
 * switching it off throws the path away and nothing is written to disk. Recording is driven from
 * the client tick rather than from the map screen, because the map is normally closed while the
 * player is actually moving — the path has to keep growing in the background.</p>
 *
 * <p>Storage is three primitive arrays with a hard cap: a long session must not grow the heap, and
 * the 3D geometry is rebuilt from these arrays whenever {@link #version()} changes.</p>
 */
public final class TrackOverlay {
    /**
     * Hard cap on samples. Once reached, the path is thinned uniformly (every second sample kept,
     * see {@link #compactHalf}) instead of dropping the oldest part, so the recorded period stays
     * fully covered at a coarser resolution; the arrays themselves never grow.
     */
    public static final int MAX_POINTS = 20_000;
    /** Sample at most once per this many blocks... */
    private static final double MIN_STEP_BLOCKS = 2.0;
    /** ...but always keep a sample this often, so standing still still advances time. */
    private static final long MAX_GAP_MS = 4_000L;
    /** A single-tick move longer than this is a teleport / respawn, not walking. */
    private static final double TELEPORT_BLOCKS = 64.0;

    private static final int[] XS = new int[MAX_POINTS];
    private static final int[] ZS = new int[MAX_POINTS];
    /**
     * The player's own Y at the sample, not the terrain surface: a flying player's path has to
     * float, and a path walked through a cave must not be lifted onto the mountain above it.
     */
    private static final int[] YS = new int[MAX_POINTS];
    /** True when the sample starts a new segment (first point, dimension change, teleport). */
    private static final boolean[] SEGMENT_START = new boolean[MAX_POINTS];

    private static boolean enabled;
    private static int count;
    /** Bumped on every change; the 3D renderer rebuilds its buffers on a version change. */
    private static volatile int version = 1;
    private static long lastSampleMs;
    private static Object lastLevel;
    /** Save/server identity ({@code sp:<level name>} / {@code mp:<address>}); a change clears. */
    private static String lastWorld;
    private static double lastX;
    private static double lastZ;

    private TrackOverlay() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int version() {
        return version;
    }

    public static int count() {
        return count;
    }

    public static int x(int i) {
        return XS[i];
    }

    public static int z(int i) {
        return ZS[i];
    }

    /** Player Y (feet) at sample {@code i}; the 3D overlay places the ribbon at this height. */
    public static int y(int i) {
        return YS[i];
    }

    public static boolean startsSegment(int i) {
        return SEGMENT_START[i];
    }

    /**
     * Alpha for sample {@code i}: only the start of the path fades in, everything after the ramp is
     * fully opaque. A fade over the whole path (the first implementation) made the recent part of
     * the trail look washed out, which is the part you actually want to read.
     */
    public static int alphaAt(int i) {
        int n = count;
        if (n <= 1) return 0xFF;
        int fade = Math.min(n - 1, Math.max(16, n / 8));
        if (i >= fade) return 0xFF;
        return 0x20 + (0xDF * i) / Math.max(1, fade);
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    /**
     * Turning the overlay on starts a fresh path ("record from the moment I switched this on");
     * turning it off clears it, which is what the button label promises.
     */
    public static void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        clear();
    }

    public static synchronized void clear() {
        count = 0;
        lastSampleMs = 0L;
        lastLevel = null;
        version++;
    }

    /** Called from every client tick, whether the map is open or not. */
    public static synchronized void tick() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;
        // A different save/server means the recorded coordinates belong to another world: clear
        // instead of drawing the old path over the new map. Switching dimension does NOT change
        // this identity, so a Nether trip keeps the overworld path.
        String world = worldSignature(mc);
        if (lastWorld == null) {
            lastWorld = world;
        } else if (!lastWorld.equals(world)) {
            lastWorld = world;
            clear();
        }
        // Overworld only, matching the map itself: the map has no Nether/End rendering, so a track
        // recorded there would otherwise be drawn over the overworld map at raw XZ coordinates.
        if (level.dimension() != Level.OVERWORLD) return;

        long now = System.currentTimeMillis();
        double x = player.getX();
        double z = player.getZ();
        double y = player.getY();

        boolean newLevel = level != lastLevel;
        boolean first = count == 0;
        double dx = x - lastX;
        double dz = z - lastZ;
        double moved = Math.hypot(dx, dz);
        boolean teleported = !newLevel && !first && moved > TELEPORT_BLOCKS;

        if (!first && !newLevel && !teleported
                && moved < MIN_STEP_BLOCKS && now - lastSampleMs < MAX_GAP_MS) {
            return;
        }

        push(x, y, z, first || newLevel || teleported);
        lastLevel = level;
        lastX = x;
        lastZ = z;
        lastSampleMs = now;
    }

    private static void push(double x, double y, double z, boolean segmentStart) {
        if (count >= MAX_POINTS) {
            // Uniform downsampling instead of dropping the oldest half: keep every second sample, so
            // the path keeps covering the whole recorded period at half the resolution. Dropping
            // the old half would silently forget everything before some point in time; thinning
            // just gets progressively coarser (2 blocks -> 4 -> 8 ...).
            compactHalf();
        }
        XS[count] = (int) Math.floor(x);
        YS[count] = (int) Math.floor(y);
        ZS[count] = (int) Math.floor(z);
        SEGMENT_START[count] = segmentStart;
        count++;
        version++;
    }

    /**
     * Identity of the save/server the player is in. Singleplayer uses the level name (the save
     * folder name), multiplayer the server address — both are cheap field reads, and neither
     * changes when the player switches dimension.
     */
    private static String worldSignature(Minecraft mc) {
        var singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) {
            return "sp:" + singleplayer.getWorldData().getLevelName();
        }
        var server = mc.getCurrentServer();
        return server == null ? "mp:" : "mp:" + server.ip;
    }

    /** Keeps every second sample (and any segment boundary it would have dropped). */
    private static void compactHalf() {
        int kept = 0;
        for (int i = 0; i < count; i += 2) {
            boolean start = SEGMENT_START[i] || (i > 0 && SEGMENT_START[i - 1]);
            XS[kept] = XS[i];
            YS[kept] = YS[i];
            ZS[kept] = ZS[i];
            SEGMENT_START[kept] = start;
            kept++;
        }
        count = kept;
        // the first surviving sample always starts a segment
        SEGMENT_START[0] = true;
    }
}
