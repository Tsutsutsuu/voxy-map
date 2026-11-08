package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Config screen for the Fabric build (26.2 GUI API). Works on a draft copy: Cancel discards it,
 * Apply writes it without closing, Done writes it and closes, Reset restores factory defaults.
 * Sliders can be dragged, number values clicked and typed. Saved to config/voxymap-client.json.
 */
public class MapConfigScreen extends Screen {
    private enum Group {
        GENERAL("voxymap.configuration.general"),
        MAP("voxymap.configuration.map"),
        DATA("voxymap.configuration.data"),
        THREE_D("voxymap.configuration.3d"),
        PERFORMANCE("voxymap.configuration.performance"),
        DEBUG("voxymap.configuration.debug");

        final String labelKey;

        Group(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private record Item(Group group, String name, String descKey, VoxyMapConfig.Value<?> value,
                        double min, double max, String[] options, boolean header) {
    }

    private static final int TAB_H = 20;
    private static final int ROW_H = 34;
    private static final int PAD = 8;
    private static final int CONTROL_W = 120;
    private static final int SLIDER_W = CONTROL_W - 34;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 6;

    private static final int COL_BG = 0xFF14171C;
    private static final int COL_PANEL = 0xF014171C;
    private static final int COL_PANEL_OFF = 0xE0101216;
    private static final int COL_BORDER = 0xFF3A424C;
    private static final int COL_ACCENT = 0xFF4F9BC4;
    private static final int COL_SELECTED = 0xE02A3A50;
    private static final int COL_ROW_HOVER = 0x504F9BC4;
    private static final int COL_TEXT = 0xFFE8EAED;
    private static final int COL_DIM = 0xFF8C949E;

    private final Screen parent;
    private final List<Item> items = new ArrayList<>();
    private final Map<Item, Object> draft = new HashMap<>();
    private Group group = Group.GENERAL;
    private int scroll;
    private Item dragging;
    private Item editing;
    private String editText = "";
    private final Map<String, Float> hoverMap = new HashMap<>();
    private float lastDt = 0.016f;
    /** real-time stamp of the current tab, so row fades never depend on the tab fade counter */
    private long tabSwitchNs = -1L;
    private float appearT;
    private float tabT;
    private long lastNs;
    private boolean closing;

    public MapConfigScreen(Screen parent) {
        super(Component.translatable("voxymap.ui.config"));
        this.parent = parent;
        section(Group.GENERAL, "voxymap.config.section.interface");
        add(Group.GENERAL, "showCoordinates", VoxyMapConfig.SHOW_COORDINATES, 0, 0, null);
        add(Group.GENERAL, "showHint", VoxyMapConfig.SHOW_HINT, 0, 0, null);
        section(Group.GENERAL, "voxymap.config.section.interaction");
        add(Group.GENERAL, "teleportEnabled", VoxyMapConfig.TELEPORT_ENABLED, 0, 0, null);
        add(Group.GENERAL, "defaultZoom", VoxyMapConfig.DEFAULT_ZOOM, 0.04, 8.0, null);
        add(Group.GENERAL, "rememberZoom", VoxyMapConfig.REMEMBER_ZOOM, 0, 0, null);
        add(Group.GENERAL, "arrowStyle", VoxyMapConfig.ARROW_STYLE, 0, 0, new String[]{"clean", "classic"});

        section(Group.MAP, "voxymap.config.section.terrainColor");
        add(Group.MAP, "defaultStyle", VoxyMapConfig.DEFAULT_STYLE, 0, 0, MapStyles.NAMES);
        add(Group.MAP, "adaptiveColorRamp", VoxyMapConfig.ADAPTIVE_COLOR_RAMP, 0, 0, null);
        add(Group.MAP, "landMaxRamp", VoxyMapConfig.MIN_MAX_RAMP, 0, 0, null);
        add(Group.MAP, "drawHillshade", VoxyMapConfig.DRAW_HILLSHADE, 0, 0, null);
        add(Group.MAP, "drawWaterDepth", VoxyMapConfig.DRAW_WATER_DEPTH, 0, 0, null);
        add(Group.MAP, "autoSeaLevel", VoxyMapConfig.AUTO_SEA_LEVEL, 0, 0, null);
        add(Group.MAP, "seaLevelOverride", VoxyMapConfig.SEA_LEVEL_OVERRIDE, -2048, 2048, null);
        section(Group.MAP, "voxymap.config.section.contours");
        add(Group.MAP, "drawContours", VoxyMapConfig.DRAW_CONTOURS, 0, 0, null);
        add(Group.MAP, "contourDensity", VoxyMapConfig.CONTOUR_DENSITY, 1, 24, null);
        add(Group.MAP, "minContourArea", VoxyMapConfig.MIN_CONTOUR_AREA, 0, 1000, null);
        section(Group.MAP, "voxymap.config.section.waterPost");
        add(Group.MAP, "waterBlur", VoxyMapConfig.WATER_BLUR, 0, 0, null);
        add(Group.MAP, "waterBlurRadius", VoxyMapConfig.WATER_BLUR_RADIUS, 1, 16, null);
        add(Group.MAP, "waterBlurPasses", VoxyMapConfig.WATER_BLUR_PASSES, 1, 5, null);
        add(Group.MAP, "denoise", VoxyMapConfig.DENOISE, 0, 0, null);
        add(Group.MAP, "fillHoles", VoxyMapConfig.FILL_HOLES, 0, 0, null);
        section(Group.MAP, "voxymap.config.section.overlaysLabels");
        add(Group.MAP, "showBiomes", VoxyMapConfig.SHOW_BIOMES, 0, 0, null);
        add(Group.MAP, "showRenderDistance", VoxyMapConfig.SHOW_RENDER_DISTANCE, 0, 0, null);
        add(Group.MAP, "biomeLabelMinArea", VoxyMapConfig.BIOME_LABEL_MIN_AREA, 1, 5000, null);
        add(Group.MAP, "biomeLabelBackground", VoxyMapConfig.BIOME_LABEL_BACKGROUND, 0, 0, null);

        // The old "refresh policy / frontier / incremental" block was removed: the unified 2D tile
        // renderer streams Voxy sections live and has no global refresh lane, so those options did
        // nothing. Only the options that still affect the tile path are shown here.
        section(Group.DATA, "voxymap.config.section.liveData");
        add(Group.DATA, "liveTiles", VoxyMapConfig.LIVE_TILES, 0, 0, null);
        section(Group.DATA, "voxymap.config.section.mapScaleMemory");
        add(Group.DATA, "mapScale", VoxyMapConfig.MAP_SCALE, 1, 8, null);
        section(Group.DATA, "voxymap.config.section.cache");
        add(Group.DATA, "cacheKeepCount", VoxyMapConfig.CACHE_KEEP_COUNT, 1, 100, null);
        add(Group.DATA, "diskCacheMaxMb", VoxyMapConfig.DISK_CACHE_MAX_MB, 256, 65536, null);
        // Highest Voxy LOD level that is merged into the index; a change invalidates the cached
        // index, hence it lives next to the cache settings.
        add(Group.DATA, "maxLodLevel", VoxyMapConfig.MAX_LOD_LEVEL, 0, 4, null);

        section(Group.THREE_D, "voxymap.config.section.camera");
        add(Group.THREE_D, "fov3d", VoxyMapConfig.FOV_3D, 30, 110, null);
        add(Group.THREE_D, "verticalScale3d", VoxyMapConfig.VERTICAL_SCALE_3D, 0.1, 3.0, null);
        add(Group.THREE_D, "minDistance3d", VoxyMapConfig.MIN_DISTANCE_3D, 16, 4096, null);
        add(Group.THREE_D, "maxDistance3d", VoxyMapConfig.MAX_DISTANCE_3D, 512, 32768, null);
        add(Group.THREE_D, "rotateSens3d", VoxyMapConfig.ROTATE_SENS_3D, 0.2, 5.0, null);
        add(Group.THREE_D, "panSens3d", VoxyMapConfig.PAN_SENS_3D, 0.2, 5.0, null);
        add(Group.THREE_D, "zoomSens3d", VoxyMapConfig.ZOOM_SENS_3D, 0.2, 5.0, null);
        add(Group.THREE_D, "remember3dView", VoxyMapConfig.REMEMBER_3D_VIEW, 0, 0, null);
        section(Group.THREE_D, "voxymap.config.section.terrainMesh");
        add(Group.THREE_D, "gpuTerrainNearDistance", VoxyMapConfig.GPU_TERRAIN_NEAR_DISTANCE, 64, 4096, null);
        add(Group.THREE_D, "gpuTerrainMidDistance", VoxyMapConfig.GPU_TERRAIN_MID_DISTANCE, 256, 16384, null);
        add(Group.THREE_D, "gpuTerrainNearStep", VoxyMapConfig.GPU_TERRAIN_NEAR_STEP, 1, 256, null);
        add(Group.THREE_D, "gpuTerrainMidStep", VoxyMapConfig.GPU_TERRAIN_MID_STEP, 1, 256, null);
        add(Group.THREE_D, "gpuTerrainFarStep", VoxyMapConfig.GPU_TERRAIN_FAR_STEP, 1, 256, null);
        add(Group.THREE_D, "gpuTerrainSkirtDepth", VoxyMapConfig.GPU_TERRAIN_SKIRT_DEPTH, 0, 64, null);
        add(Group.THREE_D, "gpuTerrainHysteresisPct", VoxyMapConfig.GPU_TERRAIN_HYSTERESIS_PCT, 0, 50, null);
        section(Group.THREE_D, "voxymap.config.section.markerLabels");
        add(Group.THREE_D, "playerMarkerHeight3d", VoxyMapConfig.PLAYER_MARKER_HEIGHT_3D, 0, 200, null);
        add(Group.THREE_D, "playerMarkerScale3d", VoxyMapConfig.PLAYER_MARKER_SCALE_3D, 0, 5, null);
        add(Group.THREE_D, "biomeLabel3dRange", VoxyMapConfig.BIOME_LABEL_3D_RANGE, 200, 20000, null);
        section(Group.THREE_D, "voxymap.config.section.overlays");
        add(Group.THREE_D, "autoHideContours3d", VoxyMapConfig.AUTO_HIDE_CONTOURS_3D, 0, 0, null);

        section(Group.PERFORMANCE, "voxymap.config.section.renderBudget");
        add(Group.PERFORMANCE, "gpuTextureBudgetMb", VoxyMapConfig.GPU_TEXTURE_BUDGET_MB, 32, 2048, null);
        section(Group.PERFORMANCE, "voxymap.config.section.tileCache");
        add(Group.PERFORMANCE, "tileResidentCap", VoxyMapConfig.TILE_RESIDENT_CAP, 256, 8192, null);
        add(Group.PERFORMANCE, "keep3dTilesIn2d", VoxyMapConfig.KEEP_3D_TILES_IN_2D, 0, 0, null);
        add(Group.PERFORMANCE, "releaseTexturesOnClose", VoxyMapConfig.RELEASE_TEXTURES_ON_CLOSE, 0, 0, null);
        section(Group.PERFORMANCE, "voxymap.config.section.build");
        add(Group.PERFORMANCE, "gpu3dBuildThreads", VoxyMapConfig.GPU3D_BUILD_THREADS, 0, 16, null);
        add(Group.PERFORMANCE, "gpu3dBatchBuild", VoxyMapConfig.GPU3D_BATCH_BUILD, 1, 32, null);
        section(Group.PERFORMANCE, "voxymap.config.section.advanced");
        add(Group.PERFORMANCE, "skipWorldRender", VoxyMapConfig.SKIP_WORLD_RENDER, 0, 0, null);

        section(Group.DEBUG, "voxymap.config.section.diagnostics");
        add(Group.DEBUG, "debugEnabled", VoxyMapConfig.DEBUG_ENABLED, 0, 0, null);
        add(Group.DEBUG, "debugRefreshChat", VoxyMapConfig.DEBUG_REFRESH_CHAT, 0, 0, null);
        // debugRefreshVerbose was removed: its per-stage timings only existed in the retired
        // refresh lanes, so the toggle did nothing on the tile path.
        add(Group.DEBUG, "debugVoxyEvents", VoxyMapConfig.DEBUG_VOXY_EVENTS, 0, 0, null);
        add(Group.DEBUG, "debugTileTrace", VoxyMapConfig.DEBUG_TILE_TRACE, 0, 0, null);
    }

    private void add(Group g, String name, VoxyMapConfig.Value<?> value, double min, double max, String[] options) {
        items.add(new Item(g, name, "voxymap.config.desc." + name, value, min, max, options, false));
    }

    private void section(Group g, String key) {
        items.add(new Item(g, key, "", null, 0.0, 0.0, null, true));
    }

    private static int withAlpha(int color, float t) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, t)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** Frame-rate independent hover progress, same feel as the map screen buttons. */
    private float hover(String key, boolean hovered, float dt) {
        float cur = hoverMap.getOrDefault(key, 0f);
        float target = hovered ? 1f : 0f;
        if (Math.abs(cur - target) < 0.01f) cur = target;
        else cur += (target - cur) * Math.min(1f, dt * 14f);
        hoverMap.put(key, cur);
        return cur;
    }

    private static int lerpColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** draft value of an item */
    private Object dv(Item it) {
        return draft.getOrDefault(it, it.value().get());
    }

    @SuppressWarnings("unchecked")
    private void set(Item it, Object value) {
        VoxyMapConfig.Value<?> v = it.value();
        Object def = v.getDefault();
        Object coerced;
        if (def instanceof Boolean) coerced = Boolean.parseBoolean(String.valueOf(value));
        else if (def instanceof Integer) coerced = Integer.parseInt(String.valueOf(value));
        else if (def instanceof Double) coerced = Double.parseDouble(String.valueOf(value));
        else coerced = String.valueOf(value);
        draft.put(it, coerced);
    }

    private void applyDraft() {
        int oldLodCap = VoxyMapConfig.MAX_LOD_LEVEL.get();
        for (Item it : items) {
            if (it.header()) continue;
            ((VoxyMapConfig.Value<Object>) it.value()).set(draft.get(it));
        }
        VoxyMapConfig.save();
        Gpu3DMap.reconfigureBuildThreads();
        // Rendering changes must re-colour existing tiles; otherwise the ramp toggle only
        // affects tiles loaded after the config screen closes.
        MapData.INSTANCE.requestColorRerender();
        if (oldLodCap != VoxyMapConfig.MAX_LOD_LEVEL.get()) {
            // The section index stores the LOD cap it was built with; a change invalidates it, so
            // request a rebuild instead of waiting for the next manual refresh.
            MapData.INSTANCE.updateAsync(true);
        }
    }

    private void resetDraft() {
        for (Item it : items) {
            if (!it.header()) draft.put(it, it.value().getDefault());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // Esc / external close = cancel: play the fade-out, leave the JSON untouched
        if (!closing) closing = true;
    }

    private List<Item> visible() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.group() == group) out.add(it);
        return out;
    }

    private int rowRight() {
        return width - PAD - 6;
    }

    private int listTop() {
        return 26 + PAD + TAB_H + 4;
    }

    private int listBottom() {
        return height - BTN_H - 14;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, visible().size() - visibleRows());
    }

    private int controlX() {
        return rowRight() - CONTROL_W;
    }

    private boolean isNumber(Item it) {
        Object v = dv(it);
        return (v instanceof Integer || v instanceof Double) && it.options() == null && !(v instanceof Boolean);
    }

    private int buttonY() {
        return height - BTN_H - 5;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (closing) return true;
        if (scrollY != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (closing) return true;
        int shiftY = (int) Math.round((1f - appearT) * 8f);

        // bottom buttons
        int by = buttonY();
        if (e.y() >= by && e.y() <= by + BTN_H) {
            Component resetLabel = Component.translatable("voxymap.config.button.reset");
            Component cancelLabel = Component.translatable("voxymap.config.button.cancel");
            Component applyLabel = Component.translatable("voxymap.config.button.apply");
            Component doneLabel = Component.translatable("voxymap.config.button.done");
            int resetX = PAD;
            int doneX = width - PAD - btnWidth(doneLabel);
            int applyX = doneX - BTN_GAP - btnWidth(applyLabel);
            int cancelX = applyX - BTN_GAP - btnWidth(cancelLabel);
            if (e.x() >= resetX && e.x() <= resetX + btnWidth(resetLabel)) {
                resetDraft();
                return true;
            }
            if (e.x() >= doneX && e.x() <= doneX + btnWidth(doneLabel)) {
                applyDraft();
                closing = true;
                return true;
            }
            if (e.x() >= applyX && e.x() <= applyX + btnWidth(applyLabel)) {
                applyDraft();
                return true;
            }
            if (e.x() >= cancelX && e.x() <= cancelX + btnWidth(cancelLabel)) {
                closing = true;
                return true;
            }
        }

        int tx = PAD;
        for (Group g : Group.values()) {
            int tw = tabWidth(g);
            if (e.y() >= 26 + shiftY && e.y() < 26 + shiftY + TAB_H && e.x() >= tx && e.x() < tx + tw) {
                if (group != g) {
                    tabT = 0f;
                    tabSwitchNs = -1L;
                }
                group = g;
                scroll = 0;
                editing = null;
                return true;
            }
            tx += tw + 4;
        }
        List<Item> vis = visible();
        int y = listTop() + shiftY;
        for (int i = scroll; i < Math.min(vis.size(), scroll + visibleRows()); i++) {
            Item it = vis.get(i);
            if (it.header()) {
                y += ROW_H;
                continue;
            }
            if (e.y() >= y && e.y() < y + ROW_H) {
                Object v = dv(it);
                if (v instanceof Boolean b) {
                    set(it, !b);
                } else if (it.options() != null) {
                    cycle(it);
                } else if (isNumber(it)) {
                    if (e.x() >= controlX() && e.x() <= controlX() + SLIDER_W) {
                        dragging = it;
                        applySlider(it, e.x());
                    } else if (e.x() > controlX() + SLIDER_W && e.x() <= rowRight()) {
                        editing = it;
                        editText = String.valueOf(v);
                    }
                }
                return true;
            }
            y += ROW_H;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging != null) {
            applySlider(dragging, e.x());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = null;
        return super.mouseReleased(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (editing == null) return super.charTyped(e);
        String s = e.codepointAsString();
        if (s.isEmpty()) return true;
        char c = s.charAt(0);
        boolean allowed = (c >= '0' && c <= '9')
                || (c == '.' && dv(editing) instanceof Double)
                || (c == '-' && editing.min() < 0 && editText.isEmpty());
        if (!allowed) return true;
        if (editText.length() < 12) editText += c;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (editing != null) {
            int k = e.key();
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                editing = null;
                return true;
            }
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                commitEdit();
                return true;
            }
            if (k == GLFW.GLFW_KEY_BACKSPACE) {
                if (!editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
                return true;
            }
            return true;
        }
        return super.keyPressed(e);
    }

    private void commitEdit() {
        Item it = editing;
        editing = null;
        if (it == null) return;
        Object v = dv(it);
        try {
            if (v instanceof Integer) {
                int parsed = Integer.parseInt(editText.trim());
                set(it, (int) Math.max(it.min(), Math.min(it.max(), parsed)));
            } else if (v instanceof Double) {
                double parsed = Double.parseDouble(editText.trim());
                // the control's declared range is authoritative; every Double item now has one
                double lo = Math.min(it.min(), it.max());
                double hi = Math.max(it.min(), it.max());
                set(it, Math.max(lo, Math.min(hi, Math.round(parsed * 100.0) / 100.0)));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private int tabWidth(Group g) {
        return font.width(Component.translatable(g.labelKey)) + 20;
    }

    private void cycle(Item it) {
        String[] opts = it.options();
        String cur = String.valueOf(dv(it));
        int idx = 0;
        for (int i = 0; i < opts.length; i++) if (opts[i].equals(cur)) idx = i;
        set(it, opts[(idx + 1) % opts.length]);
    }

    private void applySlider(Item it, double mx) {
        double ratio = Math.max(0, Math.min(1, (mx - controlX()) / (double) SLIDER_W));
        Object v = dv(it);
        double min = Math.min(it.min(), it.max());
        double max = Math.max(it.min(), it.max());
        if (v instanceof Integer) {
            set(it, (int) Math.round(min + ratio * (max - min)));
        } else if (v instanceof Double) {
            double val = min + ratio * (max - min);
            set(it, Math.round(val * 100.0) / 100.0);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long nowNs = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : (nowNs - lastNs) / 1_000_000_000f;
        lastNs = nowNs;
        dt = Math.min(0.1f, Math.max(0.001f, dt));
        lastDt = dt;
        // ~0.25 s open, ~0.18 s close, ~0.20 s per tab switch, frame-rate independent
        if (closing) {
            appearT = Math.max(0f, appearT - dt / 0.18f);
            if (appearT <= 0f) {
                Minecraft.getInstance().setScreenAndShow(parent);
                return;
            }
        } else {
            appearT = Math.min(1f, appearT + dt / 0.25f);
        }
        tabT = Math.min(1f, tabT + dt / 0.20f);
        float fade = appearT;
        int dy = (int) Math.round((1f - fade) * 8f);

        g.fill(0, 0, width, height, COL_BG);
        g.fill(0, 0, width, 24, withAlpha(COL_PANEL, fade));
        g.fill(0, 23, width, 24, withAlpha(COL_BORDER, fade));
        g.text(font, getTitle().copy().append(Component.literal("  ·  Voxy Map")),
                PAD, 8 + dy, withAlpha(COL_ACCENT, fade));

        int tx = PAD;
        for (Group gr : Group.values()) {
            int tw = tabWidth(gr);
            boolean active = gr == group;
            boolean tabHover = mouseX >= tx && mouseX <= tx + tw
                    && mouseY >= 26 + dy && mouseY <= 26 + dy + TAB_H;
            float th = hover("tab:" + gr.name(), tabHover, lastDt);
            g.fill(tx, 26 + dy, tx + tw, 26 + dy + TAB_H,
                    withAlpha(active ? COL_SELECTED : COL_PANEL_OFF, fade));
            if (th > 0.01f) {
                g.fill(tx, 26 + dy, tx + tw, 26 + dy + TAB_H, withAlpha(COL_ROW_HOVER, fade * th * 0.6f));
            }
            if (active) g.fill(tx, 26 + dy, tx + tw, 27 + dy, withAlpha(COL_ACCENT, fade));
            Component label = Component.translatable(gr.labelKey);
            g.text(font, label, tx + 10, 26 + dy + (TAB_H - font.lineHeight) / 2 + 1,
                    withAlpha(active ? COL_ACCENT : lerpColor(COL_TEXT, COL_ACCENT, th * 0.6f), fade));
            tx += tw + 4;
        }

        if (tabSwitchNs < 0L) tabSwitchNs = nowNs;
        float tabAge = (nowNs - tabSwitchNs) / 1_000_000_000f;
        List<Item> vis = visible();
        int y = listTop() + dy;
        for (int i = scroll; i < vis.size() && i < scroll + visibleRows(); i++) {
            Item it = vis.get(i);
            // staggered fade-in on real time: no flicker on tab switches, frame-rate independent
            float rowDelay = (i - scroll) * 0.03f;
            float rowT = fade * Math.min(1f, Math.max(0f, (tabAge - rowDelay) / 0.16f));
            if (it.header()) {
                g.fill(PAD, y, rowRight(), y + ROW_H, withAlpha(0x4010141A, rowT));
                g.fill(PAD, y + ROW_H - 1, rowRight(), y + ROW_H, withAlpha(COL_BORDER, rowT));
                g.text(font, Component.translatable(it.name()), PAD + 6,
                        y + (ROW_H - font.lineHeight) / 2 + 1, withAlpha(COL_ACCENT, rowT));
                y += ROW_H;
                continue;
            }
            boolean hover = mouseY >= y && mouseY < y + ROW_H;
            float rh = hover("row:" + i, hover, lastDt);
            g.fill(PAD, y, rowRight(), y + ROW_H, withAlpha(0x30101014, rowT));
            if (rh > 0.01f) {
                g.fill(PAD, y, rowRight(), y + ROW_H, withAlpha(COL_ROW_HOVER, rowT * rh * 0.8f));
            }
            // rows below the first get a thin divider; section headers are their own row type
            if (i > scroll) {
                g.fill(PAD, y, rowRight(), y + 1, withAlpha(0x20FFFFFF, rowT));
            }

            Component name = Component.translatable("voxymap.config." + it.name());
            g.text(font, name, PAD + 6, y + 5, withAlpha(COL_TEXT, rowT));
            Component desc = Component.translatable(it.descKey());
            g.text(font, Component.literal(desc.getString()), PAD + 6, y + 17, withAlpha(COL_DIM, rowT));

            drawControl(g, it, y, rowT);
            y += ROW_H;
        }

        drawScrollbar(g, fade);
        drawBottomBar(g, mouseX, mouseY, fade);

        // open/close fade, same as the NeoForge build
        float veil = closing ? closeT() : 1f - appearT;
        if (veil > 0.002f) {
            int a = (int) (Math.min(1f, veil) * 255f);
            g.fill(0, 0, width, height, a << 24);
        }
    }

    private float closeT() {
        return Math.min(1f, 1f - appearT);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, float fade) {
        int total = visible().size();
        if (total <= visibleRows()) return;
        int trackX = width - PAD;
        int top = listTop();
        int trackH = listBottom() - listTop();
        g.fill(trackX, top, trackX + 3, top + trackH, withAlpha(0x30FFFFFF, fade));
        int barH = Math.max(18, trackH * visibleRows() / total);
        int barY = top + (trackH - barH) * scroll / Math.max(1, maxScroll());
        g.fill(trackX, barY, trackX + 3, barY + barH, withAlpha(COL_ACCENT, fade));
    }

    private int btnWidth(Component label) {
        return font.width(label) + 12;
    }

    private void drawBottomBar(GuiGraphicsExtractor g, int mouseX, int mouseY, float fade) {
        int by = buttonY();
        Component resetLabel = Component.translatable("voxymap.config.button.reset");
        Component cancelLabel = Component.translatable("voxymap.config.button.cancel");
        Component applyLabel = Component.translatable("voxymap.config.button.apply");
        Component doneLabel = Component.translatable("voxymap.config.button.done");
        int doneX = width - PAD - btnWidth(doneLabel);
        int applyX = doneX - BTN_GAP - btnWidth(applyLabel);
        int cancelX = applyX - BTN_GAP - btnWidth(cancelLabel);

        drawBottomButton(g, PAD, by, resetLabel, mouseX, mouseY, fade);
        drawBottomButton(g, cancelX, by, cancelLabel, mouseX, mouseY, fade);
        drawBottomButton(g, applyX, by, applyLabel, mouseX, mouseY, fade);
        drawBottomButton(g, doneX, by, doneLabel, mouseX, mouseY, fade);
    }

    /** Same look as the map screen buttons: 20 px panel, accent top edge, hover tint. */
    private void drawBottomButton(GuiGraphicsExtractor g, int x, int y, Component label,
                                  int mouseX, int mouseY, float fade) {
        int w = btnWidth(label);
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + BTN_H;
        float bh = hover("btn:" + label.getString(), hover, lastDt);
        g.fill(x, y, x + w, y + BTN_H, withAlpha(COL_PANEL, fade));
        if (bh > 0.01f) {
            g.fill(x, y, x + w, y + BTN_H, withAlpha(COL_ROW_HOVER, fade * bh));
        }
        g.fill(x, y, x + w, y + 1, withAlpha(lerpColor(COL_BORDER, COL_ACCENT, bh), fade));
        g.text(font, label, x + 6, y + 6, withAlpha(lerpColor(COL_TEXT, 0xFFFFFFFF, bh), fade));
    }

    private void drawControl(GuiGraphicsExtractor g, Item it, int rowY, float alpha) {
        int cx = controlX();
        int cy = rowY + ROW_H / 2;
        Object v = dv(it);

        if (v instanceof Boolean b) {
            int w = 46, h = 14;
            int x = cx + CONTROL_W - w;
            int y = cy - h / 2;
            g.fill(x, y, x + w, y + h, withAlpha(b ? COL_SELECTED : COL_PANEL_OFF, alpha));
            if (b) g.fill(x, y, x + w, y + 1, withAlpha(COL_ACCENT, alpha));
            Component label = Component.translatable(b ? "voxymap.config.on" : "voxymap.config.off");
            g.text(font, label, x + (w - font.width(label)) / 2, y + 3,
                    withAlpha(b ? COL_ACCENT : COL_TEXT, alpha));
            return;
        }

        if (it.options() != null) {
            String cur = String.valueOf(v);
            int w = CONTROL_W, h = 14;
            int x = cx, y = cy - h / 2;
            g.fill(x, y, x + w, y + h, withAlpha(COL_PANEL_OFF, alpha));
            g.fill(x, y, x + w, y + 1, withAlpha(COL_BORDER, alpha));
            g.text(font, Component.literal(cur), x + (w - font.width(cur)) / 2, y + 3,
                    withAlpha(COL_ACCENT, alpha));
            return;
        }

        if (v instanceof Integer n) {
            double min = Math.min(it.min(), it.max());
            double max = Math.max(it.min(), it.max());
            double range = Math.max(1.0, max - min);
            double t = (n - min) / range;
            int x = cx;
            g.fill(x, cy - 1, x + SLIDER_W, cy + 1, withAlpha(0x60FFFFFF, alpha));
            int knobX = x + (int) Math.round(t * SLIDER_W);
            g.fill(knobX - 3, cy - 5, knobX + 3, cy + 5, withAlpha(0xFFFFFFFF, alpha));
            g.fill(x, cy - 1, knobX, cy + 1, withAlpha(COL_ACCENT, alpha));
            drawValue(g, it, String.valueOf(n), cx + SLIDER_W + 6, cy - 4, alpha);
            return;
        }

        if (v instanceof Double d) {
            double min = Math.min(it.min(), it.max());
            double max = Math.max(it.min(), it.max());
            double span = Math.max(1.0e-9, max - min);
            double t = Math.max(0, Math.min(1, (d - min) / span));
            int x = cx;
            g.fill(x, cy - 1, x + SLIDER_W, cy + 1, withAlpha(0x60FFFFFF, alpha));
            int knobX = x + (int) Math.round(t * SLIDER_W);
            g.fill(knobX - 3, cy - 5, knobX + 3, cy + 5, withAlpha(0xFFFFFFFF, alpha));
            g.fill(x, cy - 1, knobX, cy + 1, withAlpha(COL_ACCENT, alpha));
            drawValue(g, it, String.format(java.util.Locale.ROOT, "%.2f", d), cx + SLIDER_W + 6, cy - 4, alpha);
        }
    }

    private void drawValue(GuiGraphicsExtractor g, Item it, String text, int x, int y, float alpha) {
        if (editing == it) {
            String shown = editText + "_";
            g.fill(x - 2, y - 1, x + Math.max(font.width(shown), 24) + 2, y + 10,
                    withAlpha(COL_SELECTED, alpha));
            g.text(font, Component.literal(shown), x, y, withAlpha(COL_ACCENT, alpha));
        } else {
            g.text(font, Component.literal(text), x, y, withAlpha(COL_TEXT, alpha));
        }
    }
}
