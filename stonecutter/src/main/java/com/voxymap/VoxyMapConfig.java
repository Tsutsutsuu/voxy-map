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
                if (json.isJsonPrimitive()) {
                    v.set(json.getAsJsonPrimitive().getAsString());
                }
            }
        } catch (Exception ignored) {
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
