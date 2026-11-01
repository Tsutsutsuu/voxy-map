package com.voxymap;

import com.github.luben.zstd.Zstd;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Reads the Voxy / SeedGen world storage (RocksDB + ZSTD) and builds a full
 * multi-level relief map as a Minecraft texture.
 *
 * <p>Merging rules (land and water are decided separately):</p>
 * <ul>
 *     <li>surface (top): finest LOD level wins; within a level the highest top wins;
 *         a solid column reaching the section ceiling is a buried slice, not terrain</li>
 *     <li>floor: highest solid anywhere in the column below the surface (sea floor for water)</li>
 *     <li>fallbacks: highest solid when no credible surface exists, then the local median
 *         for interior holes only (the map rim stays empty)</li>
 * </ul>
 */
public final class MapData {
    public static final MapData INSTANCE = new MapData();

    private static final int VOL = 32 * 32 * 32;
    private static final long LONG_EMPTY = Long.MIN_VALUE;
    private static final int NO_SOLID = -32768;
    private static final int NO_TOP = -32767;
    private static final int BATCH = 2000;
    private static final int CACHE_VERSION = 6;
    private static final int FALLBACK_SEA = 63;

    private static final class Snapshot {
        final long[] pixels;
        final int width, height;
        final long minWX, minWZ;
        final int pixelBlocks;
        final Map<Integer, String> biomeNames;
        /** identity of this snapshot: the colour cache must belong to the same snapshot */
        final long stamp;

        Snapshot(long[] pixels, int width, int height, long minWX, long minWZ, int pixelBlocks,
                 Map<Integer, String> biomeNames, long stamp) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.minWX = minWX;
            this.minWZ = minWZ;
            this.pixelBlocks = pixelBlocks;
            this.biomeNames = biomeNames;
            this.stamp = stamp;
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "voxymap-loader");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voxymap-manager");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean requested = new AtomicBoolean();

    private volatile Snapshot snap;
    private volatile NativeImage biomeImage;
    private volatile Identifier biomeTextureId;
    private volatile int[] biomeLabelsFlat = new int[0];
    /** snapshot the label array was computed from: labels must not be reused across a refresh */
    private volatile Snapshot biomeLabelsSnapshot;
    private volatile String style = MapStyles.NAMES[0];
    /** Per-world overlay toggles, persisted in the world's cache sidecar file (see loadUiState). */
    private volatile boolean biomeOverlay;
    private volatile boolean renderDistanceOverlay;
    /** Per-world map scale: 1..8 blocks per pixel (1:1 to 1:8). */
    private volatile int mapScale = 2;
    /** Per-world zoom level, restored on reopen when rememberZoom is on. */
    private volatile double zoom = 0.5;
    private volatile Identifier textureId;
    private volatile Identifier textureIdSmall;
    /**
     * Immutable rendered colour buffer. The pixels, their dimensions and the epoch are published as
     * one volatile reference: readers (LOD build, 3D rasteriser) never see a new array with stale
     * dimensions, which used to produce an AIOOBE in the background mesh builder.
     */
    private volatile ColorFrame colorFrame;
    /** biome border pixels (w*h ARGB) for the 3D overlay / labels */
    private volatile int[] biomeEdgePx;
    /** bump when the biome border colour changes: forces the cached texture/edges to rebuild */
    private static final int BIOME_EDGE_VERSION = 2;
    private volatile int biomeEdgeVersion;
    private final AtomicBoolean buildingTextures = new AtomicBoolean();
    private final AtomicBoolean buildingBiome = new AtomicBoolean();
    /** set when a texture build was requested while another one was running (retried on completion) */
    private volatile boolean textureBuildPending;
    /** actual size of the currently registered map texture */
    private volatile int texW, texH;
    private volatile boolean loading;
    private volatile boolean styleChanging;
    /** last 3D view mode for this world (per-world ui state) */
    private volatile boolean view3d;
    /** remembered orbit camera (per-world ui state) */
    private volatile float view3dYaw = (float) Math.toRadians(45);
    private volatile float view3dPitch = (float) Math.toRadians(-50);
    private volatile float view3dDistance = 1500f;
    private volatile boolean view3dCameraSaved;
    private volatile int progress = -1;
    private volatile String error = "";
    /** non-zero while the "map too large" warning dialog should be shown */
    public volatile long oversizePixels;
    public volatile long oversizeLimit;
    public volatile int oversizeSafePixelBlocks;
    /** 0 = derive from mapScale, >0 = forced grid (safe mode), -1 = force the oversized grid */
    private volatile int pixelBlocksOverride;
    private volatile boolean allowOversize;
    private volatile Path lastStorage;
    /** World the current snapshot was built from, used to reuse it when rejoining that world. */
    private volatile Path snapStorage;
    /** Palette the current colour frame was rendered with. */
    private volatile String lastRenderedStyle;
    private boolean styleInitialized;
    private boolean initialRefreshDone;
    /** Bumped whenever the client leaves/joins a world, so stale build results can be dropped. */
    private volatile int worldEpoch;
    /** temporary override for the contour setting (the 3D view hides them); null = use config */
    public static volatile Boolean contourOverride;
    /** bumped on every texture upload request; stale async builds compare against it */
    private volatile int texEpoch;
    /** 3D view: skip building biome labels during colour renders */
    public volatile boolean skipBiomeLabels;

    /** set when the last map build/render ran out of heap (shown as a hint instead of a raw error) */
    public volatile boolean outOfMemory;

    private long sessionStartMs;
    /** debounces automatic refreshes so a failing rebuild cannot restart every tick */
    private long lastAutoRefreshMs;
    private double lastUpdateX = Double.NaN, lastUpdateZ = Double.NaN;

    private MapData() {
    }

    /** Immutable rendered colour buffer, published as one object (see {@link #colorFrame}). */
    public static final class ColorFrame {
        public final int[] rgb;
        public final int width;
        public final int height;
        public final int epoch;

        ColorFrame(int[] rgb, int width, int height, int epoch) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.epoch = epoch;
        }
    }

    // ------------------------------------------------------------- state

    public boolean isLoading() {
        return loading;
    }

    public int getProgress() {
        return Math.max(0, Math.min(100, progress));
    }

    public boolean isStyleChanging() {
        return styleChanging;
    }

    public String getError() {
        return error;
    }

    public String getStyle() {
        return style;
    }

    public int getTextureWidth() {
        return texW;
    }

    public int getTextureHeight() {
        return texH;
    }

    public Identifier getTexture() {
        return textureId;
    }

    public Identifier getTextureSmall() {
        return textureIdSmall;
    }

    public Identifier getBiomeTexture() {
        return biomeTextureId;
    }

    /** Biome label positions as flat triples: {mapX, mapZ, biomeId} per label. */
    public int[] getBiomeLabelsFlat() {
        return biomeLabelsFlat;
    }

    public String getBiomeName(int biomeId) {
        Snapshot s = snap;
        return s == null ? null : s.biomeNames.get(biomeId);
    }

    private final java.util.Map<Integer, String> biomeKeyCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Translation key for a biome ("biome.minecraft.ocean") or "" when none exists.
     * Modded biome entries in the Voxy id table often lost their namespace, so the
     * client biome registry is used to restore it by path.
     */
    public String getBiomeTranslationKey(int biomeId) {
        String cached = biomeKeyCache.get(biomeId);
        if (cached != null) return cached;
        String name = getBiomeName(biomeId);
        if (name == null) {
            biomeKeyCache.put(biomeId, "");
            return "";
        }
        String path = name.replaceAll("^[:.]+", "");
        String key = null;
        if (!name.startsWith(":")) {
            key = "biome." + name.replace(':', '.');
        } else {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
                for (Identifier rl : registry.keySet()) {
                    if (rl.getPath().equals(path)) {
                        key = "biome." + rl.getNamespace() + "." + rl.getPath();
                        break;
                    }
                }
            }
        }
        biomeKeyCache.put(biomeId, key == null ? "" : key);
        return key == null ? "" : key;
    }

    /** Pretty fallback name for a biome without any translation. */
    public String getBiomeFallbackName(int biomeId) {
        String name = getBiomeName(biomeId);
        if (name == null) return null;
        String path = name.replaceAll("^[:.]+", "");
        return path.isEmpty() ? name : path;
    }

    public void invalidateBiomeKeyCache() {
        biomeKeyCache.clear();
    }

    public boolean isReady() {
        return snap != null && textureId != null;
    }

    public int getMapWidth() {
        Snapshot s = snap;
        return s == null ? 0 : s.width;
    }

    public int getPixelBlocks() {
        Snapshot s = snap;
        return s == null ? 2 : s.pixelBlocks;
    }

    public int getMapHeight() {
        Snapshot s = snap;
        return s == null ? 0 : s.height;
    }

    public long getMinWX() {
        Snapshot s = snap;
        return s == null ? 0 : s.minWX;
    }

    public long getMinWZ() {
        Snapshot s = snap;
        return s == null ? 0 : s.minWZ;
    }

    /** Ground height at a world position, or sea level when unknown. */
    public int heightAt(int worldX, int worldZ) {
        Snapshot s = snap;
        if (s == null) return seaLevel();
        int gx = (int) Math.floorDiv((long) worldX - s.minWX, s.pixelBlocks);
        int gz = (int) Math.floorDiv((long) worldZ - s.minWZ, s.pixelBlocks);
        if (gx < 0 || gx >= s.width || gz < 0 || gz >= s.height) return seaLevel();
        long p = s.pixels[gz * s.width + gx];
        if (p == LONG_EMPTY) return seaLevel();
        int top = (int) ((p >>> 24) & 0xFFFF) - 32768;
        int floor = (int) (p & 0xFFFF) - 32768;
        if (top == NO_TOP) return floor != NO_SOLID ? floor : seaLevel();
        return top;
    }


    /** True when the map snapshot has no data at this position (unexplored). */
    public boolean isEmptyAt(int worldX, int worldZ) {
        Snapshot s = snap;
        if (s == null) return true;
        int gx = (int) Math.floorDiv((long) worldX - s.minWX, s.pixelBlocks);
        int gz = (int) Math.floorDiv((long) worldZ - s.minWZ, s.pixelBlocks);
        if (gx < 0 || gx >= s.width || gz < 0 || gz >= s.height) return true;
        long p = s.pixels[gz * s.width + gx];
        if (p == LONG_EMPTY) return true;
        // a column whose only data is a buried, top-less section is drawn as transparent by the 2D
        // renderer; treat it as empty here too so the 3D mesh does not add black sea-level triangles
        int top = (int) ((p >>> 24) & 0xFFFF) - 32768;
        int floor = (int) (p & 0xFFFF) - 32768;
        return top == NO_TOP && floor == NO_SOLID;
    }

    /** True when the top of the column at this position is water. */
    public boolean isWaterAt(int worldX, int worldZ) {
        Snapshot s = snap;
        if (s == null) return false;
        int gx = (int) Math.floorDiv((long) worldX - s.minWX, s.pixelBlocks);
        int gz = (int) Math.floorDiv((long) worldZ - s.minWZ, s.pixelBlocks);
        if (gx < 0 || gx >= s.width || gz < 0 || gz >= s.height) return false;
        long p = s.pixels[gz * s.width + gx];
        return p != LONG_EMPTY && (p & (1L << 52)) != 0;
    }

    /** Increments whenever the rendered colour buffer is replaced. */
    public int getColorEpoch() {
        ColorFrame f = colorFrame;
        return f == null ? 0 : f.epoch;
    }

    /** The colour buffer together with the dimensions it was rendered at (null before first render). */
    public ColorFrame getColorFrame() {
        return colorFrame;
    }

    /** Rendered RGB colour at a map pixel (0 when no data yet). */
    public int getRgbAt(int mapX, int mapZ) {
        ColorFrame f = colorFrame;
        if (f == null || f.width <= 0 || f.height <= 0) return 0;
        if (mapX < 0 || mapZ < 0 || mapX >= f.width || mapZ >= f.height) return 0;
        return f.rgb[mapZ * f.width + mapX];
    }

    /** Biome id at a world position, or -1 when unknown. */
    public int biomeAt(int worldX, int worldZ) {
        Snapshot s = snap;
        if (s == null) return -1;
        int gx = (int) Math.floorDiv((long) worldX - s.minWX, s.pixelBlocks);
        int gz = (int) Math.floorDiv((long) worldZ - s.minWZ, s.pixelBlocks);
        if (gx < 0 || gx >= s.width || gz < 0 || gz >= s.height) return -1;
        long p = s.pixels[gz * s.width + gx];
        if (p == LONG_EMPTY) return -1;
        return (int) ((p >>> 53) & 0x1FF);
    }

    /** The map reads Voxy's overworld LOD storage, so other dimensions are not supported yet. */
    public boolean isOverworld() {
        var level = Minecraft.getInstance().level;
        return level == null || level.dimension() == Level.OVERWORLD;
    }

    /** Sea level: read from the world so terrain mods are handled automatically. */
    public int seaLevel() {        if (VoxyMapConfig.AUTO_SEA_LEVEL.get()) {
            var level = Minecraft.getInstance().level;
            if (level != null) return level.getSeaLevel();
            return FALLBACK_SEA;
        }
        return VoxyMapConfig.SEA_LEVEL_OVERRIDE.get();
    }

    // ------------------------------------------------------- per-world UI state

    public boolean isBiomeOverlay() {
        return biomeOverlay;
    }

    public boolean isRenderDistanceOverlay() {
        return renderDistanceOverlay;
    }

    public void setBiomeOverlay(boolean value) {
        biomeOverlay = value;
        saveUiState();
    }

    public void setRenderDistanceOverlay(boolean value) {
        renderDistanceOverlay = value;
        saveUiState();
    }

    /** Blocks per pixel for the current world: 1 (1:1, sharpest) .. 8 (1:8, coarsest). */
    public double getZoom() {
        return zoom;
    }

    public void setZoom(double value) {
        zoom = Math.max(0.04, Math.min(8.0, value));
        saveUiState();
    }

    public int getMapScale() {
        return mapScale;
    }

    public void setMapScale(int value) {
        mapScale = Math.max(1, Math.min(8, value));
        pixelBlocksOverride = 0;
        allowOversize = false;
        saveUiState();
    }

    private int effectivePixelBlocks() {
        if (pixelBlocksOverride > 0) return pixelBlocksOverride;
        return Math.max(1, Math.min(8, mapScale));
    }

    /** Sidecar file next to the world cache: {@code <storage-hash>.state}. */
    private static Path uiStateFile(Path storage) {
        return worldCacheDir(storage).resolve("state.properties");
    }

    /**
     * Different worlds want different palettes and overlays, so the UI state is stored next to that
     * world's cache and restored when the world is opened. Falls back to the configured defaults
     * when the world has no state file yet.
     */
    private void loadUiState() {
        Path storage = lastStorage;
        if (storage != null) {
            Path file = uiStateFile(storage);
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    Properties props = new Properties();
                    props.load(in);
                    String savedStyle = props.getProperty("style");
                    if (savedStyle != null) {
                        for (String name : MapStyles.NAMES) {
                            if (name.equals(savedStyle)) {
                                style = name;
                                break;
                            }
                        }
                    }
                    String biomes = props.getProperty("biomes");
                    if (biomes != null) biomeOverlay = Boolean.parseBoolean(biomes);
                    String distance = props.getProperty("renderDistance");
                    if (distance != null) renderDistanceOverlay = Boolean.parseBoolean(distance);
                    String scale = props.getProperty("mapScale");
                    if (scale != null) {
                        try {
                            mapScale = Math.max(1, Math.min(8, Integer.parseInt(scale.trim())));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String savedView3d = props.getProperty("view3d");
                    if (savedView3d != null) view3d = Boolean.parseBoolean(savedView3d);
                    String sYaw = props.getProperty("view3dYaw");
                    String sPitch = props.getProperty("view3dPitch");
                    String sDist = props.getProperty("view3dDistance");
                    if (sYaw != null && sPitch != null && sDist != null) {
                        try {
                            view3dYaw = Float.parseFloat(sYaw);
                            view3dPitch = Float.parseFloat(sPitch);
                            view3dDistance = Math.max(1f, Float.parseFloat(sDist));
                            view3dCameraSaved = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String savedZoom = props.getProperty("zoom");
                    if (savedZoom != null) {
                        try {
                            zoom = Math.max(0.04, Math.min(8.0, Double.parseDouble(savedZoom.trim())));
                        } catch (NumberFormatException ignored) {
                        }
                    } else {
                        zoom = VoxyMapConfig.DEFAULT_ZOOM.get();
                    }
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        applyConfiguredDefaults();
    }

    private void applyConfiguredDefaults() {
        String defaultStyle = VoxyMapConfig.DEFAULT_STYLE.get();
        for (String name : MapStyles.NAMES) {
            if (name.equals(defaultStyle)) {
                style = name;
                break;
            }
        }
        biomeOverlay = VoxyMapConfig.SHOW_BIOMES.get();
        renderDistanceOverlay = VoxyMapConfig.SHOW_RENDER_DISTANCE.get();
        mapScale = VoxyMapConfig.MAP_SCALE.get();
        zoom = VoxyMapConfig.DEFAULT_ZOOM.get();
    }

    /**
     * Loads the per-world UI state of the world that is active now, on the calling (main) thread.
     * Called when the map screen opens so remember3dView/rememberZoom do not consult the previous
     * world's values before the async map build had a chance to load them.
     */
    public void loadCurrentWorldUiState() {
        // already loaded for this world (map reopened, window resized, back from the config screen)
        if (lastStorage != null) return;
        if (!isOverworld()) return;
        Path storage = findStorage();
        if (storage == null) return;
        lastStorage = storage;
        loadUiState();
    }

    private void saveUiState() {
        Path storage = lastStorage;
        if (storage == null) return;
        try {
            Path file = uiStateFile(storage);
            Files.createDirectories(file.getParent());
            Properties props = new Properties();
            props.setProperty("style", style);
            props.setProperty("biomes", Boolean.toString(biomeOverlay));
            props.setProperty("renderDistance", Boolean.toString(renderDistanceOverlay));
            props.setProperty("mapScale", Integer.toString(mapScale));
            props.setProperty("view3d", Boolean.toString(view3d));
            if (view3dCameraSaved) {
                props.setProperty("view3dYaw", Float.toString(view3dYaw));
                props.setProperty("view3dPitch", Float.toString(view3dPitch));
                props.setProperty("view3dDistance", Float.toString(view3dDistance));
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "voxymap per-world ui state");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Diagnostic lines for the in-game debug panel (Q when debugEnabled). */
    public List<String> debugLines() {
        List<String> out = new ArrayList<>();
        Snapshot s = snap;
        out.add("voxymap 0.1.0  cache v" + CACHE_VERSION);
        out.add("storage: " + (lastStorage == null ? "-" : lastStorage));
        out.add("map: " + (s == null ? "none" : s.width + "x" + s.height)
                + "  px=" + (s == null ? "-" : String.valueOf(s.pixelBlocks))
                + "  origin=" + (s == null ? "-" : s.minWX + "," + s.minWZ));
        out.add("state: ready=" + isReady() + " loading=" + loading + " prog=" + progress + " styleChanging=" + styleChanging);
        out.add("style: " + style + "  error: " + (error.isEmpty() ? "-" : error));
        out.add("scale: 1:" + mapScale + "  (" + effectivePixelBlocks() + " blocks/px)");
        out.add("epoch=" + worldEpoch + " initDone=" + initialRefreshDone
                + " pending=" + isInitialRefreshPending() + " newWorld=" + isNewWorldNow());
        long secs = sessionStartMs == 0 ? 0 : (System.currentTimeMillis() - sessionStartMs) / 1000L;
        out.add("session: " + secs + "s  delay=" + VoxyMapConfig.INITIAL_REFRESH_DELAY_MINUTES.get() + "min");
        out.add("tex: map=" + (textureId != null) + " small=" + (textureIdSmall != null) + " biome=" + (biomeTextureId != null));
        out.add("labels: " + (biomeLabelsFlat.length / 3) + "  biomes: " + (s == null ? 0 : s.biomeNames.size()));
        Runtime rt = Runtime.getRuntime();
        out.add("mem: " + (rt.totalMemory() - rt.freeMemory()) / 1048576L + " / " + rt.maxMemory() / 1048576L + " MB");
        return out;
    }

    public boolean getView3d() {
        return view3d;
    }

    public float getView3dYaw() {
        return view3dYaw;
    }

    public float getView3dPitch() {
        return view3dPitch;
    }

    public float getView3dDistance() {
        return view3dDistance;
    }

    public boolean hasView3dCamera() {
        return view3dCameraSaved;
    }

    public void setView3dCamera(float yaw, float pitch, float distance) {
        view3dYaw = yaw;
        view3dPitch = pitch;
        view3dDistance = Math.max(1f, distance);
        view3dCameraSaved = true;
        saveUiState();
    }

    public void setView3d(boolean value) {
        if (view3d == value) return;
        view3d = value;
        saveUiState();
    }

    public void setStyle(String s) {
        if (!isReady() || loading || styleChanging) return;
        style = s;
        saveUiState();
        Snapshot target = snap;
        int sea = seaLevel();
        styleChanging = true;
        loader.submit(() -> {
            try {
                int[] rgb = renderColor(target, s, sea, false);
                Minecraft.getInstance().execute(() -> {
                    uploadTexture(target, rgb);
                    lastRenderedStyle = s;
                    styleChanging = false;
                });
            } catch (Throwable t) {
                t.printStackTrace();
                styleChanging = false;
            }
        });
    }

    /**
     * Re-renders the colour buffer only (no data reload). Used when a display option such as the
     * contour overlay changes; much cheaper than a full refresh.
     */
    public void requestColorRerender() {
        if (!isReady() || loading || styleChanging) return;
        Snapshot target = snap;
        if (target == null) return;
        int sea = seaLevel();
        String current = style;
        styleChanging = true;
        loader.submit(() -> {
            try {
                int[] rgb = renderColor(target, current, sea, false);
                Minecraft.getInstance().execute(() -> {
                    uploadTexture(target, rgb);
                    lastRenderedStyle = current;
                    styleChanging = false;
                });
            } catch (Throwable t) {
                t.printStackTrace();
                styleChanging = false;
            }
        });
    }

    private void ensureDefaultStyle() {
        if (styleInitialized) return;
        styleInitialized = true;
        String s = VoxyMapConfig.DEFAULT_STYLE.get();
        for (String n : MapStyles.NAMES) {
            if (n.equals(s)) {
                style = n;
                return;
            }
        }
    }

    /** Called when the client leaves a world or joins another one. */
    public void resetForNewWorld() {
        sessionStartMs = 0;
        lastAutoRefreshMs = 0;
        lastUpdateX = Double.NaN;
        lastUpdateZ = Double.NaN;
        initialRefreshDone = false;
        worldEpoch++;
        // Keep the built grid and the rendered colours: rejoining the same world can re-upload the
        // cached texture instead of rebuilding and re-rendering (see updateAsync). A different world
        // is detected by comparing the storage path (needsWorldReload) and drops the old grid first.
        releaseTextures();
        dropAnalysisCaches();
        // 3D and forced-grid state is per-world too: without this a new world could inherit the
        // previous world's 3D mode/camera or its safety-grid choice, and the first saveUiState()
        // would write the current values into the previous world's state file
        view3d = false;
        view3dCameraSaved = false;
        view3dYaw = (float) Math.toRadians(45);
        view3dPitch = (float) Math.toRadians(-50);
        view3dDistance = 1500f;
        skipBiomeLabels = false;
        contourOverride = null;
        pixelBlocksOverride = 0;
        allowOversize = false;
        // do not persist per-world UI state until the new world's storage is known
        lastStorage = null;
        error = "";
        progress = -1;
        biomeKeyCache.clear();
        applyConfiguredDefaults();
    }

    /**
     * Drops the caches that pin the previous world's snapshot and its per-pixel arrays. The kept
     * snapshot/colour frame are deliberately retained so rejoining the same world can re-upload the
     * texture, but the terrain analysis (~30 B/px) has no value once the world is gone and would
     * otherwise keep the old world in memory (doubling the peak when another world is built).
     */
    private void dropAnalysisCaches() {
        terrainCache = null;
        biomeEdgePx = null;
        biomeEdgeVersion = 0;
        biomeLabelsSnapshot = null;
    }

    /** True when the kept snapshot does not belong to the world that is loaded now. */
    public boolean needsWorldReload() {
        if (snap == null) return false;
        Path storage = findStorage();
        return storage == null || !storage.equals(snapStorage);
    }

    /** Drops the kept snapshot and its textures before a different world is built. */
    public void dropSnapshotForWorldChange() {
        if (snap == null && colorFrame == null) return;
        snap = null;
        snapStorage = null;
        colorFrame = null;
        lastRenderedStyle = null;
        biomeLabelsFlat = new int[0];
        dropAnalysisCaches();
        releaseAllTextures();
    }

    // ------------------------------------------------------------- update

    public void updateAsync() {
        updateAsync(false);
    }

    /**
     * @param forceReload when true the disk cache is ignored and the Voxy storage is re-read
     */
    public void updateAsync(boolean forceReload) {
        ensureDefaultStyle();
        if (!isOverworld()) {
            // only the overworld LOD storage is supported; do not build anything elsewhere
            error = "";
            progress = -1;
            loading = false;
            requested.set(false);
            return;
        }
        if (requested.getAndSet(true)) return;
        loading = true;
        progress = 0;
        error = "";
        outOfMemory = false;
        oversizePixels = 0;
        oversizeLimit = 0;
        oversizeSafePixelBlocks = 0;
        Minecraft mc = Minecraft.getInstance();
        final int sea = seaLevel();
        final int epoch = worldEpoch;
        loader.submit(() -> {
            boolean handedToMainThread = false;
            Snapshot prevSnap = null;
            Path prevSnapStorage = null;
            Snapshot built = null;
            try {
                prevSnap = snap;
                prevSnapStorage = snapStorage;
                // the world changed after this request was queued: drop it instead of uploading the
                // previous world's map into the new one
                if (epoch != worldEpoch) return;
                Path storage = findStorage();
                if (storage != null) {
                    lastStorage = storage;
                    loadUiState();
                }
                if (storage == null) {
                    error = "no Voxy storage found for this world";
                    return;
                }
                // rejoining the world whose grid is still in memory: re-upload the rendered texture
                // when the palette has not changed, otherwise only re-render, never rebuild
                boolean sameWorld = snap != null && storage.equals(snapStorage);
                if (sameWorld && !forceReload) {
                    final Snapshot kept = snap;
                    ColorFrame keptFrame = colorFrame;
                    final int[] keptRgb = keptFrame == null ? null : keptFrame.rgb;
                    if (keptRgb != null && style.equals(lastRenderedStyle)) {
                        handedToMainThread = true;
                        mc.execute(() -> {
                            try {
                                if (textureId == null) uploadTexture(kept, keptRgb);
                                progress = 100;
                                Minecraft m = Minecraft.getInstance();
                                if (m.player != null) {
                                    lastUpdateX = m.player.getX();
                                    lastUpdateZ = m.player.getZ();
                                }
                            } finally {
                                loading = false;
                                requested.set(false);
                            }
                        });
                        return;
                    }
                } else if (!sameWorld) {
                    dropSnapshotForWorldChange();
                }
                built = forceReload ? null : (sameWorld ? snap : loadCache(storage));
                // a cache from a different map scale cannot be reused: rebuild at the new scale
                if (built != null && built.pixelBlocks != effectivePixelBlocks()) built = null;
                if (built == null) {
                    built = buildPixels(storage);
                    if (built == null) {
                        // oversize warning (dialog) or a genuinely empty storage
                        if (oversizePixels > 0) return;
                        error = "no sections found in Voxy storage";
                        return;
                    }
                    if (epoch != worldEpoch) return;
                    saveCache(storage, built);
                }
                if (epoch != worldEpoch) return;
                snap = built;
                snapStorage = storage;
                final Snapshot target = built;
                final String renderStyle = style;
                progress = 82;
                // the rendered colour cache turns a ~5 s re-render after a restart into a file read
                int[] cachedRgb = forceReload ? null : loadColorCache(storage, target, renderStyle, sea);
                if (cachedRgb == null) {
                    cachedRgb = renderColor(target, renderStyle, sea);
                    saveColorCache(storage, target, renderStyle, sea, cachedRgb);
                }
                final int[] rgb = cachedRgb;
                progress = 95;
                handedToMainThread = true;
                mc.execute(() -> {
                    try {
                        progress = 95;
                        uploadTexture(target, rgb);
                        lastRenderedStyle = renderStyle;
                        outOfMemory = false;
                        progress = 100;
                    } finally {
                        loading = false;
                        requested.set(false);
                    }
                });
                if (mc.player != null) {
                    lastUpdateX = mc.player.getX();
                    lastUpdateZ = mc.player.getZ();
                }
            } catch (OutOfMemoryError oom) {
                oom.printStackTrace();
                // The colour pass died: put the previous snapshot back so the map/3D view never
                // shows new terrain with the old colour buffer (that is what produced the black
                // patches), then offer the safe scaling like any other too-large map.
                restorePreviousSnapshot(prevSnap, prevSnapStorage);
                outOfMemory = true;
                error = "OutOfMemoryError";
                if (built != null && !allowOversize) {
                    long limit = mapPixelLimit();
                    long px = (long) built.width * built.height;
                    long blockW = (long) built.width * built.pixelBlocks;
                    long blockH = (long) built.height * built.pixelBlocks;
                    int safe = Math.max(1, built.pixelBlocks);
                    long safePx = px;
                    while (safe < 8) {
                        safePx = (blockW / safe) * (blockH / safe);
                        if (safePx <= limit) break;
                        safe++;
                    }
                    oversizePixels = px;
                    oversizeLimit = limit;
                    oversizeSafePixelBlocks = safePx <= limit ? safe : 0;
                }
            } catch (Throwable t) {
                t.printStackTrace();
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                // a failed build/render must not leave a half-updated snapshot behind either
                restorePreviousSnapshot(prevSnap, prevSnapStorage);
            } finally {
                if (!handedToMainThread) {
                    loading = false;
                    progress = -1;
                    requested.set(false);
                }
            }
        });
    }

    /** True while the initial refresh is still waiting for its delay. */
    /** Puts the snapshot that was current before a failed update back, so data and colours stay paired. */
    private void restorePreviousSnapshot(Snapshot previous, Path previousStorage) {
        snap = previous;
        snapStorage = previousStorage;
        // the partially built analysis cache must not stay paired with a different snapshot
        terrainCache = null;
    }

    /**
     * Pixel budget for the terrain analysis. The arrays peak at roughly 60 bytes per pixel on top of
     * the snapshot and the colour buffer, so even a large heap can only take a fraction of the
     * configured limit; when the estimate exceeds this the oversize dialog suggests a coarser scale.
     */
    private static long mapPixelLimit() {
        long configured = Math.max(10_000_000L, VoxyMapConfig.MAX_MAP_PIXELS.get());
        long heapCap = Math.max(10_000_000L, (long) (Runtime.getRuntime().maxMemory() * 0.6) / 64L);
        return Math.min(configured, heapCap);
    }

    /** Reload with the suggested safe grid (coarser blocks per pixel). */
    public void retryWithSafeGrid() {
        if (oversizeSafePixelBlocks <= 0) return;
        pixelBlocksOverride = Math.max(1, oversizeSafePixelBlocks);
        allowOversize = false;
        oversizePixels = 0;
        // drop the previous (oversized) snapshot first: keeping it alive while the coarser grid is
        // being built doubles the peak, which is exactly what the safe mode is trying to avoid
        dropSnapshotForWorldChange();
        updateAsync(true);
    }

    /** Reload anyway, accepting the risk of running out of memory. */
    public void retryOversized() {
        pixelBlocksOverride = 0;
        allowOversize = true;
        oversizePixels = 0;
        dropSnapshotForWorldChange();
        updateAsync(true);
    }

    public boolean isInitialRefreshPending() {
        return !initialRefreshDone && VoxyMapConfig.INITIAL_REFRESH_DELAY_MINUTES.get() > 0;
    }

    /** True when this is a freshly created single-player world that is still inside the delay. */
    public boolean isNewWorldNow() {
        if (Minecraft.getInstance().getSingleplayerServer() == null) return false;
        int delayMin = VoxyMapConfig.INITIAL_REFRESH_DELAY_MINUTES.get();
        return delayMin > 0 && !isOldWorld(delayMin);
    }

    /** Skip the remaining initial delay (the player chose to refresh now). */
    public void skipInitialDelay() {
        initialRefreshDone = true;
    }

    /** True when the save already existed before this session (so SeedGen had time already). */
    private static long lastOldWorldCheck;
    private static boolean lastOldWorldResult;

    private static boolean isOldWorld(int delayMin) {
        long now = System.currentTimeMillis();
        if (now - lastOldWorldCheck < 5000) return lastOldWorldResult;
        boolean result = computeOldWorld(delayMin);
        lastOldWorldCheck = now;
        lastOldWorldResult = result;
        return result;
    }

    private static boolean computeOldWorld(int delayMin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) return false;
        try {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            // an existing map cache means voxymap already rendered this world before
            Path voxy = root.resolve("voxy");
            if (Files.isDirectory(voxy)) {
                try (var stream = Files.list(voxy)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        Path storage = p.resolve("storage");
                        if (Files.isDirectory(storage) && Files.isRegularFile(cacheFile(storage))) return true;
                    }
                }
            }
            // fall back to the save directory creation time when it is valid
            long created = 0;
            try {
                created = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class)
                        .creationTime().toMillis();
            } catch (Exception ignored) {
            }
            long now = System.currentTimeMillis();
            if (created > 0 && created <= now) {
                return now - created > delayMin * 60_000L;
            }
        } catch (Exception ignored) {
        }
        // unknown world age: treat it as new so the dialog is shown instead of refreshing immediately
        return false;
    }

    /** True while the player is in the overworld (the only dimension with a supported storage). */
    private boolean overworldActive = true;

    /** Called once per client tick. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean active = mc.player != null && isOverworld();
        if (!active) {
            // nether / end / menus: reset once on entry, then stay idle
            if (overworldActive) {
                resetForNewWorld();
                overworldActive = false;
            }
            return;
        }
        overworldActive = true;
        if (!VoxyMapConfig.AUTO_REFRESH.get()) return;
        long now = System.currentTimeMillis();
        if (sessionStartMs == 0) sessionStartMs = now;
        double refreshDist = VoxyMapConfig.REFRESH_DISTANCE_CHUNKS.get() * 16.0;
        boolean moved = !Double.isNaN(lastUpdateX)
                && Math.hypot(mc.player.getX() - lastUpdateX, mc.player.getZ() - lastUpdateZ) > refreshDist;
        // Initial refresh runs once per world, N minutes after joining, so SeedGen has time to fill more data.
        int delayMin = VoxyMapConfig.INITIAL_REFRESH_DELAY_MINUTES.get();
        boolean initialPending = !initialRefreshDone && delayMin > 0;
        boolean timeUp = initialPending && now - sessionStartMs > delayMin * 60_000L;
        boolean oldWorld = initialPending && isOldWorld(delayMin);
        // While a fresh world is still inside its initial delay, walking around must not start a
        // build either: only the delay expiring (or the dialog's "refresh now" button) may. This
        // keeps the "wait a few minutes so SeedGen can fill the storage" promise, and stops the
        // map from being built and cached from a nearly empty storage.
        boolean movedAllowed = moved && !initialPending;
        boolean initialTrigger = timeUp || oldWorld;
        boolean mapOpen = Minecraft.getInstance().gui.screen() instanceof MapScreen;
        // Auto refresh only runs while the map is visible: a full rebuild is expensive and the old
        // code triggered it on every tick when the player travelled far (the "正在刷新 0%" flicker).
        // Opening the map after travelling calls refreshIfMoved() instead.
        boolean movementRefresh = movedAllowed && mapOpen;
        if ((movementRefresh || initialTrigger) && !loading
                && oversizePixels == 0 && !outOfMemory
                && now - lastAutoRefreshMs > 15_000L) {
            if (initialTrigger) initialRefreshDone = true;
            lastAutoRefreshMs = now;
            if (movementRefresh) {
                // remember the position the async build was started for: a failed refresh must not
                // restart on every tick
                lastUpdateX = mc.player.getX();
                lastUpdateZ = mc.player.getZ();
            }
            // movement means new Voxy data exists, so the snapshot must be rebuilt; the initial
            // refresh only reuses the disk cache so joining a world does not re-read everything
            updateAsync(!movementRefresh);
        }
    }

    /**
     * Called when the map screen opens. With auto-refresh enabled the snapshot is rebuilt when the
     * player travelled farther than {@code refreshDistanceChunks} while the map was closed (auto
     * refresh itself only runs while the map is visible, to avoid rebuilding huge maps in the
     * background and to stop the old per-tick retry loop).
     */
    public void refreshIfMoved() {
        if (!VoxyMapConfig.AUTO_REFRESH.get() || loading || outOfMemory || oversizePixels > 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Double.isNaN(lastUpdateX)) return;
        double dist = VoxyMapConfig.REFRESH_DISTANCE_CHUNKS.get() * 16.0;
        if (Math.hypot(mc.player.getX() - lastUpdateX, mc.player.getZ() - lastUpdateZ) <= dist) return;
        if (isInitialRefreshPending()) return;
        lastUpdateX = mc.player.getX();
        lastUpdateZ = mc.player.getZ();
        updateAsync(true);
    }

    // ------------------------------------------------------------ disk cache
    private static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("voxymap-cache");
    }

    /** One directory per world storage: map.vxm + color.vxc + state.properties. */
    private static Path worldCacheDir(Path storage) {
        return cacheDir().resolve(storage.getParent().getFileName().toString());
    }

    private static Path cacheFile(Path storage) {
        return worldCacheDir(storage).resolve("map.vxm");
    }

    /** Rendered colour cache: skips renderColor on the next launch. */
    private static Path colorCacheFile(Path storage) {
        return worldCacheDir(storage).resolve("color.vxc");
    }

    private static final int COLOR_CACHE_MAGIC = 0x56584331;
    /**
     * 3: the colour cache now records the snapshot stamp, so a cached colour buffer can never be
     * paired with a rebuilt snapshot again (that mismatch is what left black blocks on the map when
     * a refresh ran out of memory before it could re-render the colours).
     */
    private static final int COLOR_CACHE_VERSION = 3;

    /** everything that changes the produced pixels; changing any of these invalidates the .vxc */
    private static String colorCacheKey(String style, int sea) {
        return style + "|" + sea + "|" + VoxyMapConfig.MAP_SCALE.get() + "|"
                + (contourOverride != null ? contourOverride : VoxyMapConfig.DRAW_CONTOURS.get()) + "|"
                + VoxyMapConfig.MIN_CONTOUR_AREA.get() + "|"
                + VoxyMapConfig.CONTOUR_DENSITY.get() + "|" + VoxyMapConfig.DRAW_HILLSHADE.get() + "|"
                + VoxyMapConfig.DRAW_WATER_DEPTH.get() + "|" + VoxyMapConfig.ADAPTIVE_COLOR_RAMP.get() + "|"
                + VoxyMapConfig.MIN_MAX_RAMP.get() + "|" + VoxyMapConfig.WATER_BLUR.get() + "|"
                + VoxyMapConfig.WATER_BLUR_RADIUS.get() + "|" + VoxyMapConfig.WATER_BLUR_PASSES.get() + "|"
                + VoxyMapConfig.DENOISE.get() + "|" + VoxyMapConfig.FILL_HOLES.get();
    }

    private static int[] loadColorCache(Path storage, Snapshot s, String style, int sea) {
        try {
            Path file = colorCacheFile(storage);
            if (!Files.isRegularFile(file)) return null;
            byte[] data = Files.readAllBytes(file);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != COLOR_CACHE_MAGIC || in.readInt() != COLOR_CACHE_VERSION) return null;
            int w = in.readInt();
            int h = in.readInt();
            int pb = in.readInt();
            long stamp = in.readLong();
            String key = in.readUTF();
            int clen = in.readInt();
            // the colour cache is only valid for the exact snapshot it was rendered from: a rebuilt
            // snapshot (same dimensions!) whose re-render failed must not reuse the old colours
            if (w != s.width || h != s.height || pb != s.pixelBlocks || stamp != s.stamp
                    || !key.equals(colorCacheKey(style, sea))) return null;
            byte[] comp = new byte[clen];
            in.readFully(comp);
            byte[] raw = Zstd.decompress(comp, w * h * 4);
            if (raw == null || raw.length != w * h * 4) return null;
            int[] rgb = new int[w * h];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(rgb);
            return rgb;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveColorCache(Path storage, Snapshot s, String style, int sea, int[] rgb) {
        try {
            Files.createDirectories(worldCacheDir(storage));
            ByteBuffer bb = ByteBuffer.allocate(rgb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            bb.asIntBuffer().put(rgb);
            byte[] comp = Zstd.compress(bb.array(), 3);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(COLOR_CACHE_MAGIC);
            out.writeInt(COLOR_CACHE_VERSION);
            out.writeInt(s.width);
            out.writeInt(s.height);
            out.writeInt(s.pixelBlocks);
            out.writeLong(s.stamp);
            out.writeUTF(colorCacheKey(style, sea));
            out.writeInt(comp.length);
            out.write(comp);
            out.flush();
            Files.write(colorCacheFile(storage), bos.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    private static void saveCache(Path storage, Snapshot s) {
        try {
            Files.createDirectories(worldCacheDir(storage));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(0x56584D31);
            dos.writeInt(CACHE_VERSION);
            dos.writeInt(s.width);
            dos.writeInt(s.height);
            dos.writeLong(s.minWX);
            dos.writeLong(s.minWZ);
            dos.writeInt(s.pixelBlocks);
            dos.writeLong(s.stamp);
            dos.writeInt(s.biomeNames.size());
            for (Map.Entry<Integer, String> e : s.biomeNames.entrySet()) {
                dos.writeInt(e.getKey());
                dos.writeUTF(e.getValue());
            }
            ByteBuffer raw = ByteBuffer.allocate(s.pixels.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            raw.asLongBuffer().put(s.pixels);
            byte[] compressed = Zstd.compress(raw.array(), 3);
            dos.writeInt(compressed.length);
            dos.write(compressed);
            Path out = cacheFile(storage);
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.write(tmp, bos.toByteArray());
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            pruneCache();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Keeps only the newest {@code cacheKeepCount} world caches and drops leftover {@code .tmp}
     * files. A cache is never invalidated automatically (Voxy rewrites its storage constantly), so
     * without this the directory would grow with every world that was ever opened, including
     * worlds that have long been deleted.
     */
    private static void pruneCache() {
        int keep = Math.max(1, VoxyMapConfig.CACHE_KEEP_COUNT.get());
        try {
            Path dir = cacheDir();
            if (!Files.isDirectory(dir)) return;
            // the old flat layout kept map.vxm/.vxc/.state in the cache root: drop those strays
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString();
                    if (name.endsWith(".vxm") || name.endsWith(".vxc") || name.endsWith(".state")
                            || name.endsWith(".tmp")) {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            List<Path> worlds = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(p)) worlds.add(p);
                }
            }
            if (worlds.size() <= keep) return;
            worlds.sort((a, b) -> Long.compare(lastCacheWrite(b), lastCacheWrite(a)));
            for (int i = keep; i < worlds.size(); i++) {
                deleteTree(worlds.get(i));
            }
        } catch (Throwable ignored) {
        }
    }

    private static long lastCacheWrite(Path worldDir) {
        try {
            Path f = worldDir.resolve("map.vxm");
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void deleteTree(Path dir) {
        try (var stream = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            for (Path p : (Iterable<Path>) stream::iterator) all.add(p);
            for (int i = all.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(all.get(i));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Snapshot loadCache(Path storage) {
        try {
            Path f = cacheFile(storage);
            if (!Files.isRegularFile(f)) return null;
            byte[] data = Files.readAllBytes(f);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            if (dis.readInt() != 0x56584D31 || dis.readInt() != CACHE_VERSION) return null;
            int w = dis.readInt();
            int h = dis.readInt();
            long mwx = dis.readLong();
            long mwz = dis.readLong();
            int pb = dis.readInt();
            long stamp = dis.readLong();
            // Voxy rewrites its RocksDB constantly (mtime always newer after a restart),
            // so an existing cache stays valid; the manual refresh forces a rebuild.
            int bioN = dis.readInt();
            Map<Integer, String> bio = new HashMap<>();
            for (int i = 0; i < bioN; i++) {
                int id = dis.readInt();
                bio.put(id, dis.readUTF());
            }
            int clen = dis.readInt();
            if (clen <= 0 || clen > data.length) return null;
            byte[] comp = new byte[clen];
            dis.readFully(comp);
            byte[] raw = Zstd.decompress(comp, w * h * 8);
            if (raw == null || raw.length != w * h * 8) return null;
            long[] px = new long[w * h];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(px);
            return new Snapshot(px, w, h, mwx, mwz, pb, bio, stamp);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- storage IO

    private static Path findStorage() {
        Minecraft mc = Minecraft.getInstance();
        Path base = null;
        if (mc.getSingleplayerServer() != null) {
            base = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else if (mc.getConnection() != null && mc.getConnection().getServerData() != null) {
            var info = mc.getConnection().getServerData();
            Path saves = mc.gameDirectory.toPath().resolve(".voxy").resolve("saves");
            base = saves.resolve(info.isRealm() ? "realms" : info.ip.replace(":", "_"));
        }
        if (base == null || !Files.isDirectory(base)) return null;
        try (var stream = Files.list(base)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path storage = p.resolve("storage");
                if (Files.isDirectory(storage)) return storage;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Snapshot buildPixels(Path storage) throws Exception {
        int pixelBlocks = effectivePixelBlocks();
        int maxLodLevel = Math.max(0, Math.min(4, VoxyMapConfig.MAX_LOD_LEVEL.get()));
        if (!RocksBridge.available()) {
            throw new IllegalStateException("rocksdb unavailable: " + RocksBridge.error());
        }
        List<Object> handles = new ArrayList<>();
        Object db = RocksBridge.openReadOnly(storage.toString(), handles);
        try {
            Object sections = handles.get(1);
            Object mappings = handles.get(2);
            Map<Integer, String> biomeNames = new HashMap<>();
            Set<Long> waterIds = loadWaterIds(db, mappings, biomeNames);

            int totalSections = 0;
            long minWXv = Long.MAX_VALUE, maxWXv = Long.MIN_VALUE;
            long minWZv = Long.MAX_VALUE, maxWZv = Long.MIN_VALUE;
            Object it = RocksBridge.newIterator(db, sections);
            try {
                RocksBridge.seekToFirst(it);
                while (RocksBridge.isValid(it)) {
                    byte[] kb = RocksBridge.key(it);
                    if (kb != null && kb.length == 8) {
                        long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                        int lvl = (int) ((key >>> 60) & 0xF);
                        if (lvl <= maxLodLevel) {
                            totalSections++;
                            int scale = 1 << lvl;
                            int sx = (int) ((key << 36) >> 40);
                            int sz = (int) ((key << 12) >> 40);
                            long x0 = (long) sx * 32 * scale;
                            long z0 = (long) sz * 32 * scale;
                            minWXv = Math.min(minWXv, x0);
                            maxWXv = Math.max(maxWXv, x0 + 32L * scale);
                            minWZv = Math.min(minWZv, z0);
                            maxWZv = Math.max(maxWZv, z0 + 32L * scale);
                        }
                    }
                    RocksBridge.next(it);
                }
            } finally {
                RocksBridge.close(it);
            }
            if (maxWXv == Long.MIN_VALUE) return null;
            // Guard against OutOfMemory: the analysis arrays peak at roughly 60 bytes per pixel on top
            // of the snapshot and colour buffers. Cap the accepted size by a fraction of the heap even
            // when the configured limit is larger (a forced load can still bypass this), then let the
            // player decide between the safe grid and forcing the load.
            final long limit = mapPixelLimit();
            int pb = pixelBlocks;
            int gw = (int) ((maxWXv - minWXv + pb - 1) / pb);
            int gh = (int) ((maxWZv - minWZv + pb - 1) / pb);
            long pixelsEstimate = (long) gw * gh;
            if (pixelsEstimate > limit && !allowOversize) {
                int safe = Math.max(1, pb);
                while (safe < 8
                        && (long) ((maxWXv - minWXv + safe - 1) / safe) * ((maxWZv - minWZv + safe - 1) / safe) > limit) {
                    safe++;
                }
                long safePixels = (long) ((maxWXv - minWXv + safe - 1) / safe) * ((maxWZv - minWZv + safe - 1) / safe);
                oversizePixels = pixelsEstimate;
                oversizeLimit = limit;
                // 0 = even the coarsest grid does not fit: the dialog must not offer "load safely"
                oversizeSafePixelBlocks = safePixels <= limit ? safe : 0;
                return null;
            }
            long[] pix = new long[gw * gh];
            Arrays.fill(pix, LONG_EMPTY);

            int processedSections = 0;
            List<Future<Decoded>> futures = new ArrayList<>(BATCH);
            Object it2 = RocksBridge.newIterator(db, sections);
            try {
                RocksBridge.seekToFirst(it2);
                while (RocksBridge.isValid(it2)) {
                    byte[] kb = RocksBridge.key(it2);
                    if (kb != null && kb.length == 8) {
                        long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                        int lvl = (int) ((key >>> 60) & 0xF);
                        if (lvl <= maxLodLevel) {
                            processedSections++;
                            byte[] val = RocksBridge.value(it2);
                            if (val != null && val.length > 16) {
                                final long fkey = key;
                                final byte[] fval = val;
                                futures.add(pool.submit(() -> decodeSection(fkey, fval, waterIds)));
                                if (futures.size() >= BATCH) {
                                    flush(futures, pix, gw, gh, minWXv, minWZv, pixelBlocks);
                                    futures.clear();
                                    progress = Math.min(80, (int) ((long) processedSections * 80 / Math.max(1, totalSections)));
                                }
                            }
                        }
                    }
                    RocksBridge.next(it2);
                }
            } finally {
                RocksBridge.close(it2);
            }
            flush(futures, pix, gw, gh, minWXv, minWZv, pixelBlocks);
            return new Snapshot(pix, gw, gh, minWXv, minWZv, pixelBlocks, biomeNames, System.nanoTime());
        } finally {
            for (Object h : handles) RocksBridge.close(h);
            RocksBridge.close(db);
        }
    }

    private record Decoded(long key, long[] cols) {
    }

    private void flush(List<Future<Decoded>> futures, long[] pixels, int w, int h, long minWXv, long minWZv, int pixelBlocks) throws Exception {
        for (Future<Decoded> f : futures) {
            Decoded d = f.get();
            if (d != null) flushSection(d.key(), d.cols(), pixels, w, h, minWXv, minWZv, pixelBlocks);
        }
    }

    private void flushSection(long key, long[] cols, long[] pixels, int w, int h, long minWXv, long minWZv, int pixelBlocks) {
        int lvl = (int) ((key >>> 60) & 0xF);
        int sx = (int) ((key << 36) >> 40);
        int sz = (int) ((key << 12) >> 40);
        int scale = 1 << lvl;
        for (int c = 0; c < 1024; c++) {
            long packed = cols[c];
            if (packed == LONG_EMPTY) continue;
            int top = (int) ((packed >>> 24) & 0xFFFF) - 32768;
            int floor = (int) (packed & 0xFFFF) - 32768;
            boolean topFluid = (packed & (1L << 52)) != 0;
            int biomeHere = (int) ((packed >>> 53) & 0x1FF);
            long wx0 = (long) (sx * 32 + (c & 31)) * scale;
            long wz0 = (long) (sz * 32 + (c >> 5)) * scale;
            int px0 = (int) ((wx0 - minWXv) / pixelBlocks);
            int px1 = (int) ((wx0 + scale - 1 - minWXv) / pixelBlocks);
            int pz0 = (int) ((wz0 - minWZv) / pixelBlocks);
            int pz1 = (int) ((wz0 + scale - 1 - minWZv) / pixelBlocks);
            for (int pz = pz0; pz <= pz1; pz++) {
                if (pz < 0 || pz >= h) continue;
                int row = pz * w;
                for (int px = px0; px <= px1; px++) {
                    if (px < 0 || px >= w) continue;
                    int idx = row + px;
                    long prev = pixels[idx];
                    int solidHere = floor != NO_SOLID ? floor : Integer.MIN_VALUE;
                    int curTop;
                    boolean curWater;
                    int curLvl;
                    int curFloor;
                    int curBiome;
                    if (prev == LONG_EMPTY) {
                        curTop = top;
                        curWater = topFluid;
                        curLvl = lvl;
                        curFloor = NO_SOLID;
                        curBiome = biomeHere;
                    } else {
                        curTop = (int) ((prev >>> 24) & 0xFFFF) - 32768;
                        curWater = (prev & (1L << 52)) != 0;
                        curLvl = (int) ((prev >>> 48) & 0xF);
                        curFloor = (int) (prev & 0xFFFF) - 32768;
                        curBiome = (int) ((prev >>> 53) & 0x1FF);
                        if (top != NO_TOP) {
                            boolean accept = curTop == NO_TOP
                                    || lvl < curLvl
                                    || (lvl == curLvl && top > curTop)
                                    // a much higher coarse-level top means the fine level only has
                                    // buried slices here: take the coarse surface instead
                                    || (lvl > curLvl && curTop < 0 && top > curTop + 32);
                            if (accept) {
                                curTop = top;
                                curWater = topFluid;
                                curLvl = lvl;
                                curBiome = biomeHere;
                            }
                        }
                    }
                    // same-level highest solid above the current top: the top is a cave/underground
                    // ceiling, so the real surface is the top of that solid
                    if (curTop != NO_TOP && solidHere != Integer.MIN_VALUE && solidHere > curTop && lvl == curLvl) {
                        curTop = solidHere;
                        curWater = false;
                        curLvl = lvl;
                        curBiome = biomeHere;
                    }
                    // floor: highest solid anywhere in the column (sea floor for water)
                    if (curTop != NO_TOP && solidHere != Integer.MIN_VALUE && solidHere < curTop) {
                        int prevF = curFloor == NO_SOLID ? Integer.MIN_VALUE : curFloor;
                        if (prevF == Integer.MIN_VALUE || solidHere > prevF) curFloor = solidHere;
                    }
                    if (curTop != NO_TOP && !curWater) curFloor = curTop;
                    if (curFloor == Integer.MIN_VALUE) curFloor = NO_SOLID;
                    long out = ((long) (curTop + 32768)) << 24 | ((curFloor + 32768) & 0xFFFF);
                    if (curWater) out |= (1L << 52);
                    out |= ((long) curBiome) << 53;
                    pixels[idx] = out;
                }
            }
        }
    }

    private static Decoded decodeSection(long key, byte[] val, Set<Long> waterIds) {
        byte[] d = decompress(val);
        if (d == null || d.length < 16 + VOL * 2) return null;
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        bb.getLong();
        long meta = bb.getLong();
        int lutSize = (int) (meta & 0xFFFF);
        int base = 16;
        int lutBase = base + VOL * 2;
        if (lutSize <= 0 || lutSize > (1 << 16) || lutBase + lutSize * 8L > d.length) return null;
        short[] idx = new short[VOL];
        for (int i = 0; i < VOL; i++) idx[i] = bb.getShort(base + i * 2);
        long[] lut = new long[lutSize];
        for (int i = 0; i < lutSize; i++) lut[i] = bb.getLong(lutBase + i * 8);

        int sy = (int) ((key << 4) >> 56);
        int scale = 1 << (int) ((key >>> 60) & 0xF);
        long airMask = ((1L << 20) - 1) << 27;
        long[] cols = new long[1024];
        Arrays.fill(cols, LONG_EMPTY);
        for (int c = 0; c < 1024; c++) {
            int x = c & 31;
            int z = c >> 5;
            int topY = -1;
            boolean topFluid = false;
            int solidY = -1;
            long topVox = 0;
            for (int y = 31; y >= 0; y--) {
                int i = (y << 10) | (z << 5) | x;
                int li = Short.toUnsignedInt(idx[i]);
                if (li >= lutSize) continue;
                long voxel = lut[li];
                if ((voxel & airMask) == 0) continue;
                boolean fluid = isWater(voxel, waterIds);
                if (topY < 0) {
                    topY = y;
                    topFluid = fluid;
                    topVox = voxel;
                }
                if (!fluid && y > solidY) solidY = y;
            }
            if (topY < 0 && solidY < 0) continue; // empty column
            int topWorld = topY >= 0 ? (sy * 32 + topY) * scale + scale - 1 : NO_TOP;
            // a solid column reaching the section ceiling is a buried slice, not terrain
            if (topY >= 0 && topY == 31 && !topFluid) topWorld = NO_TOP;
            int solidWorld = solidY >= 0 ? (sy * 32 + solidY) * scale + scale - 1 : NO_SOLID;
            long out = (((long) (topWorld + 32768)) << 24) | ((solidWorld + 32768) & 0xFFFF);
            if (topFluid) out |= (1L << 52);
            out |= ((long) (((topVox >>> 47) & 0x1FF))) << 53;
            cols[c] = out;
        }
        return new Decoded(key, cols);
    }

    private static byte[] decompress(byte[] in) {
        try {
            long sz = Zstd.getFrameContentSize(in);
            if (sz > 0 && sz < 50_000_000) return Zstd.decompress(in, (int) sz);
            try (com.github.luben.zstd.ZstdInputStream z = new com.github.luben.zstd.ZstdInputStream(new ByteArrayInputStream(in))) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(700_000);
                z.transferTo(bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<Long> loadWaterIds(Object db, Object mappings, Map<Integer, String> biomeNames) {
        Set<Long> out = new HashSet<>();
        try {
            Object it = RocksBridge.newIterator(db, mappings);
            try {
                RocksBridge.seekToFirst(it);
                while (RocksBridge.isValid(it)) {
                    byte[] k = RocksBridge.key(it);
                    byte[] v = RocksBridge.value(it);
                    if (k != null && k.length == 4 && v != null) {
                        long be = ((long) (k[0] & 0xFF) << 24) | ((long) (k[1] & 0xFF) << 16)
                                | ((long) (k[2] & 0xFF) << 8) | (k[3] & 0xFF);
                        String text = unzipText(v);
                        if (text != null && ((be >>> 24) == 0x80)) {
                            int hi = text.indexOf("minecraft:");
                            if (hi < 0) hi = text.indexOf(':');
                            if (hi >= 0) {
                                int end = hi;
                                while (end < text.length()) {
                                    char ch = text.charAt(end);
                                    boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                                            || ch == '_' || ch == ':' || ch == '.' || ch == '-';
                                    if (!ok) break;
                                    end++;
                                }
                                String nm = text.substring(hi, end);
                                while (nm.endsWith(".")) nm = nm.substring(0, nm.length() - 1);
                                if (!nm.isEmpty()) {
                                    biomeNames.put((int) (be & ((1L << 30) - 1)), nm);
                                }
                            }
                        }
                        if (text != null && text.contains("minecraft:water") && !text.contains("cauldron")) {
                            out.add(be & ((1L << 30) - 1));
                        }
                    }
                    RocksBridge.next(it);
                }
            } finally {
                RocksBridge.close(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String unzipText(byte[] v) {
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(v))) {
            byte[] out = g.readAllBytes();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                char c = (char) (b & 0xFF);
                sb.append(c >= 32 && c < 127 ? c : (c == 0 ? ' ' : '.'));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWater(long voxel, Set<Long> waterIds) {
        return waterIds.contains((voxel >>> 27) & ((1L << 20) - 1));
    }

    // ------------------------------------------------------------- render

    /** Render the colour grid off the render thread, then upload it on the client thread. */
    /** Writes the most recently rendered map as a PNG. */
    public boolean exportPng(java.nio.file.Path out) {
        ColorFrame f = colorFrame;
        if (f == null || f.width <= 0 || f.height <= 0) return false;
        int[] rgb = f.rgb;
        int w = f.width, h = f.height;
        try {
            java.nio.file.Files.createDirectories(out.getParent());
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, rgb, 0, w);
            javax.imageio.ImageIO.write(img, "png", out.toFile());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Release all map textures (frees ~560 MB of VRAM when the map is closed). */
    public void releaseTextures() {
        Minecraft mc = Minecraft.getInstance();
        // the 1/4 texture is kept on purpose: ~5 MB of VRAM, and reopening shows it instantly
        releaseOne(mc, textureId);
        textureId = null;
        texW = 0;
        texH = 0;
        releaseOne(mc, biomeTextureId);
        biomeTextureId = null;
        biomeImage = null;
    }

    /** Releases every texture including the small one (used when the world changes). */
    public void releaseAllTextures() {
        releaseTextures();
        releaseOne(Minecraft.getInstance(), textureIdSmall);
        textureIdSmall = null;
    }

    private static void releaseOne(Minecraft mc, Identifier id) {
        if (id == null) return;
        var texture = mc.getTextureManager().getTexture(id);
        if (texture != null) texture.close();
        mc.getTextureManager().release(id);
    }

    /** Re-upload the main textures from the last rendered grid (background, used when reopening). */
    public void ensureMainTextures() {
        if (textureId != null) return;
        ColorFrame f = colorFrame;
        if (snap == null || f == null) return;
        buildTexturesAsync(f.width, f.height, f.rgb);
    }

    /** Build the 1-bit biome boundary mask + labels on the loader thread (overlay only). */
    public void ensureBiomeTexture() {
        // the 3D overlay needs the edge array even when the 2D texture already exists (e.g. the map
        // was opened before this feature was added), so do not early-out on the texture alone
        if (biomeTextureId != null && biomeEdgePx != null && biomeEdgeVersion == BIOME_EDGE_VERSION) return;
        Snapshot s = snap;
        if (s == null) return;
        if (!buildingBiome.compareAndSet(false, true)) return;
        final int biomainEpoch = worldEpoch;
        final boolean needTexture = biomeTextureId == null;
        loader.submit(() -> {
            try {
                NativeImage bimg = needTexture ? buildBiomeImage(s) : null;
                int[] edges = buildBiomeEdges(s);
                int[] labels = (biomeLabelsFlat.length > 0 && biomeLabelsSnapshot == s)
                        ? biomeLabelsFlat : buildBiomeLabels(s);
                Minecraft.getInstance().execute(() -> {
                    try {
                        if (biomainEpoch != worldEpoch) {
                            if (bimg != null) bimg.close();
                            return;
                        }
                        biomeEdgePx = edges;
                        biomeEdgeVersion = BIOME_EDGE_VERSION;
                        biomeLabelsFlat = labels;
                        biomeLabelsSnapshot = s;
                        if (bimg != null) {
                            biomeImage = bimg;
                            DynamicTexture btex = new DynamicTexture(() -> "voxymap_bimg", bimg);
                            Identifier bid = Identifier.fromNamespaceAndPath("voxymap", "map_biomes");
                            Minecraft.getInstance().getTextureManager().register(bid, btex);
                            biomeTextureId = bid;
                        }
                    } finally {
                        buildingBiome.set(false);
                    }
                });
            } catch (Throwable t) {
                buildingBiome.set(false);
                t.printStackTrace();
            }
        });
    }

    private void uploadTexture(Snapshot s, int[] rgb) {
        ColorFrame prev = colorFrame;
        colorFrame = new ColorFrame(rgb, s.width, s.height, prev == null ? 1 : prev.epoch + 1);
        texEpoch++;
        Minecraft mc = Minecraft.getInstance();
        // a rebuilt grid invalidates the biome overlay: drop it so ensureBiomeTexture regenerates it
        releaseOne(mc, biomeTextureId);
        biomeTextureId = null;
        biomeImage = null;
        buildTexturesAsync(s.width, s.height, rgb);
    }

    /** Builds the NativeImages on the loader thread; only the GPU upload runs on the main thread. */
    private void buildTexturesAsync(int w, int h, int[] rgb) {
        if (!buildingTextures.compareAndSet(false, true)) {
            // a build is already running with an older colour buffer: remember to start a fresh one
            // when it finishes instead of silently leaving the map with stale colours
            textureBuildPending = true;
            return;
        }
        final int epoch = texEpoch;
        final int worldEpochAtStart = worldEpoch;
        loader.submit(() -> {
            try {
                NativeImage big = buildImage(rgb, w, h);
                NativeImage small = buildSmall(rgb, w, h);
                Minecraft.getInstance().execute(() -> {
                    try {
                        if (epoch == texEpoch && worldEpochAtStart == worldEpoch) {
                            registerTextures(big, small);
                        } else {
                            big.close();
                            small.close();
                        }
                    } finally {
                        buildingTextures.set(false);
                    }
                    if (textureBuildPending) {
                        textureBuildPending = false;
                        // only rebuild when this build was stale (a newer colour arrived) or no
                        // texture exists yet; otherwise the finished build is already the newest
                        if (epoch != texEpoch || textureId == null) {
                            ColorFrame f = colorFrame;
                            if (f != null && snap != null) {
                                buildTexturesAsync(f.width, f.height, f.rgb);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                buildingTextures.set(false);
                textureBuildPending = false;
                t.printStackTrace();
            }
        });
    }

    private static NativeImage buildImage(int[] rgb, int w, int h) {
        NativeImage img = new NativeImage(w, h, false);
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            img.setPixelABGR(i % w, i / w, 0xFF000000 | (b << 16) | (g << 8) | r);
        }
        return img;
    }

    /** 1/4 resolution box-filtered copy: used when zoomed far out so bilinear sampling has no aliasing. */
    private static NativeImage buildSmall(int[] rgb, int w, int h) {
        int sw = Math.max(1, w / 4);
        int sh = Math.max(1, h / 4);
        NativeImage small = new NativeImage(sw, sh, false);
        for (int z = 0; z < sh; z++) {
            for (int x = 0; x < sw; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int i = (z * 4 + dz) * w + (x * 4 + dx);
                        if (i >= rgb.length) continue;
                        int c = rgb[i];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                small.setPixelABGR(x, z, 0xFF000000 | ((b / n) << 16) | ((g / n) << 8) | (r / n));
            }
        }
        return small;
    }

    private void registerTextures(NativeImage big, NativeImage small) {
        Minecraft mc = Minecraft.getInstance();
        // replace cleanly: drop the previous texture objects before registering the new ones
        releaseOne(mc, textureId);
        textureId = null;
        releaseOne(mc, textureIdSmall);
        textureIdSmall = null;
        if (big != null) {
            DynamicTexture tex = new DynamicTexture(() -> "voxymap_img", big);
            Identifier id = Identifier.fromNamespaceAndPath("voxymap", "map");
            Minecraft.getInstance().getTextureManager().register(id, tex);
            textureId = id;
            texW = big.getWidth();
            texH = big.getHeight();
        }
        if (small != null) {
            DynamicTexture stex = new DynamicTexture(() -> "voxymap_small", small);
            Identifier sid = Identifier.fromNamespaceAndPath("voxymap", "map_small");
            Minecraft.getInstance().getTextureManager().register(sid, stex);
            textureIdSmall = sid;
        }
    }

    /** ARGB biome border pixels (0x00 when there is no border), or null before it is built. */
    public int[] getBiomeEdgeSnapshot() {
        return biomeEdgePx;
    }

    private static int[] buildBiomeEdges(Snapshot s) {
        int w = s.width;
        int h = s.height;
        long[] pixels = s.pixels;
        int[] biomeArr = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            if (pixels[i] != LONG_EMPTY) biomeArr[i] = (int) ((pixels[i] >>> 53) & 0x1FF);
        }
        int[] edges = new int[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i2 = z * w + x;
                if (pixels[i2] == LONG_EMPTY) continue;
                boolean edge = (x < w - 1 && pixels[i2 + 1] != LONG_EMPTY && biomeArr[i2] != biomeArr[i2 + 1])
                        || (z < h - 1 && pixels[i2 + w] != LONG_EMPTY && biomeArr[i2] != biomeArr[i2 + w]);
                if (!edge) continue;
                // same alternating alpha as the 2D overlay: a continuous but dashed-looking line
                int alpha = ((x + z) & 1) == 0 ? 0xC0 : 0x40;
                edges[i2] = (alpha << 24) | 0x00FFCC;  // ARGB: R=0 G=255 B=204 (teal)
            }
        }
        return edges;
    }

    private static NativeImage buildBiomeImage(Snapshot s) {
        int w = s.width, h = s.height;
        long[] pixels = s.pixels;
        int[] biomeArr = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            if (pixels[i] != LONG_EMPTY) biomeArr[i] = (int) ((pixels[i] >>> 53) & 0x1FF);
        }
        NativeImage bimg = new NativeImage(w, h, false);
        for (int i = 0; i < w * h; i++) {
            bimg.setPixelABGR(i % w, i / w, 0);
        }
        // 1) border mask: pixels whose 4-neighbour belongs to another biome
        boolean[] edge = new boolean[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i2 = z * w + x;
                if (pixels[i2] == LONG_EMPTY) continue;
                edge[i2] = (x < w - 1 && pixels[i2 + 1] != LONG_EMPTY && biomeArr[i2] != biomeArr[i2 + 1])
                        || (z < h - 1 && pixels[i2 + w] != LONG_EMPTY && biomeArr[i2] != biomeArr[i2 + w]);
            }
        }
        // 2) keep every border pixel, but alternate full and 25% opacity in a 1px checkerboard:
        // the line stays continuous while the dashes still read at any zoom/direction.
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i2 = z * w + x;
                if (!edge[i2]) continue;
                int alpha = ((x + z) & 1) == 0 ? 0xC0 : 0x40;
                bimg.setPixelABGR(x, z, (alpha << 24) | 0xCCFF00);
            }
        }
        return bimg;
    }

    private static int[] buildBiomeLabels(Snapshot s) {
        int w = s.width, h = s.height;
        long[] pixels = s.pixels;
        int n = w * h;
        int[] biomeArr = new int[n];
        for (int i = 0; i < n; i++) {
            if (pixels[i] != LONG_EMPTY) biomeArr[i] = (int) ((pixels[i] >>> 53) & 0x1FF);
        }
        boolean[] visited = new boolean[n];
        boolean[] inRegion = new boolean[n];
        int[] dist = new int[n];
        int[] bq = new int[n];
        int[] labels = new int[3 * 128];
        int labelCount = 0;
        int minArea = Math.max(1, VoxyMapConfig.BIOME_LABEL_MIN_AREA.get());
        for (int st = 0; st < n; st++) {
            if (visited[st] || pixels[st] == LONG_EMPTY) continue;
            int qh = 0, qt = 0;
            bq[qt++] = st;
            visited[st] = true;
            inRegion[st] = true;
            int b0 = biomeArr[st];
            while (qh < qt) {
                int ii = bq[qh++];
                int x = ii % w, z = ii / w;
                if (x > 0) { int j = ii - 1; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; inRegion[j] = true; bq[qt++] = j; } }
                if (x < w - 1) { int j = ii + 1; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; inRegion[j] = true; bq[qt++] = j; } }
                if (z > 0) { int j = ii - w; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; inRegion[j] = true; bq[qt++] = j; } }
                if (z < h - 1) { int j = ii + w; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; inRegion[j] = true; bq[qt++] = j; } }
            }
            if (qt >= minArea) {
                int point = pickLabelPoint(bq, qt, inRegion, dist, w, h);
                if ((labelCount + 1) * 3 > labels.length) {
                    labels = Arrays.copyOf(labels, Math.max(labels.length * 2, (labelCount + 1) * 3));
                }
                labels[labelCount * 3] = point % w;
                labels[labelCount * 3 + 1] = point / w;
                labels[labelCount * 3 + 2] = b0;
                labelCount++;
            }
            // reset the per-region scratch so the next region starts blank
            for (int k = 0; k < qt; k++) {
                inRegion[bq[k]] = false;
                dist[bq[k]] = 0;
            }
        }
        return Arrays.copyOf(labels, labelCount * 3);
    }

    /**
     * Label anchor inside the region: the pixel farthest from the region border (centre of the
     * largest inscribed circle). Keeps labels inside concave shapes where a plain centroid can fall
     * outside the biome.
     */
    private static int pickLabelPoint(int[] region, int count, boolean[] inRegion, int[] dist, int w, int h) {
        int[] queue = new int[count];
        int qh = 0, qt = 0;
        for (int k = 0; k < count; k++) {
            int p = region[k];
            int x = p % w, z = p / w;
            boolean border = x == 0 || z == 0 || x == w - 1 || z == h - 1
                    || !inRegion[p - 1] || !inRegion[p + 1] || !inRegion[p - w] || !inRegion[p + w];
            if (border) {
                dist[p] = 1;
                queue[qt++] = p;
            } else {
                dist[p] = Integer.MAX_VALUE;
            }
        }
        while (qh < qt) {
            int p = queue[qh++];
            int nd = dist[p] + 1;
            int x = p % w, z = p / w;
            if (x > 0 && inRegion[p - 1] && dist[p - 1] == Integer.MAX_VALUE) { dist[p - 1] = nd; queue[qt++] = p - 1; }
            if (x < w - 1 && inRegion[p + 1] && dist[p + 1] == Integer.MAX_VALUE) { dist[p + 1] = nd; queue[qt++] = p + 1; }
            if (z > 0 && inRegion[p - w] && dist[p - w] == Integer.MAX_VALUE) { dist[p - w] = nd; queue[qt++] = p - w; }
            if (z < h - 1 && inRegion[p + w] && dist[p + w] == Integer.MAX_VALUE) { dist[p + w] = nd; queue[qt++] = p + w; }
        }
        int best = region[0], bestD = -1;
        for (int k = 0; k < count; k++) {
            int p = region[k];
            if (dist[p] > bestD) {
                bestD = dist[p];
                best = p;
            }
        }
        return best;
    }

    @FunctionalInterface
    private interface BandTask {
        void run(int z0, int z1);
    }

    /** Runs a row band task on up to 8 daemon threads; falls back to inline for tiny heights. */
    private static void parallelRows(int h, BandTask task) {
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        if (threads <= 1 || h < 64) {
            task.run(0, h);
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        for (int k = 0; k < threads; k++) {
            final int z0 = k * h / threads;
            final int z1 = (k + 1) * h / threads;
            Thread th = new Thread(() -> {
                try {
                    task.run(z0, z1);
                } finally {
                    latch.countDown();
                }
            }, "voxymap-color-" + k);
            th.setDaemon(true);
            th.start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int[] renderColor(Snapshot s, String style, int sea) {
        return renderColor(s, style, sea, true);
    }

    /**
     * @param rebuildLabels rebuild the biome label array. Style switches and 3D colour re-renders
     *                      leave the biomes untouched, so they can reuse the previous labels.
     */

    /**
     * Contour mask from the smoothed land height, dropping small fragments by connected-area size.
     * Depends only on the terrain + contour settings, never on the colour theme.
     */
    private boolean[] computeContour(int[] topArr, boolean[] isWaterPix, int[] smoothTop,
                                     int lo, int hi, int w, int h, boolean enabled) {
        boolean[] contour = new boolean[w * h];
        if (!enabled) return contour;
        int contourInterval = Math.max(10, (hi - lo) / Math.max(1, VoxyMapConfig.CONTOUR_DENSITY.get()));
        int minContourArea = VoxyMapConfig.MIN_CONTOUR_AREA.get();
        boolean[] contourRaw = new boolean[w * h];
        final int[] fTopA = topArr;
        final int[] fSmoothT = smoothTop;
        final boolean[] fWaterC = isWaterPix;
        final boolean[] fRaw = contourRaw;
        final int fwC = w;
        final int fhC = h;
        final int fInterval = contourInterval;
        parallelRows(fhC, (z0, z1) -> {
            for (int z = z0; z < z1; z++) {
                int rowBase = z * fwC;
                for (int x = 0; x < fwC; x++) {
                    int i = rowBase + x;
                    if (fTopA[i] == Integer.MIN_VALUE || fWaterC[i]) continue;
                    int tv = fSmoothT[i];
                    if (tv == Integer.MIN_VALUE) continue;
                    if (x < fwC - 1 && fSmoothT[i + 1] != Integer.MIN_VALUE
                            && Math.floorDiv(tv, fInterval) != Math.floorDiv(fSmoothT[i + 1], fInterval)) {
                        fRaw[i] = true;
                    } else if (z < fhC - 1 && fSmoothT[i + fwC] != Integer.MIN_VALUE
                            && Math.floorDiv(tv, fInterval) != Math.floorDiv(fSmoothT[i + fwC], fInterval)) {
                        fRaw[i] = true;
                    }
                }
            }
        });
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (!contourRaw[start] || seen[start]) continue;
            int qh = 0, qt = 0;
            stack[qt++] = start;
            seen[start] = true;
            while (qh < qt) {
                int idx = stack[qh++];
                int x = idx % w, z = idx / w;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = zz * w + xx;
                        if (contourRaw[j] && !seen[j]) {
                            seen[j] = true;
                            stack[qt++] = j;
                        }
                    }
                }
            }
            if (qt >= minContourArea) {
                for (int k = 0; k < qt; k++) contour[stack[k]] = true;
            }
        }
        return contour;
    }

    private int[] renderColor(Snapshot s, String style, int sea, boolean rebuildLabels) {
        TerrainCache tc = terrainFor(s, sea);
        boolean wantContour = contourOverride != null ? contourOverride : VoxyMapConfig.DRAW_CONTOURS.get();
        if (tc.contourEnabled != wantContour) {
            // contour visibility is a separate switch: rebuild only the mask, keep the terrain cache
            tc.contour = computeContour(tc.topArr, tc.isWaterPix, tc.smoothTop, tc.lo, tc.hi,
                    tc.width, tc.height, wantContour);
            tc.contourEnabled = wantContour;
        }
        int[] rgb = colorize(tc, style);
        if (rebuildLabels && !skipBiomeLabels) {
            biomeLabelsFlat = buildBiomeLabels(s);
            biomeLabelsSnapshot = s;
        }
        progress = 95;
        return rgb;
    }

    /** Cached, style-independent terrain analysis shared by every colour theme. */
    private static final class TerrainCache {
        final Snapshot snapshot;
        final int sea;
        final String fingerprint;
        final int[] topArr;
        final int[] smoothTop;
        final int[] smoothFloor;
        final boolean[] isWaterPix;
        boolean[] contour;
        boolean contourEnabled;
        final float[] depthMap;
        final int lo;
        final int hi;
        final int rampLo;
        final int rampHi;
        final boolean adaptive;
        final int width;
        final int height;
        final int pixelBlocks;

        TerrainCache(Snapshot snapshot, int sea, String fingerprint, int[] topArr,
                     int[] smoothTop, int[] smoothFloor, boolean[] isWaterPix, boolean[] contour,
                     float[] depthMap, int lo, int hi, int rampLo, int rampHi,
                     boolean adaptive, int width, int height, int pixelBlocks) {
            this.snapshot = snapshot;
            this.sea = sea;
            this.fingerprint = fingerprint;
            this.topArr = topArr;
            this.smoothTop = smoothTop;
            this.smoothFloor = smoothFloor;
            this.isWaterPix = isWaterPix;
            this.contour = contour;
            this.depthMap = depthMap;
            this.lo = lo;
            this.hi = hi;
            this.rampLo = rampLo;
            this.rampHi = rampHi;
            this.adaptive = adaptive;
            this.width = width;
            this.height = height;
            this.pixelBlocks = pixelBlocks;
        }
    }

    private volatile TerrainCache terrainCache;

    /** Settings that shape the terrain analysis; changing any of them invalidates the cache. */
    private static String terrainFingerprint() {
        return VoxyMapConfig.FILL_HOLES.get() + "|" + VoxyMapConfig.DENOISE.get() + "|"
                + VoxyMapConfig.WATER_BLUR.get() + "|" + VoxyMapConfig.WATER_BLUR_RADIUS.get() + "|"
                + VoxyMapConfig.WATER_BLUR_PASSES.get() + "|" + VoxyMapConfig.ADAPTIVE_COLOR_RAMP.get() + "|"
                + VoxyMapConfig.MIN_MAX_RAMP.get() + "|" + VoxyMapConfig.CONTOUR_DENSITY.get() + "|"
                + VoxyMapConfig.MIN_CONTOUR_AREA.get();
    }

    private TerrainCache terrainFor(Snapshot s, int sea) {
        String fp = terrainFingerprint();
        TerrainCache cached = terrainCache;
        if (cached != null && cached.snapshot == s && cached.sea == sea && fp.equals(cached.fingerprint)) {
            return cached;
        }
        // Drop the stale cache before allocating the new analysis arrays. It pins the previous
        // snapshot plus ~22 B/px of arrays; on an 87M-pixel refresh that is ~2.6 GB of avoidable
        // peak and was what pushed the colour pass into OutOfMemoryError.
        terrainCache = null;
        long[] pixels = s.pixels;
        int w = s.width;
        int h = s.height;
        int pixelBlocks = s.pixelBlocks;

        // one shared BFS/scratch buffer reused by the sequential passes below
        int[] scratch = new int[w * h];
        int[] topArr = new int[pixels.length];
        int[] floorArr = new int[pixels.length];
        boolean[] isWaterPix = new boolean[pixels.length];
        {
            final long[] fPixels = pixels;
            final int[] fTop = topArr;
            final int[] fFloor = floorArr;
            final boolean[] fWater = isWaterPix;
            parallelRows(h, (z0, z1) -> {
                for (int z = z0; z < z1; z++) {
                    int rowBase = z * w;
                    for (int x = 0; x < w; x++) {
                        int i = rowBase + x;
                        if (fPixels[i] == LONG_EMPTY) {
                            fTop[i] = Integer.MIN_VALUE;
                            fFloor[i] = Integer.MIN_VALUE;
                        } else {
                            int tv = (int) ((fPixels[i] >>> 24) & 0xFFFF) - 32768;
                            int fl = (int) (fPixels[i] & 0xFFFF) - 32768;
                            boolean tw = (fPixels[i] & (1L << 52)) != 0;
                            if (tv == NO_TOP) {
                                tv = fl != NO_SOLID ? fl : Integer.MIN_VALUE;
                                tw = false;
                            }
                            fTop[i] = tv;
                            fWater[i] = tw;
                            fFloor[i] = tv == Integer.MIN_VALUE ? Integer.MIN_VALUE
                                    : ((tw && fl != NO_SOLID && fl < tv) ? fl : tv);
                        }
                    }
                }
            });
        }

        progress = 83;

        // fill interior data holes: connected empty regions that do not touch the map border
        // (and are small enough) get values spread inwards from the surrounding terrain
        if (VoxyMapConfig.FILL_HOLES.get()) {
            final int maxHoleArea = 500;
            boolean[] visited = new boolean[w * h];
            int[] queue = scratch;
            for (int sIdx = 0; sIdx < w * h; sIdx++) {
                if (visited[sIdx] || topArr[sIdx] != Integer.MIN_VALUE) continue;
                int qh = 0, qt = 0;
                queue[qt++] = sIdx;
                visited[sIdx] = true;
                boolean touchesBorder = false;
                while (qh < qt) {
                    int idx = queue[qh++];
                    int x = idx % w;
                    int z = idx / w;
                    if (x == 0 || x == w - 1 || z == 0 || z == h - 1) touchesBorder = true;
                    for (int dz = -1; dz <= 1; dz++) {
                        int zz = z + dz;
                        if (zz < 0 || zz >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            int j = zz * w + xx;
                            if (topArr[j] == Integer.MIN_VALUE && !visited[j]) {
                                visited[j] = true;
                                queue[qt++] = j;
                            }
                        }
                    }
                }
                if (qt > maxHoleArea) continue; // large empty region = unexplored
                if (touchesBorder && qt > 200) continue; // medium region touching the rim = map edge
                boolean any = true;
                while (any) {
                    any = false;
                    int[] toFill = new int[qt];
                    int[] vals = new int[qt];
                    int n = 0;
                    for (int t = 0; t < qt; t++) {
                        int idx = queue[t];
                        if (topArr[idx] != Integer.MIN_VALUE) continue;
                        int x = idx % w, z = idx / w;
                        int sum = 0, cnt = 0;
                        for (int dz = -1; dz <= 1; dz++) {
                            int zz = z + dz;
                            if (zz < 0 || zz >= h) continue;
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx;
                                if (xx < 0 || xx >= w) continue;
                                int v = topArr[zz * w + xx];
                                if (v != Integer.MIN_VALUE) {
                                    sum += v;
                                    cnt++;
                                }
                            }
                        }
                        if (cnt > 0) {
                            toFill[n] = idx;
                            vals[n] = sum / cnt;
                            n++;
                        }
                    }
                    if (n == 0) break;
                    for (int t = 0; t < n; t++) {
                        topArr[toFill[t]] = vals[t];
                        isWaterPix[toFill[t]] = false;
                        floorArr[toFill[t]] = vals[t];
                    }
                    any = true;
                }
            }
        }

        progress = 85;

        // denoise isolated land-height outliers produced by LOD merging
        if (VoxyMapConfig.DENOISE.get()) {
            int[] den = topArr.clone();
            final int[] fTop = topArr;
            final boolean[] fWater = isWaterPix;
            final int fw = w;
            final int fh = h;
            parallelRows(h - 2, (z0, z1) -> {
                int[] vals = new int[9];
                int[] sorted = new int[9];
                for (int z = 1 + z0; z < 1 + z1 && z < fh - 1; z++) {
                    for (int x = 1; x < fw - 1; x++) {
                        int idx = z * fw + x;
                        if (fWater[idx] || fTop[idx] == Integer.MIN_VALUE) continue;
                        int n = 0;
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int j = idx + dz * fw + dx;
                                if (!fWater[j] && fTop[j] != Integer.MIN_VALUE) vals[n++] = fTop[j];
                            }
                        }
                        if (n >= 5) {
                            System.arraycopy(vals, 0, sorted, 0, n);
                            Arrays.sort(sorted, 0, n);
                            int medv = sorted[n / 2];
                            if (Math.abs(fTop[idx] - medv) > 20) den[idx] = medv;
                        }
                    }
                }
            });
            topArr = den;
        }

        progress = 87;
        int[] smoothFloor = smooth(floorArr, w, h, scratch);
        int[] smoothTop = smooth(topArr, w, h, scratch);

        progress = 89;

        // water depth: real where known; missing columns fall back to a deep percentile,
        // coastal water stays shallow (the map rim is left empty)
        float[] depthMap = new float[w * h];
        boolean[] depthKnown = new boolean[w * h];
        // 90th percentile via a small histogram instead of a float[w*h] array (that array alone was
        // ~350 MB of peak heap on an 87M-pixel map)
        final int DEPTH_BINS = 4096;
        int[] depthHist = new int[DEPTH_BINS];
        int knownN = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (!isWaterPix[i] || topArr[i] == Integer.MIN_VALUE) continue;
            int floor = floorArr[i];
            if (floor < topArr[i]) {
                int d = topArr[i] - floor;
                depthMap[i] = d;
                depthKnown[i] = true;
                depthHist[Math.min(DEPTH_BINS - 1, d)]++;
                knownN++;
            }
        }
        float fillDepth = 20f;
        if (knownN > 0) {
            int target = (int) (knownN * 0.9f);
            int acc = 0;
            for (int b = 0; b < DEPTH_BINS; b++) {
                acc += depthHist[b];
                if (acc > target) {
                    fillDepth = Math.max(1f, b);
                    break;
                }
            }
        }
        final int coastalK = 6;
        boolean[] coastalBand = new boolean[w * h];
        int[] q = new int[w * h];
        int qn = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (topArr[i] == Integer.MIN_VALUE) continue;
            if (!isWaterPix[i]) {
                coastalBand[i] = true;
                q[qn++] = i;
            }
        }
        int[] next = new int[w * h];
        for (int layer = 0; layer < coastalK && qn > 0; layer++) {
            int nn = 0;
            for (int t = 0; t < qn; t++) {
                int idx = q[t];
                int x = idx % w, z = idx / w;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = zz * w + xx;
                        if (!coastalBand[j] && isWaterPix[j]) {
                            coastalBand[j] = true;
                            next[nn++] = j;
                        }
                    }
                }
            }
            int[] tmp = q;
            q = next;
            next = tmp;
            qn = nn;
        }
        for (int i = 0; i < pixels.length; i++) {
            if (isWaterPix[i] && !depthKnown[i] && topArr[i] != Integer.MIN_VALUE) {
                depthMap[i] = coastalBand[i] ? 2f : fillDepth;
                depthKnown[i] = true;
            }
        }

        progress = 91;

        // 3-pass separable box blur (sliding window) of the water depth only
        if (VoxyMapConfig.WATER_BLUR.get() && knownN > 0) {
            final int R = Math.max(1, VoxyMapConfig.WATER_BLUR_RADIUS.get());
            final int passes = Math.max(1, VoxyMapConfig.WATER_BLUR_PASSES.get());
            float[] cur = depthMap;
            float[] hbuf = new float[w * h];
            float[] vbuf = new float[w * h];
            final boolean[] fWaterB = isWaterPix;
            final int fw = w;
            final int fh = h;
            final int fR = R;
            for (int pass = 0; pass < passes; pass++) {
                final float[] fCur = cur;
                final float[] fHbuf = hbuf;
                final float[] fVbuf = vbuf;
                // horizontal pass: every row is independent
                parallelRows(fh, (z0, z1) -> {
                    for (int z = z0; z < z1; z++) {
                        int row = z * fw;
                        float sum = 0;
                        int cnt = 0;
                        for (int x = 0; x < fw && x <= fR; x++) {
                            if (fWaterB[row + x]) {
                                sum += fCur[row + x];
                                cnt++;
                            }
                        }
                        int c = cnt;
                        float su = sum;
                        for (int x = 0; x < fw; x++) {
                            int idx = row + x;
                            fHbuf[idx] = fWaterB[idx] && c > 0 ? su / c : fCur[idx];
                            int add = x + fR + 1;
                            int rem = x - fR;
                            if (add < fw && fWaterB[row + add]) {
                                su += fCur[row + add];
                                c++;
                            }
                            if (rem >= 0 && fWaterB[row + rem]) {
                                su -= fCur[row + rem];
                                c--;
                            }
                        }
                    }
                });
                // vertical pass: every column is independent -> split by column bands
                int colThreads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
                java.util.concurrent.CountDownLatch vLatch = new java.util.concurrent.CountDownLatch(
                        fw < 64 ? 1 : colThreads);
                int bands = fw < 64 ? 1 : colThreads;
                for (int band = 0; band < bands; band++) {
                    final int x0 = band * fw / bands;
                    final int x1 = (band + 1) * fw / bands;
                    Thread th = new Thread(() -> {
                        try {
                            for (int x = x0; x < x1; x++) {
                                float sum = 0;
                                int cnt = 0;
                                for (int z = 0; z < fh && z <= fR; z++) {
                                    int idx = z * fw + x;
                                    if (fWaterB[idx]) {
                                        sum += fHbuf[idx];
                                        cnt++;
                                    }
                                }
                                int c = cnt;
                                float su = sum;
                                for (int z = 0; z < fh; z++) {
                                    int idx = z * fw + x;
                                    fVbuf[idx] = fWaterB[idx] && c > 0 ? su / c : fHbuf[idx];
                                    int add = z + fR + 1;
                                    int rem = z - fR;
                                    if (add < fh && fWaterB[add * fw + x]) {
                                        su += fHbuf[add * fw + x];
                                        c++;
                                    }
                                    if (rem >= 0 && fWaterB[rem * fw + x]) {
                                        su -= fHbuf[rem * fw + x];
                                        c--;
                                    }
                                }
                            }
                        } finally {
                            vLatch.countDown();
                        }
                    }, "voxymap-blur-" + band);
                    th.setDaemon(true);
                    th.start();
                }
                try {
                    vLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                float[] tmp2 = cur;
                cur = vbuf;
                vbuf = tmp2;
            }
            depthMap = cur;
        }

        progress = 92;

        // adaptive colour ramp: stretch the land ramp to this world's actual height range
        boolean adaptive = VoxyMapConfig.ADAPTIVE_COLOR_RAMP.get();
        int lo = sea, hi = sea + 820;
        if (adaptive) {
            // histogram of land heights: O(n) instead of sorting 70M+ values. The bin range covers
            // the real min/max heights (not a fixed [-64, 512] window) so heavily modded or
            // amplified terrain above y=512 keeps its ramp detail instead of saturating.
            int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
            int n = 0;
            for (int i = 0; i < w * h; i++) {
                if (topArr[i] != Integer.MIN_VALUE && !isWaterPix[i]) {
                    if (topArr[i] < minH) minH = topArr[i];
                    if (topArr[i] > maxH) maxH = topArr[i];
                    n++;
                }
            }
            if (n > 100 && maxH >= minH) {
                int bin = Math.max(1, (maxH - minH) / 1024 + 1);
                int bins = (maxH - minH) / bin + 1;
                int[] hist = new int[bins];
                for (int i = 0; i < w * h; i++) {
                    if (topArr[i] != Integer.MIN_VALUE && !isWaterPix[i]) {
                        hist[(topArr[i] - minH) / bin]++;
                    }
                }
                int lowIdx = (int) (n * 0.01f);
                int highIdx = Math.min(n - 1, (int) (n * 0.99f));
                int acc = 0;
                int loH = minH;
                int hiH = maxH;
                boolean gotLo = false;
                for (int k = 0; k < bins; k++) {
                    acc += hist[k];
                    if (!gotLo && acc > lowIdx) {
                        loH = minH + k * bin;
                        gotLo = true;
                    }
                    if (acc > highIdx) {
                        hiH = minH + k * bin;
                        break;
                    }
                }
                lo = loH;
                hi = hiH;
                if (hi - lo < 40) hi = lo + 40;
            }
        }

        // contour mask from the smoothed land height, then drop small fragments by connected-area size
        int contourInterval = Math.max(10, (hi - lo) / Math.max(1, VoxyMapConfig.CONTOUR_DENSITY.get()));
        // optional: land ramp from sea level up to the map's highest point (green -> white);
        // water already spans shallow -> deepest blue by depth, so its deepest point is the lowest floor
        int rampLo = lo, rampHi = hi;
        if (VoxyMapConfig.MIN_MAX_RAMP.get()) {
            int maxH = Integer.MIN_VALUE;
            for (int i = 0; i < w * h; i++) {
                int top = topArr[i];
                if (top == Integer.MIN_VALUE || isWaterPix[i]) continue;
                if (top > maxH) maxH = top;
            }
            if (maxH != Integer.MIN_VALUE && maxH - sea >= 40) {
                rampLo = sea;
                rampHi = maxH;
            }
        }
        boolean drawContours = contourOverride != null ? contourOverride : VoxyMapConfig.DRAW_CONTOURS.get();
        boolean[] contour = computeContour(topArr, isWaterPix, smoothTop, lo, hi, w, h, drawContours);

        TerrainCache built = new TerrainCache(s, sea, fp, topArr, smoothTop, smoothFloor,
                isWaterPix, contour, depthMap, lo, hi, rampLo, rampHi,
                adaptive || VoxyMapConfig.MIN_MAX_RAMP.get(), w, h, pixelBlocks);
        built.contourEnabled = drawContours;
        terrainCache = built;
        return built;
    }

    private int[] colorize(TerrainCache tc, String style) {
        progress = 93;

        int[] out = new int[tc.width * tc.height];
        final float lx = -0.55f, ly = 0.70f, lz = -0.55f;
        final float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        final boolean nautical = "nautical".equals(style);
        final float landStrength = nautical ? 0.06f : 0.18f;
        final boolean drawShade = VoxyMapConfig.DRAW_HILLSHADE.get();
        final boolean drawDepth = VoxyMapConfig.DRAW_WATER_DEPTH.get();
        final float step = 2f * tc.pixelBlocks;
        final int[] fOut = out;
        final String fStyle = style;
        final int fw = tc.width;
        final int fh = tc.height;
        final int[] fTopArr = tc.topArr;
        final int[] fSmoothTop = tc.smoothTop;
        final int[] fSmoothFloor = tc.smoothFloor;
        final boolean[] fWater = tc.isWaterPix;
        final boolean[] fContour = tc.contour;
        final float[] fDepth = tc.depthMap;
        parallelRows(fh, (z0, z1) -> {
        for (int z = z0; z < z1; z++) {
            for (int x = 0; x < fw; x++) {
                int idx = z * fw + x;
                int top = fTopArr[idx];
                if (top == Integer.MIN_VALUE) {
                    fOut[idx] = 0;
                    continue;
                }
                boolean water = fWater[idx];
                int rgb;
                if (water) {
                    float dep = drawDepth ? fDepth[idx] : 1f;
                    rgb = MapStyles.waterColor(fStyle, Math.round(dep));
                } else {
                    rgb = MapStyles.landColor(fStyle, top, tc.sea, tc.rampLo, tc.rampHi, tc.adaptive);
                }
                float shade = 1f;
                if (drawShade && x > 0 && z > 0 && x < fw - 1 && z < fh - 1) {
                    int nb0, nb1, nb2, nb3;
                    if (water) {
                        nb0 = fSmoothFloor[idx - 1];
                        nb1 = fSmoothFloor[idx + 1];
                        nb2 = fSmoothFloor[idx - fw];
                        nb3 = fSmoothFloor[idx + fw];
                    } else {
                        nb0 = fSmoothTop[idx - 1];
                        nb1 = fSmoothTop[idx + 1];
                        nb2 = fSmoothTop[idx - fw];
                        nb3 = fSmoothTop[idx + fw];
                    }
                    if (nb0 != Integer.MIN_VALUE && nb1 != Integer.MIN_VALUE && nb2 != Integer.MIN_VALUE && nb3 != Integer.MIN_VALUE) {
                        float dzdx = (nb1 - nb0) / step;
                        float dzdy = (nb3 - nb2) / step;
                        float slope = (float) Math.sqrt(dzdx * dzdx + dzdy * dzdy);
                        if (slope > 0.08f) {
                            float nx = -dzdx, ny = 1f, nz = -dzdy;
                            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                            float d = (nx * lx + ny * ly + nz * lz) / (nl * ll);
                            float strength = Math.min(1f, (slope - 0.08f) / 0.6f);
                            shade = 1f + (water ? 0.10f : landStrength) * d * strength;
                        }
                    }
                }
                shade = Math.max(0.78f, Math.min(1.08f, shade));
                if (!water) {
                    if (fContour[idx]) shade *= nautical ? 0.62f : 0.70f;
                    boolean coast = (x < fw - 1 && fWater[idx + 1]) || (z < fh - 1 && fWater[idx + fw])
                            || (x > 0 && fWater[idx - 1]) || (z > 0 && fWater[idx - fw]);
                    if (coast) shade *= 0.70f;
                }
                int r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) * shade)));
                int g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) * shade)));
                int b = Math.max(0, Math.min(255, (int) ((rgb & 0xFF) * shade)));
                fOut[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        });
        return out;
    }


    private static int[] smooth(int[] arr, int w, int h, int[] tmp) {
        if (tmp == null || tmp.length != arr.length) tmp = new int[arr.length];
        int[] out = new int[arr.length];
        final int[] fArr = arr;
        final int[] fTmp = tmp;
        final int[] fOut = out;
        final int fw = w;
        final int fh = h;
        // Two separate banded passes with a latch barrier between them. Doing both passes inside a
        // single parallelRows call let the vertical pass read the neighbour band's rows before that
        // band had written its horizontal pass, which produced horizontal seams every band boundary.
        parallelRows(h, (z0, z1) -> {
            for (int z = z0; z < z1; z++) {
                int row = z * fw;
                for (int x = 0; x < fw; x++) {
                    int sum = 0, cnt = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= fw) continue;
                        int v = fArr[row + xx];
                        if (v != Integer.MIN_VALUE) {
                            sum += v;
                            cnt++;
                        }
                    }
                    fTmp[row + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
                }
            }
        });
        parallelRows(h, (z0, z1) -> {
            for (int z = z0; z < z1; z++) {
                for (int x = 0; x < fw; x++) {
                    int sum = 0, cnt = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        int zz = z + dz;
                        if (zz < 0 || zz >= fh) continue;
                        int v = fTmp[zz * fw + x];
                        if (v != Integer.MIN_VALUE) {
                            sum += v;
                            cnt++;
                        }
                    }
                    fOut[z * fw + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
                }
            }
        });
        return out;
    }
}
