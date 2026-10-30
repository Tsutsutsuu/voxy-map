package com.voxymap;

import com.github.luben.zstd.Zstd;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
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
    private static final int CACHE_VERSION = 5;
    private static final int FALLBACK_SEA = 63;

    private static final class Snapshot {
        final long[] pixels;
        final int width, height;
        final long minWX, minWZ;
        final int pixelBlocks;
        final Map<Integer, String> biomeNames;

        Snapshot(long[] pixels, int width, int height, long minWX, long minWZ, int pixelBlocks, Map<Integer, String> biomeNames) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.minWX = minWX;
            this.minWZ = minWZ;
            this.pixelBlocks = pixelBlocks;
            this.biomeNames = biomeNames;
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
    private volatile ResourceLocation biomeTextureId;
    private volatile int[] biomeLabelsFlat = new int[0];
    private volatile String style = MapStyles.NAMES[0];
    /** Per-world overlay toggles, persisted in the world's cache sidecar file (see loadUiState). */
    private volatile boolean biomeOverlay;
    private volatile boolean renderDistanceOverlay;
    /** Per-world map scale: 1..8 blocks per pixel (1:1 to 1:8). */
    private volatile int mapScale = 2;
    private volatile ResourceLocation textureId;
    private volatile ResourceLocation textureIdSmall;
    private volatile int[] lastRgb;
    private volatile int lastW, lastH;
    private volatile boolean loading;
    private volatile boolean styleChanging;
    private volatile int progress = -1;
    private volatile String error = "";
    private volatile Path lastStorage;
    /** World the current snapshot was built from, used to reuse it when rejoining that world. */
    private volatile Path snapStorage;
    /** Palette the current {@code lastRgb} was rendered with. */
    private volatile String lastRenderedStyle;
    private boolean styleInitialized;
    private boolean initialRefreshDone;
    /** Bumped whenever the client leaves/joins a world, so stale build results can be dropped. */
    private volatile int worldEpoch;

    private long sessionStartMs;
    private double lastUpdateX = Double.NaN, lastUpdateZ = Double.NaN;

    private MapData() {
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

    public ResourceLocation getTexture() {
        return textureId;
    }

    public ResourceLocation getTextureSmall() {
        return textureIdSmall;
    }

    public ResourceLocation getBiomeTexture() {
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
                var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
                for (ResourceLocation rl : registry.keySet()) {
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
    public int getMapScale() {
        return mapScale;
    }

    public void setMapScale(int value) {
        mapScale = Math.max(1, Math.min(8, value));
        saveUiState();
    }

    private int effectivePixelBlocks() {
        return Math.max(1, Math.min(8, mapScale));
    }

    /** Sidecar file next to the world cache: {@code <storage-hash>.state}. */
    private static Path uiStateFile(Path storage) {
        return cacheDir().resolve(storage.getParent().getFileName() + ".state");
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

    public void setStyle(String s) {
        if (!isReady() || loading || styleChanging) return;
        style = s;
        saveUiState();
        Snapshot target = snap;
        int sea = seaLevel();
        styleChanging = true;
        loader.submit(() -> {
            try {
                int[] rgb = renderColor(target, s, sea);
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
        lastUpdateX = Double.NaN;
        lastUpdateZ = Double.NaN;
        initialRefreshDone = false;
        worldEpoch++;
        // Keep the built grid and the rendered colours: rejoining the same world can re-upload the
        // cached texture instead of rebuilding and re-rendering (see updateAsync). A different world
        // is detected by comparing the storage path (needsWorldReload) and drops the old grid first.
        releaseTextures();
        error = "";
        progress = -1;
        biomeKeyCache.clear();
        applyConfiguredDefaults();
    }

    /** True when the kept snapshot does not belong to the world that is loaded now. */
    public boolean needsWorldReload() {
        if (snap == null) return false;
        Path storage = findStorage();
        return storage == null || !storage.equals(snapStorage);
    }

    /** Drops the kept snapshot and its textures before a different world is built. */
    public void dropSnapshotForWorldChange() {
        if (snap == null && lastRgb == null) return;
        snap = null;
        snapStorage = null;
        lastRgb = null;
        lastRenderedStyle = null;
        lastW = 0;
        lastH = 0;
        biomeLabelsFlat = new int[0];
        releaseTextures();
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
        Minecraft mc = Minecraft.getInstance();
        final int sea = seaLevel();
        final int epoch = worldEpoch;
        loader.submit(() -> {
            boolean handedToMainThread = false;
            try {
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
                    final int[] keptRgb = lastRgb;
                    if (keptRgb != null && style.equals(lastRenderedStyle)) {
                        handedToMainThread = true;
                        mc.execute(() -> {
                            try {
                                if (textureId == null) uploadTexture(kept, keptRgb);
                                progress = 100;
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
                Snapshot built = forceReload ? null : (sameWorld ? snap : loadCache(storage));
                // a cache from a different map scale cannot be reused: rebuild at the new scale
                if (built != null && built.pixelBlocks != effectivePixelBlocks()) built = null;
                if (built == null) {
                    built = buildPixels(storage);
                    if (built == null) {
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
                int[] rgb = renderColor(target, renderStyle, sea);
                progress = 95;
                handedToMainThread = true;
                mc.execute(() -> {
                    try {
                        progress = 95;
                        uploadTexture(target, rgb);
                        lastRenderedStyle = renderStyle;
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
            } catch (Throwable t) {
                t.printStackTrace();
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
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
        if ((movedAllowed || timeUp || oldWorld) && !loading) {
            if (timeUp || oldWorld) initialRefreshDone = true;
            updateAsync(false);
        }
    }

    // ------------------------------------------------------------ disk cache

    private static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("voxymap-cache");
    }

    private static Path cacheFile(Path storage) {
        return cacheDir().resolve(storage.getParent().getFileName() + ".vxm");
    }

    private static void saveCache(Path storage, Snapshot s) {
        try {
            Files.createDirectories(cacheDir());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(0x56584D31);
            dos.writeInt(CACHE_VERSION);
            dos.writeInt(s.width);
            dos.writeInt(s.height);
            dos.writeLong(s.minWX);
            dos.writeLong(s.minWZ);
            dos.writeInt(s.pixelBlocks);
            dos.writeLong(0L);
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
            List<Path> caches = new ArrayList<>();
            List<Path> leftovers = new ArrayList<>();
            List<Path> states = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".vxm")) {
                        caches.add(p);
                    } else if (name.endsWith(".state")) {
                        states.add(p);
                    } else if (name.endsWith(".tmp")) {
                        leftovers.add(p);
                    }
                }
            }
            for (Path p : leftovers) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            }
            if (caches.size() <= keep) return;
            caches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (Exception e) {
                    return 0;
                }
            });
            for (int i = keep; i < caches.size(); i++) {
                try {
                    Files.deleteIfExists(caches.get(i));
                } catch (Exception ignored) {
                }
            }
            // sidecars only make sense next to their cache
            for (Path state : states) {
                String name = state.getFileName().toString();
                String cacheName = name.substring(0, name.length() - ".state".length()) + ".vxm";
                if (!Files.isRegularFile(state.resolveSibling(cacheName))) {
                    try {
                        Files.deleteIfExists(state);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
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
            long cachedMtime = dis.readLong();
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
            return new Snapshot(px, w, h, mwx, mwz, pb, bio);
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
            int gw = (int) ((maxWXv - minWXv + pixelBlocks - 1) / pixelBlocks);
            int gh = (int) ((maxWZv - minWZv + pixelBlocks - 1) / pixelBlocks);
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
            return new Snapshot(pix, gw, gh, minWXv, minWZv, pixelBlocks, biomeNames);
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
        int[] rgb = lastRgb;
        int w = lastW, h = lastH;
        if (rgb == null || w <= 0 || h <= 0) return false;
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
        ResourceLocation[] ids = {textureId, textureIdSmall, biomeTextureId};
        for (ResourceLocation id : ids) {
            if (id == null) continue;
            var texture = mc.getTextureManager().getTexture(id);
            if (texture != null) texture.close();
            mc.getTextureManager().release(id);
        }
        textureId = null;
        textureIdSmall = null;
        biomeTextureId = null;
        biomeImage = null;
    }

    /** Re-upload the main textures from the last rendered grid (fast, used when reopening). */
    public void ensureMainTextures() {
        Snapshot s = snap;
        int[] rgb = lastRgb;
        if (s == null || rgb == null) return;
        if (textureId == null) uploadTexture(s, rgb);
    }

    /** Build and upload the 1-bit biome boundary mask (only when the overlay is on). */
    public void ensureBiomeTexture() {
        Snapshot s = snap;
        if (s == null || biomeTextureId != null) return;
        if (biomeImage == null) generateBiomeOverlay(s, new int[s.width * s.height]);
        NativeImage bimg = biomeImage;
        if (bimg == null) return;
        DynamicTexture btex = new DynamicTexture(bimg);
        btex.setFilter(false, false);
        ResourceLocation bid = ResourceLocation.fromNamespaceAndPath("voxymap", "map_biomes");
        Minecraft.getInstance().getTextureManager().register(bid, btex);
        biomeTextureId = bid;
    }

    private void uploadTexture(Snapshot s, int[] rgb) {
        lastRgb = rgb;
        lastW = s.width;
        lastH = s.height;
        NativeImage img = new NativeImage(s.width, s.height, false);
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            img.setPixelRGBA(i % s.width, i / s.width, 0xFF000000 | (b << 16) | (g << 8) | r);
        }
        DynamicTexture tex = new DynamicTexture(img);
        tex.setFilter(false, false);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("voxymap", "map");
        Minecraft.getInstance().getTextureManager().register(id, tex);
        textureId = id;

        // 1/4 resolution box-filtered copy: used when zoomed far out so bilinear
        // sampling has no visible aliasing (poor man's mipmap)
        int sw = Math.max(1, s.width / 4);
        int sh = Math.max(1, s.height / 4);
        NativeImage small = new NativeImage(sw, sh, false);
        for (int z = 0; z < sh; z++) {
            for (int x = 0; x < sw; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int i = (z * 4 + dz) * s.width + (x * 4 + dx);
                        if (i >= rgb.length) continue;
                        int c = rgb[i];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                small.setPixelRGBA(x, z, 0xFF000000 | ((b / n) << 16) | ((g / n) << 8) | (r / n));
            }
        }
        DynamicTexture stex = new DynamicTexture(small);
        stex.setFilter(true, false);
        ResourceLocation sid = ResourceLocation.fromNamespaceAndPath("voxymap", "map_small");
        Minecraft.getInstance().getTextureManager().register(sid, stex);
        textureIdSmall = sid;

        NativeImage bimg = biomeImage;
        if (bimg != null) {
            DynamicTexture btex = new DynamicTexture(bimg);
            btex.setFilter(false, false);
            ResourceLocation bid = ResourceLocation.fromNamespaceAndPath("voxymap", "map_biomes");
            Minecraft.getInstance().getTextureManager().register(bid, btex);
            biomeTextureId = bid;
        }
    }

    private void generateBiomeOverlay(Snapshot s, int[] scratch) {
        long[] pixels = s.pixels;
        int w = s.width;
        int h = s.height;
        // biome boundary image + label positions
        int[] biomeArr = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            if (pixels[i] != LONG_EMPTY) biomeArr[i] = (int) ((pixels[i] >>> 53) & 0x1FF);
        }
        NativeImage bimg = new NativeImage(w, h, false);
        for (int i = 0; i < w * h; i++) {
            bimg.setPixelRGBA(i % w, i / w, 0);
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
        // 2) 1px checkerboard mask on the border: half of the border pixels stay, so the dashes read
        // as a regular dotted line no matter which way the border runs.
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i2 = z * w + x;
                if (edge[i2] && ((x + z) & 1) == 0) {
                    bimg.setPixelRGBA(x, z, 0xC0FFFF00);
                }
            }
        }
        biomeImage = bimg;
        int[] labels = new int[3 * 128];
        int labelCount = 0;
        {
            boolean[] visited = new boolean[w * h];
            int[] bq = scratch;
            int minArea = Math.max(1, VoxyMapConfig.BIOME_LABEL_MIN_AREA.get());
            for (int st = 0; st < w * h; st++) {
                if (visited[st] || pixels[st] == LONG_EMPTY) continue;
                int qh = 0, qt = 0;
                bq[qt++] = st;
                visited[st] = true;
                long sumX = 0, sumZ = 0;
                int b0 = biomeArr[st];
                while (qh < qt) {
                    int ii = bq[qh++];
                    int x = ii % w, z = ii / w;
                    sumX += x;
                    sumZ += z;
                    if (x > 0) { int j = ii - 1; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; } }
                    if (x < w - 1) { int j = ii + 1; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; } }
                    if (z > 0) { int j = ii - w; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; } }
                    if (z < h - 1) { int j = ii + w; if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; } }
                }
                if (qt >= minArea) {
                    if ((labelCount + 1) * 3 > labels.length) {
                        labels = Arrays.copyOf(labels, Math.max(labels.length * 2, (labelCount + 1) * 3));
                    }
                    labels[labelCount * 3] = (int) (sumX / qt);
                    labels[labelCount * 3 + 1] = (int) (sumZ / qt);
                    labels[labelCount * 3 + 2] = b0;
                    labelCount++;
                }
            }
        }
        biomeLabelsFlat = Arrays.copyOf(labels, labelCount * 3);
    }

    private int[] renderColor(Snapshot s, String style, int sea) {
        long[] pixels = s.pixels;
        int w = s.width;
        int h = s.height;
        int pixelBlocks = s.pixelBlocks;

        // one shared BFS/scratch buffer reused by the sequential passes below
        int[] scratch = new int[w * h];
        int[] topArr = new int[pixels.length];
        int[] floorArr = new int[pixels.length];
        boolean[] isWaterPix = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == LONG_EMPTY) {
                topArr[i] = Integer.MIN_VALUE;
                floorArr[i] = Integer.MIN_VALUE;
            } else {
                int t = (int) ((pixels[i] >>> 24) & 0xFFFF) - 32768;
                int fl = (int) (pixels[i] & 0xFFFF) - 32768;
                boolean tw = (pixels[i] & (1L << 52)) != 0;
                if (t == NO_TOP) {
                    t = fl != NO_SOLID ? fl : Integer.MIN_VALUE;
                    tw = false;
                }
                topArr[i] = t;
                isWaterPix[i] = tw;
                floorArr[i] = t == Integer.MIN_VALUE ? Integer.MIN_VALUE
                        : ((tw && fl != NO_SOLID && fl < t) ? fl : t);
            }
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
            for (int z = 1; z < h - 1; z++) {
                for (int x = 1; x < w - 1; x++) {
                    int idx = z * w + x;
                    if (isWaterPix[idx] || topArr[idx] == Integer.MIN_VALUE) continue;
                    int[] vals = new int[9];
                    int n = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = idx + dz * w + dx;
                            if (!isWaterPix[j] && topArr[j] != Integer.MIN_VALUE) vals[n++] = topArr[j];
                        }
                    }
                    if (n >= 5) {
                        int[] sorted = Arrays.copyOf(vals, n);
                        Arrays.sort(sorted);
                        int medv = sorted[n / 2];
                        if (Math.abs(topArr[idx] - medv) > 20) den[idx] = medv;
                    }
                }
            }
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
        float[] knownVals = new float[w * h];
        int knownN = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (!isWaterPix[i] || topArr[i] == Integer.MIN_VALUE) continue;
            int floor = floorArr[i];
            if (floor < topArr[i]) {
                depthMap[i] = topArr[i] - floor;
                depthKnown[i] = true;
                knownVals[knownN++] = depthMap[i];
            }
        }
        float fillDepth = 20f;
        if (knownN > 0) {
            Arrays.sort(knownVals, 0, knownN);
            fillDepth = knownVals[Math.min(knownN - 1, (int) (knownN * 0.9f))];
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
            for (int pass = 0; pass < passes; pass++) {
                for (int z = 0; z < h; z++) {
                    int row = z * w;
                    float sum = 0;
                    int cnt = 0;
                    for (int x = 0; x < w && x <= R; x++) {
                        if (isWaterPix[row + x]) {
                            sum += cur[row + x];
                            cnt++;
                        }
                    }
                    for (int x = 0; x < w; x++) {
                        int idx = row + x;
                        hbuf[idx] = isWaterPix[idx] && cnt > 0 ? sum / cnt : cur[idx];
                        int add = x + R + 1;
                        int rem = x - R;
                        if (add < w && isWaterPix[row + add]) {
                            sum += cur[row + add];
                            cnt++;
                        }
                        if (rem >= 0 && isWaterPix[row + rem]) {
                            sum -= cur[row + rem];
                            cnt--;
                        }
                    }
                }
                for (int x = 0; x < w; x++) {
                    float sum = 0;
                    int cnt = 0;
                    for (int z = 0; z < h && z <= R; z++) {
                        int idx = z * w + x;
                        if (isWaterPix[idx]) {
                            sum += hbuf[idx];
                            cnt++;
                        }
                    }
                    for (int z = 0; z < h; z++) {
                        int idx = z * w + x;
                        vbuf[idx] = isWaterPix[idx] && cnt > 0 ? sum / cnt : hbuf[idx];
                        int add = z + R + 1;
                        int rem = z - R;
                        if (add < h && isWaterPix[add * w + x]) {
                            sum += hbuf[add * w + x];
                            cnt++;
                        }
                        if (rem >= 0 && isWaterPix[rem * w + x]) {
                            sum -= hbuf[rem * w + x];
                            cnt--;
                        }
                    }
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
            int[] landVals = new int[w * h];
            int n = 0;
            for (int i = 0; i < w * h; i++) {
                if (topArr[i] != Integer.MIN_VALUE && !isWaterPix[i]) landVals[n++] = topArr[i];
            }
            if (n > 100) {
                Arrays.sort(landVals, 0, n);
                lo = landVals[(int) (n * 0.01f)];
                hi = landVals[Math.min(n - 1, (int) (n * 0.99f))];
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
        boolean drawContours = VoxyMapConfig.DRAW_CONTOURS.get();
        int minContourArea = VoxyMapConfig.MIN_CONTOUR_AREA.get();
        boolean[] contourRaw = new boolean[w * h];
        if (drawContours) {
            for (int i = 0; i < w * h; i++) {
                if (topArr[i] == Integer.MIN_VALUE || isWaterPix[i]) continue;
                int t = smoothTop[i];
                if (t == Integer.MIN_VALUE) continue;
                int x = i % w, z = i / w;
                if (x < w - 1 && smoothTop[i + 1] != Integer.MIN_VALUE && Math.floorDiv(t, contourInterval) != Math.floorDiv(smoothTop[i + 1], contourInterval)) {
                    contourRaw[i] = true;
                } else if (z < h - 1 && smoothTop[i + w] != Integer.MIN_VALUE && Math.floorDiv(t, contourInterval) != Math.floorDiv(smoothTop[i + w], contourInterval)) {
                    contourRaw[i] = true;
                }
            }
        }
        boolean[] contour = new boolean[w * h];
        if (drawContours) {
            boolean[] seen = new boolean[w * h];
            int[] stack = scratch;
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
        }

        progress = 93;
        int[] out = new int[w * h];
        final float lx = -0.55f, ly = 0.70f, lz = -0.55f;
        final float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        boolean nautical = "nautical".equals(style);
        float landStrength = nautical ? 0.06f : 0.18f;
        boolean drawShade = VoxyMapConfig.DRAW_HILLSHADE.get();
        boolean drawDepth = VoxyMapConfig.DRAW_WATER_DEPTH.get();
        float step = 2f * pixelBlocks;
        for (int z = 0; z < h; z++) {
            if ((z & 255) == 0) progress = 93 + (int) ((long) z * 2L / h);
            for (int x = 0; x < w; x++) {
                int idx = z * w + x;
                int top = topArr[idx];
                if (top == Integer.MIN_VALUE) {
                    out[idx] = 0;
                    continue;
                }
                boolean water = isWaterPix[idx];
                int rgb;
                if (water) {
                    float dep = drawDepth ? depthMap[idx] : 1f;
                    rgb = MapStyles.waterColor(style, Math.round(dep));
                } else {
                    rgb = MapStyles.landColor(style, top, sea, rampLo, rampHi, adaptive || VoxyMapConfig.MIN_MAX_RAMP.get());
                }
                float shade = 1f;
                if (drawShade && x > 0 && z > 0 && x < w - 1 && z < h - 1) {
                    int nb0, nb1, nb2, nb3;
                    if (water) {
                        nb0 = smoothFloor[idx - 1];
                        nb1 = smoothFloor[idx + 1];
                        nb2 = smoothFloor[idx - w];
                        nb3 = smoothFloor[idx + w];
                    } else {
                        nb0 = smoothTop[idx - 1];
                        nb1 = smoothTop[idx + 1];
                        nb2 = smoothTop[idx - w];
                        nb3 = smoothTop[idx + w];
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
                    if (contour[idx]) shade *= nautical ? 0.62f : 0.70f;
                    boolean coast = (x < w - 1 && isWaterPix[idx + 1]) || (z < h - 1 && isWaterPix[idx + w])
                            || (x > 0 && isWaterPix[idx - 1]) || (z > 0 && isWaterPix[idx - w]);
                    if (coast) shade *= 0.70f;
                }
                int r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) * shade)));
                int g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) * shade)));
                int b = Math.max(0, Math.min(255, (int) ((rgb & 0xFF) * shade)));
                out[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        generateBiomeOverlay(s, scratch);
        progress = 95;

        return out;
    }

    private static int[] smooth(int[] arr, int w, int h, int[] tmp) {
        if (tmp == null || tmp.length != arr.length) tmp = new int[arr.length];
        int[] out = new int[arr.length];
        for (int z = 0; z < h; z++) {
            int row = z * w;
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    int v = arr[row + xx];
                    if (v != Integer.MIN_VALUE) {
                        sum += v;
                        cnt++;
                    }
                }
                tmp[row + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
            }
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    int v = tmp[zz * w + x];
                    if (v != Integer.MIN_VALUE) {
                        sum += v;
                        cnt++;
                    }
                }
                out[z * w + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
            }
        }
        return out;
    }
}
