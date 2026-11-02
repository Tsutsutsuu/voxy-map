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

    public static final Value<Boolean> AUTO_REFRESH = new Value<>("autoRefresh", true);
    /** gentle = closed-map background refresh only; fast = refresh only while the map is open; off = no auto refresh. */
    public static final Value<String> AUTO_REFRESH_MODE = new Value<>("autoRefreshMode", "gentle");
    /** CPU budget for the gentle background lane (milliseconds per 10 seconds). */
    public static final Value<Integer> GENTLE_BUDGET_MS_PER_10S = new Value<>("gentleBudgetMsPer10s", 100);
    /** Seconds the player must stand still before the gentle lane may run. */
    public static final Value<Integer> GENTLE_IDLE_SECONDS = new Value<>("gentleIdleSeconds", 2);
    /** How close unexplored map cells must be for a frontier refresh. */
    public static final Value<Integer> FRONTIER_DISTANCE_CHUNKS = new Value<>("frontierDistanceChunks", 16);
    /** Open-map time fallback when the DB changed but the frontier is far. */
    public static final Value<Integer> OPEN_TIME_FALLBACK_MINUTES = new Value<>("openTimeFallbackMinutes", 5);
    /** Background time fallback (only while standing still). */
    public static final Value<Integer> IDLE_TIME_FALLBACK_MINUTES = new Value<>("idleTimeFallbackMinutes", 30);
    public static final Value<Integer> FRONTIER_COOLDOWN_SECONDS = new Value<>("frontierCooldownSeconds", 15);
    public static final Value<Integer> FRONTIER_BACKOFF_SECONDS = new Value<>("frontierBackoffSeconds", 60);
    /** Above this many changed sections the incremental merge gives up and a full rebuild is used. */
    public static final Value<Integer> INCREMENTAL_MAX_CHANGED_PERCENT = new Value<>("incrementalMaxChangedPercent", 60);
    /** Automatic refreshes only read/decode changed Voxy sections and patch the map. */
    public static final Value<Boolean> INCREMENTAL_REFRESH = new Value<>("incrementalRefresh", true);
    public static final Value<Integer> REFRESH_DISTANCE_CHUNKS = new Value<>("refreshDistanceChunks", 128);
    public static final Value<Integer> INITIAL_REFRESH_DELAY_MINUTES = new Value<>("initialRefreshDelayMinutes", 3);
    public static final Value<String> DEFAULT_STYLE = new Value<>("defaultStyle", "current");
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
    public static final Value<Boolean> FILL_HOLES = new Value<>("fillHoles", true);
    public static final Value<Boolean> DRAW_CONTOURS = new Value<>("drawContours", true);
    public static final Value<Boolean> DRAW_HILLSHADE = new Value<>("drawHillshade", true);
    public static final Value<Boolean> DRAW_WATER_DEPTH = new Value<>("drawWaterDepth", true);
    public static final Value<Boolean> SHOW_COORDINATES = new Value<>("showCoordinates", true);
    public static final Value<Boolean> TELEPORT_ENABLED = new Value<>("teleportEnabled", true);
    public static final Value<Boolean> DEBUG_ENABLED = new Value<>("debugEnabled", true);
    /** Test aid: print a compact refresh report to the chat. */
    public static final Value<Boolean> DEBUG_REFRESH_CHAT = new Value<>("debugRefreshChat", false);
    /** Test aid: also print per-stage timings to the chat. */
    public static final Value<Boolean> DEBUG_REFRESH_VERBOSE = new Value<>("debugRefreshVerbose", false);
    /** PoC: observe Voxy's live section dirty callbacks instead of polling RocksDB. */
    public static final Value<Boolean> DEBUG_VOXY_EVENTS = new Value<>("debugVoxyEvents", false);
    /** Milestone 1 of the tiled map: draw independent tiles instead of one huge texture. */
    public static final Value<Boolean> TILE_RENDER = new Value<>("tileRender", true);
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
    public static final Value<Integer> RENDER_SCALE_3D = new Value<>("renderScale3d", 100);
    public static final Value<Boolean> ANTIALIAS_3D = new Value<>("antialias3d", true);
    public static final Value<Integer> RENDER_THREADS_3D = new Value<>("renderThreads3d", 8);
    public static final Value<Double> VERTICAL_SCALE_3D = new Value<>("verticalScale3d", 1.0);
    public static final Value<Integer> LOD_NEAR_RADIUS = new Value<>("lodNearRadius", 1500);
    public static final Value<Integer> LOD_MID_RADIUS = new Value<>("lodMidRadius", 6000);
    public static final Value<Integer> LOD_STEP_NEAR = new Value<>("lodStepNear", 4);
    public static final Value<Integer> LOD_STEP_MID = new Value<>("lodStepMid", 32);
    public static final Value<Integer> LOD_STEP_FAR = new Value<>("lodStepFar", 64);
    public static final Value<Integer> LOD_REBUILD_DISTANCE = new Value<>("lodRebuildDistance", 300);
    public static final Value<Integer> LOD_ZOOM_REBUILD_PCT = new Value<>("lodZoomRebuildPct", 25);
    public static final Value<Integer> FOV_3D = new Value<>("fov3d", 55);
    public static final Value<Integer> MIN_DISTANCE_3D = new Value<>("minDistance3d", 64);
    public static final Value<Integer> MAX_DISTANCE_3D = new Value<>("maxDistance3d", 8192);
    public static final Value<Double> ROTATE_SENS_3D = new Value<>("rotateSens3d", 2.0);
    public static final Value<Double> PAN_SENS_3D = new Value<>("panSens3d", 2.0);
    public static final Value<Double> ZOOM_SENS_3D = new Value<>("zoomSens3d", 2.0);
    public static final Value<Boolean> REMEMBER_3D_VIEW = new Value<>("remember3dView", true);
    public static final Value<Boolean> AUTO_HIDE_CONTOURS_3D = new Value<>("autoHideContours3d", false);
    public static final Value<Boolean> LOD_DEBUG_COLORS_3D = new Value<>("lodDebugColors3d", false);
    public static final Value<Double> PLAYER_MARKER_HEIGHT_3D = new Value<>("playerMarkerHeight3d", 50.0);
    public static final Value<Double> PLAYER_MARKER_SCALE_3D = new Value<>("playerMarkerScale3d", 2.6);
    /** pixel count above which the map asks before loading (memory guard) */
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
