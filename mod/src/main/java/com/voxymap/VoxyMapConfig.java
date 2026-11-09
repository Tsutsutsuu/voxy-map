package com.voxymap;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration for the NeoForge 1.21.1 build.
 *
 * <p>The 26.2 mainline keeps a JSON config and a self-drawn config screen; NeoForge keeps its own
 * TOML ({@link ModConfigSpec}) backend. To keep the ported 26.2 sources (MapData, MapScreen,
 * MapConfigScreen, Gpu3DMap) source-identical, every option is exposed as a {@link Value} wrapper
 * that mirrors the 26.2 accessor contract ({@code .get()}, {@code .set(Object)},
 * {@code .getDefault()}). Only this file differs between the loaders.</p>
 */
public final class VoxyMapConfig {
    public static final ModConfigSpec SPEC;

    // ---- retired / legacy options (no effect, kept because retired code paths reference them) ----
    /** @deprecated no effect: the 2D tile path has no global auto refresh. */
    @Deprecated public static final Value<Boolean> AUTO_REFRESH;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<String> AUTO_REFRESH_MODE;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> GENTLE_BUDGET_MS_PER_10S;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> GENTLE_IDLE_SECONDS;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> FRONTIER_DISTANCE_CHUNKS;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> OPEN_TIME_FALLBACK_MINUTES;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> IDLE_TIME_FALLBACK_MINUTES;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> FRONTIER_COOLDOWN_SECONDS;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> FRONTIER_BACKOFF_SECONDS;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Integer> INCREMENTAL_MAX_CHANGED_PERCENT;
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated public static final Value<Boolean> INCREMENTAL_REFRESH;
    /** @deprecated no effect: the "wait for SeedGen" dialog is disabled in tile mode. */
    @Deprecated public static final Value<Integer> INITIAL_REFRESH_DELAY_MINUTES;
    /** @deprecated no effect: the legacy snapshot memory guard is unreachable in tile mode. */
    @Deprecated public static final Value<Integer> MAX_MAP_PIXELS;
    /** @deprecated no effect: the verbose lines only existed in the retired refresh lanes. */
    @Deprecated public static final Value<Boolean> DEBUG_REFRESH_VERBOSE;

    // ---- interface / 2D map ---------------------------------------------------------------------
    public static final Value<Boolean> SHOW_COORDINATES;
    public static final Value<Boolean> SHOW_HINT;
    public static final Value<Boolean> TELEPORT_ENABLED;
    public static final Value<Double> DEFAULT_ZOOM;
    public static final Value<Boolean> REMEMBER_ZOOM;
    public static final Value<String> ARROW_STYLE;
    public static final Value<String> DEFAULT_STYLE;
    public static final Value<Boolean> SHOW_BIOMES;
    public static final Value<Boolean> SHOW_RENDER_DISTANCE;
    public static final Value<Integer> BIOME_LABEL_MIN_AREA;
    public static final Value<Boolean> BIOME_LABEL_BACKGROUND;

    // ---- map drawing ---------------------------------------------------------------------------
    public static final Value<Boolean> ADAPTIVE_COLOR_RAMP;
    public static final Value<Boolean> MIN_MAX_RAMP;
    public static final Value<Boolean> DRAW_HILLSHADE;
    public static final Value<Boolean> DRAW_WATER_DEPTH;
    public static final Value<Boolean> AUTO_SEA_LEVEL;
    public static final Value<Integer> SEA_LEVEL_OVERRIDE;
    public static final Value<Boolean> DRAW_CONTOURS;
    public static final Value<Integer> CONTOUR_DENSITY;
    public static final Value<Integer> MIN_CONTOUR_AREA;
    public static final Value<Boolean> WATER_BLUR;
    public static final Value<Integer> WATER_BLUR_RADIUS;
    public static final Value<Integer> WATER_BLUR_PASSES;
    public static final Value<Boolean> DENOISE;
    public static final Value<Boolean> FILL_HOLES;

    // ---- data and refresh ----------------------------------------------------------------------
    public static final Value<Boolean> LIVE_TILES;
    public static final Value<Integer> MAP_SCALE;
    public static final Value<Integer> MAX_LOD_LEVEL;
    public static final Value<Integer> CACHE_KEEP_COUNT;
    public static final Value<Integer> DISK_CACHE_MAX_MB;

    // ---- 3D view -------------------------------------------------------------------------------
    public static final Value<Integer> FOV_3D;
    public static final Value<Double> VERTICAL_SCALE_3D;
    public static final Value<Integer> MIN_DISTANCE_3D;
    public static final Value<Integer> MAX_DISTANCE_3D;
    public static final Value<Double> ROTATE_SENS_3D;
    public static final Value<Double> PAN_SENS_3D;
    public static final Value<Double> ZOOM_SENS_3D;
    public static final Value<Boolean> REMEMBER_3D_VIEW;
    public static final Value<Integer> GPU_TERRAIN_NEAR_DISTANCE;
    public static final Value<Integer> GPU_TERRAIN_MID_DISTANCE;
    public static final Value<Integer> GPU_TERRAIN_NEAR_STEP;
    public static final Value<Integer> GPU_TERRAIN_MID_STEP;
    public static final Value<Integer> GPU_TERRAIN_FAR_STEP;
    public static final Value<Integer> GPU_TERRAIN_SKIRT_DEPTH;
    public static final Value<Integer> GPU_TERRAIN_HYSTERESIS_PCT;
    public static final Value<Double> PLAYER_MARKER_HEIGHT_3D;
    public static final Value<Double> PLAYER_MARKER_SCALE_3D;
    public static final Value<Integer> BIOME_LABEL_3D_RANGE;
    public static final Value<Boolean> AUTO_HIDE_CONTOURS_3D;
    public static final Value<Boolean> KEEP_3D_TILES_IN_2D;

    // ---- performance ---------------------------------------------------------------------------
    public static final Value<Integer> TILE_RESIDENT_CAP;
    public static final Value<Integer> GPU_TEXTURE_BUDGET_MB;
    public static final Value<Integer> GPU3D_BUILD_THREADS;
    public static final Value<Integer> GPU3D_BATCH_BUILD;
    public static final Value<Boolean> SKIP_WORLD_RENDER;
    public static final Value<Boolean> RELEASE_TEXTURES_ON_CLOSE;

    // ---- debug ---------------------------------------------------------------------------------
    public static final Value<Boolean> DEBUG_ENABLED;
    public static final Value<Boolean> DEBUG_REFRESH_CHAT;
    public static final Value<Boolean> DEBUG_VOXY_EVENTS;
    public static final Value<Boolean> DEBUG_TILE_TRACE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Retired options (no effect) / 已废弃选项").push("legacy");
        AUTO_REFRESH = new Value<>(b.define("autoRefresh", true), true);
        AUTO_REFRESH_MODE = new Value<>(b.define("autoRefreshMode", "gentle"), "gentle");
        GENTLE_BUDGET_MS_PER_10S = new Value<>(b.define("gentleBudgetMsPer10s", 100), 100);
        GENTLE_IDLE_SECONDS = new Value<>(b.define("gentleIdleSeconds", 2), 2);
        FRONTIER_DISTANCE_CHUNKS = new Value<>(b.define("frontierDistanceChunks", 16), 16);
        OPEN_TIME_FALLBACK_MINUTES = new Value<>(b.define("openTimeFallbackMinutes", 5), 5);
        IDLE_TIME_FALLBACK_MINUTES = new Value<>(b.define("idleTimeFallbackMinutes", 30), 30);
        FRONTIER_COOLDOWN_SECONDS = new Value<>(b.define("frontierCooldownSeconds", 15), 15);
        FRONTIER_BACKOFF_SECONDS = new Value<>(b.define("frontierBackoffSeconds", 60), 60);
        INCREMENTAL_MAX_CHANGED_PERCENT = new Value<>(b.define("incrementalMaxChangedPercent", 60), 60);
        INCREMENTAL_REFRESH = new Value<>(b.define("incrementalRefresh", true), true);
        INITIAL_REFRESH_DELAY_MINUTES = new Value<>(b.define("initialRefreshDelayMinutes", 3), 3);
        MAX_MAP_PIXELS = new Value<>(b.define("maxMapPixels", 120_000_000), 120_000_000);
        DEBUG_REFRESH_VERBOSE = new Value<>(b.define("debugRefreshVerbose", false), false);
        b.pop();

        b.comment("Interface / 界面").push("ui");
        SHOW_COORDINATES = new Value<>(b.comment("Show cursor coordinates at the bottom of the map screen")
                .define("showCoordinates", true), true);
        SHOW_HINT = new Value<>(b.comment("Show the control hint strip in the top-left corner")
                .define("showHint", true), true);
        TELEPORT_ENABLED = new Value<>(b.comment("Offer the teleport entry in the right-click menu")
                .define("teleportEnabled", true), true);
        DEFAULT_ZOOM = new Value<>(b.comment("Initial map zoom for new worlds (or when rememberZoom is off)")
                .defineInRange("defaultZoom", 0.5, 0.04, 8.0), 0.5);
        REMEMBER_ZOOM = new Value<>(b.comment("Remember the zoom level between map sessions")
                .define("rememberZoom", true), true);
        ARROW_STYLE = new Value<>(b.comment("Arrow marker style: clean or classic")
                .define("arrowStyle", "clean"), "clean");
        DEFAULT_STYLE = new Value<>(b.comment("Default palette: current, gradient, gmt, nautical, gradient-turbo, gradient-soft")
                .define("defaultStyle", "current"), "current");
        SHOW_BIOMES = new Value<>(b.comment("Show the biome boundary overlay when opening the map")
                .define("showBiomes", false), false);
        SHOW_RENDER_DISTANCE = new Value<>(b.comment("Show the player render distance as a yellow box")
                .define("showRenderDistance", false), false);
        BIOME_LABEL_MIN_AREA = new Value<>(b.comment("Minimum biome region area (map pixels) needed to show its name")
                .defineInRange("biomeLabelMinArea", 600, 1, 5000), 600);
        BIOME_LABEL_BACKGROUND = new Value<>(b.comment("Draw a semi-transparent background behind biome labels")
                .define("biomeLabelBackground", true), true);
        b.pop();

        b.comment("Map drawing / 地图绘制").push("map");
        ADAPTIVE_COLOR_RAMP = new Value<>(b.comment("Stretch the land colour ramp to the world's actual height range")
                .define("adaptiveColorRamp", true), true);
        MIN_MAX_RAMP = new Value<>(b.comment("Land only: sea level (green) up to the map's highest point (white)")
                .define("landMaxRamp", true), true);
        DRAW_HILLSHADE = new Value<>(b.comment("Apply hillshade to the terrain")
                .define("drawHillshade", true), true);
        DRAW_WATER_DEPTH = new Value<>(b.comment("Colour water by bathymetry depth")
                .define("drawWaterDepth", true), true);
        AUTO_SEA_LEVEL = new Value<>(b.comment("Read the sea level from the world (adapts to terrain mods)")
                .define("autoSeaLevel", true), true);
        SEA_LEVEL_OVERRIDE = new Value<>(b.comment("Sea level used when autoSeaLevel is disabled")
                .defineInRange("seaLevelOverride", 63, -2048, 2048), 63);
        DRAW_CONTOURS = new Value<>(b.comment("Draw contour lines on land")
                .define("drawContours", true), true);
        CONTOUR_DENSITY = new Value<>(b.comment("How many contour lines span the land height range")
                .defineInRange("contourDensity", 12, 1, 24), 12);
        MIN_CONTOUR_AREA = new Value<>(b.comment("Drop contour fragments smaller than this many pixels")
                .defineInRange("minContourArea", 40, 0, 1000), 40);
        WATER_BLUR = new Value<>(b.comment("Blur the water depth to smooth out sea-floor speckles")
                .define("waterBlur", true), true);
        WATER_BLUR_RADIUS = new Value<>(b.comment("Water blur radius in map pixels")
                .defineInRange("waterBlurRadius", 8, 1, 16), 8);
        WATER_BLUR_PASSES = new Value<>(b.comment("Water blur passes (more = smoother)")
                .defineInRange("waterBlurPasses", 2, 1, 5), 2);
        DENOISE = new Value<>(b.comment("Remove isolated height outliers on land")
                .define("denoise", false), false);
        FILL_HOLES = new Value<>(b.comment("Fill small interior data holes with the local median")
                .define("fillHoles", false), false);
        b.pop();

        b.comment("Data and refresh / 数据与刷新").push("data");
        LIVE_TILES = new Value<>(b.comment("Event-driven tile updates for newly explored chunks")
                .define("liveTiles", true), true);
        MAP_SCALE = new Value<>(b.comment("Map scale: 1 to 1/8 (blocks per pixel)")
                .defineInRange("mapScale", 2, 1, 8), 2);
        MAX_LOD_LEVEL = new Value<>(b.comment("Highest Voxy LOD level to merge (0-4)")
                .defineInRange("maxLodLevel", 4, 0, 4), 4);
        CACHE_KEEP_COUNT = new Value<>(b.comment("How many world caches to keep")
                .defineInRange("cacheKeepCount", 5, 1, 100), 5);
        DISK_CACHE_MAX_MB = new Value<>(b.comment("Disk .vxt cache soft limit in MB")
                .defineInRange("diskCacheMaxMb", 8192, 256, 65536), 8192);
        b.pop();

        b.comment("3D view / 3D 视图").push("view3d");
        FOV_3D = new Value<>(b.comment("3D camera field of view in degrees")
                .defineInRange("fov3d", 55, 30, 110), 55);
        VERTICAL_SCALE_3D = new Value<>(b.comment("Vertical exaggeration of the 3D terrain")
                .defineInRange("verticalScale3d", 1.0, 0.1, 3.0), 1.0);
        MIN_DISTANCE_3D = new Value<>(b.comment("Closest 3D camera distance")
                .defineInRange("minDistance3d", 64, 16, 4096), 64);
        MAX_DISTANCE_3D = new Value<>(b.comment("Farthest 3D camera distance")
                .defineInRange("maxDistance3d", 8192, 512, 32768), 8192);
        ROTATE_SENS_3D = new Value<>(b.comment("3D orbit sensitivity")
                .defineInRange("rotateSens3d", 2.0, 0.2, 5.0), 2.0);
        PAN_SENS_3D = new Value<>(b.comment("3D pan sensitivity")
                .defineInRange("panSens3d", 2.0, 0.2, 5.0), 2.0);
        ZOOM_SENS_3D = new Value<>(b.comment("3D zoom sensitivity")
                .defineInRange("zoomSens3d", 1.0, 0.2, 5.0), 1.0);
        REMEMBER_3D_VIEW = new Value<>(b.comment("Remember the 3D camera per world")
                .define("remember3dView", true), true);
        GPU_TERRAIN_NEAR_DISTANCE = new Value<>(b.comment("Near mesh precision distance (blocks)")
                .defineInRange("gpuTerrainNearDistance", 512, 64, 4096), 512);
        GPU_TERRAIN_MID_DISTANCE = new Value<>(b.comment("Mid mesh precision distance (blocks)")
                .defineInRange("gpuTerrainMidDistance", 2048, 256, 16384), 2048);
        GPU_TERRAIN_NEAR_STEP = new Value<>(b.comment("Near mesh step (map pixels per vertex, must divide 256)")
                .defineInRange("gpuTerrainNearStep", 1, 1, 256), 1);
        GPU_TERRAIN_MID_STEP = new Value<>(b.comment("Mid mesh step (map pixels per vertex, must divide 256)")
                .defineInRange("gpuTerrainMidStep", 4, 1, 256), 4);
        GPU_TERRAIN_FAR_STEP = new Value<>(b.comment("Far mesh step (map pixels per vertex, must divide 256)")
                .defineInRange("gpuTerrainFarStep", 32, 1, 256), 32);
        GPU_TERRAIN_SKIRT_DEPTH = new Value<>(b.comment("Vertical skirt depth that hides LOD seams")
                .defineInRange("gpuTerrainSkirtDepth", 16, 0, 64), 16);
        GPU_TERRAIN_HYSTERESIS_PCT = new Value<>(b.comment("Distance hysteresis percent between mesh steps")
                .defineInRange("gpuTerrainHysteresisPct", 15, 0, 50), 15);
        PLAYER_MARKER_HEIGHT_3D = new Value<>(b.comment("3D player marker height above the terrain")
                .defineInRange("playerMarkerHeight3d", 50.0, 0.0, 200.0), 50.0);
        PLAYER_MARKER_SCALE_3D = new Value<>(b.comment("3D player marker scale")
                .defineInRange("playerMarkerScale3d", 2.0, 0.0, 5.0), 2.0);
        BIOME_LABEL_3D_RANGE = new Value<>(b.comment("Horizontal range (blocks) for 3D biome labels")
                .defineInRange("biomeLabel3dRange", 3000, 200, 20000), 3000);
        AUTO_HIDE_CONTOURS_3D = new Value<>(b.comment("Hide the 2D contour lines while in the 3D view")
                .define("autoHideContours3d", false), false);
        KEEP_3D_TILES_IN_2D = new Value<>(b.comment("Keep built GPU terrain when returning to 2D")
                .define("keep3dTilesIn2d", true), true);
        b.pop();

        b.comment("Performance / 性能").push("perf");
        TILE_RESIDENT_CAP = new Value<>(b.comment("Soft resident tile cap")
                .defineInRange("tileResidentCap", 1024, 64, 65536), 1024);
        GPU_TEXTURE_BUDGET_MB = new Value<>(b.comment("Tile-texture VRAM budget in MB (shared 2D/3D estimate)")
                .defineInRange("gpuTextureBudgetMb", 256, 16, 4096), 256);
        GPU3D_BUILD_THREADS = new Value<>(b.comment("3D mesh build threads (0 = automatic)")
                .defineInRange("gpu3dBuildThreads", 0, 0, 16), 0);
        GPU3D_BATCH_BUILD = new Value<>(b.comment("3D mesh uploads per frame")
                .defineInRange("gpu3dBatchBuild", 10, 1, 32), 10);
        SKIP_WORLD_RENDER = new Value<>(b.comment("Skip rendering the world while the map is open")
                .define("skipWorldRender", true), true);
        RELEASE_TEXTURES_ON_CLOSE = new Value<>(b.comment("Release map textures when closing the map")
                .define("releaseTexturesOnClose", true), true);
        b.pop();

        b.comment("Debug / 调试").push("debug");
        DEBUG_ENABLED = new Value<>(b.comment("Debug: press Q in the map screen to toggle the debug panel")
                .define("debugEnabled", true), true);
        DEBUG_REFRESH_CHAT = new Value<>(b.comment("Print a compact refresh report to the chat")
                .define("debugRefreshChat", false), false);
        DEBUG_VOXY_EVENTS = new Value<>(b.comment("Observe Voxy's live section dirty callbacks")
                .define("debugVoxyEvents", false), false);
        DEBUG_TILE_TRACE = new Value<>(b.comment("Write a per-tile lifecycle trace to logs/")
                .define("debugTileTrace", false), false);
        b.pop();

        SPEC = b.build();
    }

    private VoxyMapConfig() {
    }

    /**
     * Typed config entry that mirrors the 26.2 {@code VoxyMapConfig.Value} contract on top of a
     * NeoForge {@link ModConfigSpec.ConfigValue}.
     */
    public static final class Value<T> {
        private final ModConfigSpec.ConfigValue<T> spec;
        private final T defaultValue;

        private Value(ModConfigSpec.ConfigValue<T> spec, T defaultValue) {
            this.spec = spec;
            this.defaultValue = defaultValue;
        }

        /** factory default, used by the config screen's reset button */
        public T getDefault() {
            return defaultValue;
        }

        public T get() {
            return spec.get();
        }

        @SuppressWarnings("unchecked")
        public void set(Object v) {
            if (v == null) return;
            spec.set((T) coerce(v));
        }

        private Object coerce(Object v) {
            if (defaultValue instanceof Boolean) return Boolean.parseBoolean(v.toString());
            if (defaultValue instanceof Integer) return Integer.parseInt(v.toString());
            if (defaultValue instanceof Double) return Double.parseDouble(v.toString());
            return v.toString();
        }
    }

    /** Persist the current values to the TOML file (called by the config screen's Apply/Done). */
    public static void save() {
        SPEC.save();
    }

    /**
     * No-op on NeoForge: the loader loads/validates the TOML before the client reaches the menu.
     * Kept so the ported 26.2 sources that call it stay unchanged.
     */
    public static void load() {
        // nothing to do
    }
}
