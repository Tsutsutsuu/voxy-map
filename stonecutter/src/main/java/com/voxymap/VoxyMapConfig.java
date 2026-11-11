package com.voxymap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric-side configuration. Keeps the same field names and {@code .get()} access pattern as the
 * NeoForge build so MapData/MapScreen port without changes; persisted as JSON under
 * {@code config/voxymap-client.json}.
 */
public final class VoxyMapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "voxymap-client.json";

    // ---- legacy global-refresh options (no effect on the 2D tile path) --------------------------
    // The unified tile renderer streams Voxy sections directly and never runs the old global
    // snapshot refresh lane, so these knobs are dead. They are kept (deprecated) only because the
    // legacy code paths still reference them; @Deprecated also makes load()/save() ignore them, so
    // they stop appearing in the config file. Remove together with the legacy refresh pipeline.
    /** @deprecated no effect: the 2D tile path has no global auto refresh. */
    @Deprecated
    public static final Value<Boolean> AUTO_REFRESH = new Value<>("autoRefresh", true);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<String> AUTO_REFRESH_MODE = new Value<>("autoRefreshMode", "gentle");
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> GENTLE_BUDGET_MS_PER_10S = new Value<>("gentleBudgetMsPer10s", 100);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> GENTLE_IDLE_SECONDS = new Value<>("gentleIdleSeconds", 2);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> FRONTIER_DISTANCE_CHUNKS = new Value<>("frontierDistanceChunks", 16);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> OPEN_TIME_FALLBACK_MINUTES = new Value<>("openTimeFallbackMinutes", 5);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> IDLE_TIME_FALLBACK_MINUTES = new Value<>("idleTimeFallbackMinutes", 30);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> FRONTIER_COOLDOWN_SECONDS = new Value<>("frontierCooldownSeconds", 15);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> FRONTIER_BACKOFF_SECONDS = new Value<>("frontierBackoffSeconds", 60);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Integer> INCREMENTAL_MAX_CHANGED_PERCENT = new Value<>("incrementalMaxChangedPercent", 60);
    /** @deprecated no effect: see {@link #AUTO_REFRESH}. */
    @Deprecated
    public static final Value<Boolean> INCREMENTAL_REFRESH = new Value<>("incrementalRefresh", true);
    /** @deprecated no effect: the "wait for SeedGen" dialog is disabled in tile mode. */
    @Deprecated
    public static final Value<Integer> INITIAL_REFRESH_DELAY_MINUTES = new Value<>("initialRefreshDelayMinutes", 3);
    public static final Value<String> DEFAULT_STYLE = new Value<>("defaultStyle", "blocks");
    public static final Value<Integer> MAP_SCALE = new Value<>("mapScale", 2);
    public static final Value<Integer> MAX_LOD_LEVEL = new Value<>("maxLodLevel", 4);
    public static final Value<Boolean> AUTO_SEA_LEVEL = new Value<>("autoSeaLevel", true);
    public static final Value<Integer> SEA_LEVEL_OVERRIDE = new Value<>("seaLevelOverride", 63);
    public static final Value<Boolean> ADAPTIVE_COLOR_RAMP = new Value<>("adaptiveColorRamp", true);
    public static final Value<Boolean> MIN_MAX_RAMP = new Value<>("landMaxRamp", true);
    public static final Value<Boolean> WATER_BLUR = new Value<>("waterBlur", true);
    public static final Value<Integer> WATER_BLUR_RADIUS = new Value<>("waterBlurRadius", 8);
    public static final Value<Integer> WATER_BLUR_PASSES = new Value<>("waterBlurPasses", 2);
    public static final Value<Boolean> DENOISE = new Value<>("denoise", false);
    public static final Value<Boolean> FILL_HOLES = new Value<>("fillHoles", false);
    public static final Value<Boolean> DRAW_CONTOURS = new Value<>("drawContours", true);
    public static final Value<Boolean> DRAW_HILLSHADE = new Value<>("drawHillshade", true);
    /**
     * Biome-tinted colours for the "blocks" style: grass/foliage use the biome colour (vanilla
     * {@code Biome#getGrassColor(x,z)} / {@code getFoliageColor()}) and water is multiplied by the
     * biome water colour. Off = the palette's static class colours and plain current water.
     */
    public static final Value<Boolean> BIOME_COLORS = new Value<>("biomeColors", true);
    public static final Value<Boolean> DRAW_WATER_DEPTH = new Value<>("drawWaterDepth", true);
    public static final Value<Boolean> SHOW_COORDINATES = new Value<>("showCoordinates", true);
    public static final Value<Boolean> TELEPORT_ENABLED = new Value<>("teleportEnabled", true);
    public static final Value<Boolean> DEBUG_ENABLED = new Value<>("debugEnabled", true);
    /** Test aid: print a compact refresh report to the chat. */
    public static final Value<Boolean> DEBUG_REFRESH_CHAT = new Value<>("debugRefreshChat", false);
    /**
     * @deprecated no effect: the verbose lines only existed in the retired refresh lanes; the
     *     remaining live chat messages are gated by {@link #DEBUG_REFRESH_CHAT}.
     */
    @Deprecated
    public static final Value<Boolean> DEBUG_REFRESH_VERBOSE = new Value<>("debugRefreshVerbose", false);
    /** PoC: observe Voxy's live section dirty callbacks instead of polling RocksDB. */
    public static final Value<Boolean> DEBUG_VOXY_EVENTS = new Value<>("debugVoxyEvents", false);
    /** Writes a per-tile lifecycle trace to logs/voxymap-tile-*.log. */
    public static final Value<Boolean> DEBUG_TILE_TRACE = new Value<>("debugTileTrace", false);
    /** Event-driven 2D tile updates for newly explored chunks. */
    public static final Value<Boolean> LIVE_TILES = new Value<>("liveTiles", true);
    /** Soft resident tile cap; eviction only happens above this, down to ~70%. */
    public static final Value<Integer> TILE_RESIDENT_CAP = new Value<>("tileResidentCap", 1024);
    /** Estimated VRAM budget for tile textures (256×256 RGBA ≈ 256 KiB each). */
    public static final Value<Integer> GPU_TEXTURE_BUDGET_MB = new Value<>("gpuTextureBudgetMb", 256);
    /** Experimental: build 3D mesh per map tile and rebuild only changed tiles. */
    /** Disk .vxt cache soft limit. Oldest files are removed first. */
    public static final Value<Integer> DISK_CACHE_MAX_MB = new Value<>("diskCacheMaxMb", 8192);
    public static final Value<Boolean> SKIP_WORLD_RENDER = new Value<>("skipWorldRender", true);
    public static final Value<Integer> CACHE_KEEP_COUNT = new Value<>("cacheKeepCount", 5);
    public static final Value<Integer> MIN_CONTOUR_AREA = new Value<>("minContourArea", 40);
    public static final Value<Integer> CONTOUR_DENSITY = new Value<>("contourDensity", 12);
    public static final Value<Boolean> SHOW_BIOMES = new Value<>("showBiomes", false);
    public static final Value<String> ARROW_STYLE = new Value<>("arrowStyle", "clean");
    public static final Value<Boolean> SHOW_RENDER_DISTANCE = new Value<>("showRenderDistance", false);
    public static final Value<Boolean> RELEASE_TEXTURES_ON_CLOSE = new Value<>("releaseTexturesOnClose", true);
    public static final Value<Integer> BIOME_LABEL_MIN_AREA = new Value<>("biomeLabelMinArea", 600);
    public static final Value<Boolean> BIOME_LABEL_BACKGROUND = new Value<>("biomeLabelBackground", true);
    public static final Value<Double> DEFAULT_ZOOM = new Value<>("defaultZoom", 0.5);
    public static final Value<Boolean> REMEMBER_ZOOM = new Value<>("rememberZoom", true);
    public static final Value<Boolean> SHOW_HINT = new Value<>("showHint", true);

    // ---- 3D map view ----
    public static final Value<Double> VERTICAL_SCALE_3D = new Value<>("verticalScale3d", 1.0);
    /** GPU terrain mesh precision (not Minecraft's chunk LOD). */
    public static final Value<Integer> GPU_TERRAIN_NEAR_DISTANCE = new Value<>("gpuTerrainNearDistance", 512);
    public static final Value<Integer> GPU_TERRAIN_MID_DISTANCE = new Value<>("gpuTerrainMidDistance", 2048);
    public static final Value<Integer> GPU_TERRAIN_NEAR_STEP = new Value<>("gpuTerrainNearStep", 1);
    public static final Value<Integer> GPU_TERRAIN_MID_STEP = new Value<>("gpuTerrainMidStep", 4);
    public static final Value<Integer> GPU_TERRAIN_FAR_STEP = new Value<>("gpuTerrainFarStep", 32);
    public static final Value<Integer> GPU_TERRAIN_SKIRT_DEPTH = new Value<>("gpuTerrainSkirtDepth", 16);
    public static final Value<Integer> GPU_TERRAIN_HYSTERESIS_PCT = new Value<>("gpuTerrainHysteresisPct", 15);
    public static final Value<Integer> GPU3D_BUILD_THREADS = new Value<>("gpu3dBuildThreads", 0);
    public static final Value<Integer> GPU3D_BATCH_BUILD = new Value<>("gpu3dBatchBuild", 10);
    public static final Value<Boolean> KEEP_3D_TILES_IN_2D = new Value<>("keep3dTilesIn2d", true);
    public static final Value<Integer> FOV_3D = new Value<>("fov3d", 55);
    public static final Value<Integer> MIN_DISTANCE_3D = new Value<>("minDistance3d", 64);
    public static final Value<Integer> MAX_DISTANCE_3D = new Value<>("maxDistance3d", 8192);
    public static final Value<Double> ROTATE_SENS_3D = new Value<>("rotateSens3d", 2.0);
    public static final Value<Double> PAN_SENS_3D = new Value<>("panSens3d", 2.0);
    public static final Value<Double> ZOOM_SENS_3D = new Value<>("zoomSens3d", 1.0);
    public static final Value<Boolean> REMEMBER_3D_VIEW = new Value<>("remember3dView", true);
    public static final Value<Boolean> AUTO_HIDE_CONTOURS_3D = new Value<>("autoHideContours3d", false);
    public static final Value<Double> PLAYER_MARKER_HEIGHT_3D = new Value<>("playerMarkerHeight3d", 50.0);
    public static final Value<Double> PLAYER_MARKER_SCALE_3D = new Value<>("playerMarkerScale3d", 2.0);
    /** pixel count above which the map asks before loading (memory guard) */
    /** @deprecated no effect: the legacy snapshot memory guard is unreachable in tile mode. */
    @Deprecated
    public static final Value<Integer> MAX_MAP_PIXELS = new Value<>("maxMapPixels", 120_000_000);
    /** horizontal range (blocks) within which 3D biome labels are drawn */
    public static final Value<Integer> BIOME_LABEL_3D_RANGE = new Value<>("biomeLabel3dRange", 3000);

    private VoxyMapConfig() {
    }

    /** Minimal typed config entry, mirrors ModConfigSpec's .get()/set() usage. */
    public static final class Value<T> {
        private final String key;
        private final T defaultValue;
        private T value;

        Value(String key, T defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        /** factory default, used by the config screen's reset button */
        public T getDefault() {
            return defaultValue;
        }

        @SuppressWarnings("unchecked")
        public void set(Object v) {
            if (v != null) {
                value = (T) coerce(v);
                // callers persist explicitly (the config screen works on a draft and saves on apply)
            }
        }

        private Object coerce(Object v) {
            if (defaultValue instanceof Boolean) return Boolean.parseBoolean(v.toString());
            if (defaultValue instanceof Integer) return Integer.parseInt(v.toString());
            if (defaultValue instanceof Double) return Double.parseDouble(v.toString());
            return v.toString();
        }
    }

    private static Path configFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    public static void load() {
        Path file = configFile();
        if (!Files.exists(file)) {
            save();
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) return;
            for (java.lang.reflect.Field f : VoxyMapConfig.class.getDeclaredFields()) {
                if (f.getType() != Value.class) continue;
                if (f.isAnnotationPresent(Deprecated.class)) continue;
                f.setAccessible(true);
                Value<?> v = (Value<?>) f.get(null);
                if (v == null || !root.has(v.key)) continue;
                JsonElement json = root.get(v.key);
                if (!json.isJsonPrimitive()) continue;
                try {
                    v.set(json.getAsJsonPrimitive().getAsString());
                } catch (RuntimeException e) {
                    // one malformed entry must not abort loading every field after it (which then
                    // would be saved back as defaults and silently reset the player's options)
                    System.err.println("[voxymap] config value '" + v.key + "' is invalid: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[voxymap] config file could not be read, using defaults: " + e);
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        for (java.lang.reflect.Field f : VoxyMapConfig.class.getDeclaredFields()) {
            if (f.getType() != Value.class) continue;
            if (f.isAnnotationPresent(Deprecated.class)) continue;
            try {
                f.setAccessible(true);
                Value<?> v = (Value<?>) f.get(null);
                if (v == null) continue;
                Object val = v.value;
                if (val instanceof Boolean b) root.addProperty(v.key, b);
                else if (val instanceof Integer i) root.addProperty(v.key, i);
                else if (val instanceof Double d) root.addProperty(v.key, d);
                else root.addProperty(v.key, String.valueOf(val));
            } catch (Exception ignored) {
            }
        }
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException ignored) {
        }
    }
}

