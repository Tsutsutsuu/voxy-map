package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
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

    private static final int TAB_GAP = 4;
    private static final int ABOUT_ICON_SIZE = 48;
    private static final int ABOUT_AVATAR_SIZE = 24;
    private static final int ABOUT_LINK_H = 36;
    private static final int ABOUT_LINK_GAP = 8;

    private static final Identifier TEX_ABOUT_ICON = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/about_icon.png");
    private static final Identifier TEX_ABOUT_AUTHOR = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/about_author.png");
    private static final Identifier TEX_PLATFORM_GITHUB = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/platform_github.png");
    private static final Identifier TEX_PLATFORM_MODRINTH = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/platform_modrinth.png");
    private static final Identifier TEX_PLATFORM_CURSEFORGE = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/platform_curseforge.png");
    private static final Identifier TEX_ABOUT_BUG = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/about_bug.png");

    private static final String URL_GITHUB = "https://github.com/Tsutsutsuu/voxy-map";
    private static final String URL_MODRINTH = "https://modrinth.com/mod/voxy-map";
    private static final String URL_CURSEFORGE = "https://www.curseforge.com/minecraft/mc-mods/voxy-map";
    private static final String URL_ISSUES = "https://github.com/Tsutsutsuu/voxy-map/issues";
    private static final String[] ABOUT_LINK_URLS = {URL_GITHUB, URL_MODRINTH, URL_CURSEFORGE, URL_ISSUES};
    private static final String[] ABOUT_LINK_KEYS = {
            "voxymap.about.github", "voxymap.about.modrinth", "voxymap.about.curseforge", "voxymap.about.issues"
    };
    private static final Identifier[] ABOUT_LINK_ICONS = {
            TEX_PLATFORM_GITHUB, TEX_PLATFORM_MODRINTH, TEX_PLATFORM_CURSEFORGE, TEX_PLATFORM_GITHUB
    };

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
    /** shared clock for the description marquee; set on the first rendered frame */
    private long descClockNs;
    /** overlong help text scrolls at this speed (GUI px/s) with a gap between loops */
    private static final float DESC_SPEED_PX_S = 60f;
    private static final int DESC_GAP_PX = 32;
    private float appearT;
    private float tabT;
    private long lastNs;
    private boolean closing;
    /** seventh pseudo-tab: the About page is not a config group */
    private boolean aboutTab;
    private int tabScroll;
    private int aboutScroll;
    private int aboutContentHeight = 1;
    private final int[] aboutLinkY = new int[4];
    private final int[] aboutLinkX = new int[4];
    private final int[] aboutLinkW = new int[4];
    private int feedbackButtonY;
    /** In-game feedback editor state (opens from the About tab). */
    private boolean feedbackOpen;
    private final StringBuilder feedbackText = new StringBuilder();
    private MultiLineEditBox feedbackInput;
    private boolean feedbackAttachLog;
    private boolean feedbackAttachMods = true;
    private boolean feedbackSending;
    private String feedbackStatus = "";
    private static final int FEEDBACK_MAX_CHARS = 1_200;

    public MapConfigScreen(Screen parent) {
        super(Component.translatable("voxymap.ui.config"));
        this.parent = parent;
        section(Group.GENERAL, "voxymap.config.section.interface");
        add(Group.GENERAL, "showCoordinates", VoxyMapConfig.SHOW_COORDINATES, 0, 0, null);
        add(Group.GENERAL, "showHint", VoxyMapConfig.SHOW_HINT, 0, 0, null);
        add(Group.GENERAL, "hideHud", VoxyMapConfig.HIDE_HUD, 0, 0, null);
        add(Group.GENERAL, "showStartNotice", VoxyMapConfig.SHOW_START_NOTICE, 0, 0, null);
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
        add(Group.MAP, "lightStrength", VoxyMapConfig.LIGHT_STRENGTH, 0, 100, null);
        add(Group.MAP, "lightAmbient", VoxyMapConfig.LIGHT_AMBIENT, 0, 100, null);
        add(Group.MAP, "biomeColors", VoxyMapConfig.BIOME_COLORS, 0, 0, null);
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
        section(Group.MAP, "voxymap.config.section.xaero");
        add(Group.MAP, "xaeroWaypoints", VoxyMapConfig.XAERO_WAYPOINTS, 0, 0, null);
        add(Group.MAP, "xaeroWaypointLabels", VoxyMapConfig.XAERO_WAYPOINT_LABELS, 0, 0, null);
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
        add(Group.THREE_D, "gpuTerrainVoxel", VoxyMapConfig.GPU_TERRAIN_VOXEL, 0, 0, null);
        add(Group.THREE_D, "advanced3dRender", VoxyMapConfig.ADVANCED_3D_RENDER, 0, 0, null);
        add(Group.THREE_D, "advanced3dAo", VoxyMapConfig.ADVANCED_3D_AO, 0, 100, null);
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

    @Override
    protected void init() {
        super.init();
        FeedbackBox box = feedbackBox();
        feedbackInput = MultiLineEditBox.builder()
                .setX(box.textX() + 4)
                .setY(box.textY() + 3)
                .setPlaceholder(Component.translatable("voxymap.feedback.placeholder"))
                .setTextColor(COL_TEXT)
                .setTextShadow(true)
                .setCursorColor(COL_ACCENT)
                .setShowBackground(false)
                .setShowDecorations(false)
                .build(font, box.textW() - 8, box.textH() - 6,
                        Component.translatable("voxymap.feedback.title"));
        feedbackInput.setCharacterLimit(FEEDBACK_MAX_CHARS);
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
        boolean oldStartNotice = VoxyMapConfig.SHOW_START_NOTICE.get();
        for (Item it : items) {
            if (it.header()) continue;
            ((VoxyMapConfig.Value<Object>) it.value()).set(draft.get(it));
        }
        VoxyMapConfig.save();
        if (!oldStartNotice && VoxyMapConfig.SHOW_START_NOTICE.get()) {
            // re-enabled after a session-level "OK": allow the notice to appear again this session
            MapScreen.resetStartNoticeSession();
        }
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
        if (feedbackOpen) return true;
        if (scrollY != 0) {
            int shiftY = (int) Math.round((1f - appearT) * 8f);
            if (my >= 26 + shiftY && my <= 26 + shiftY + TAB_H && tabMaxScroll() > 0) {
                tabScroll = clamp(tabScroll - (int) Math.signum(scrollY) * 24, 0, tabMaxScroll());
                return true;
            }
            if (aboutTab) {
                aboutScroll = clamp(aboutScroll - (int) Math.signum(scrollY) * 24,
                        0, aboutMaxScroll());
                return true;
            }
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (closing) return true;
        if (feedbackOpen) {
            if (feedbackInput != null && feedbackInput.mouseClicked(e, doubleClick)) return true;
            handleFeedbackClick((int) e.x(), (int) e.y());
            return true;
        }
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

        if (aboutTab) {
            boolean inContent = e.y() >= listTop() + shiftY && e.y() < listBottom();
            if (inContent && feedbackButtonY > 0
                    && e.y() >= feedbackButtonY && e.y() < feedbackButtonY + 24) {
                feedbackOpen = true;
                feedbackStatus = "";
                if (feedbackInput != null) {
                    feedbackInput.setFocused(true);
                    setFocused(feedbackInput);
                }
                return true;
            }
            for (int i = 0; inContent && i < ABOUT_LINK_URLS.length; i++) {
                int ly = aboutLinkY[i];
                if (ly > 0 && e.y() >= ly && e.y() < ly + ABOUT_LINK_H - 2
                        && e.x() >= aboutLinkX[i] - 4 && e.x() <= aboutLinkX[i] + aboutLinkW[i]) {
                    openLink(ABOUT_LINK_URLS[i]);
                    return true;
                }
            }
        }

        for (int i = 0; i < tabCount(); i++) {
            int tx = tabX(i);
            int tw = tabWidth(i);
            if (e.y() >= 26 + shiftY && e.y() < 26 + shiftY + TAB_H
                    && e.x() >= Math.max(PAD, tx) && e.x() < Math.min(width - PAD, tx + tw)) {
                selectTab(i);
                return true;
            }
        }

        if (aboutTab) return true; // the About page has no config rows under the links

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
        if (feedbackOpen) {
            if (feedbackInput != null) feedbackInput.mouseDragged(e, dx, dy);
            return true;
        }
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
        if (feedbackOpen) {
            if (feedbackInput != null) feedbackInput.charTyped(e);
            return true;
        }
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
        if (feedbackOpen) {
            if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
                feedbackOpen = false;
                setFocused(null);
                if (feedbackInput != null) feedbackInput.setFocused(false);
                return true;
            }
            if (feedbackInput != null) feedbackInput.keyPressed(e);
            return true;
        }
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

    private int tabCount() {
        return Group.values().length + 1;
    }

    private boolean isAboutTab(int index) {
        return index == Group.values().length;
    }

    private Component tabLabel(int index) {
        return isAboutTab(index)
                ? Component.translatable("voxymap.configuration.about")
                : Component.translatable(Group.values()[index].labelKey);
    }

    private int tabWidth(int index) {
        return font.width(tabLabel(index)) + 20;
    }

    private int tabTotalWidth() {
        int total = 0;
        for (int i = 0; i < tabCount(); i++) {
            total += tabWidth(i);
            if (i > 0) total += TAB_GAP;
        }
        return total;
    }

    private int tabMaxScroll() {
        return Math.max(0, tabTotalWidth() - (width - PAD * 2));
    }

    private int tabX(int index) {
        int x = PAD - tabScroll;
        for (int i = 0; i < index; i++) x += tabWidth(i) + TAB_GAP;
        return x;
    }

    private boolean tabActive(int index) {
        return isAboutTab(index) ? aboutTab : (!aboutTab && Group.values()[index] == group);
    }

    private void selectTab(int index) {
        if (isAboutTab(index)) {
            if (!aboutTab) {
                tabT = 0f;
                tabSwitchNs = -1L;
            }
            aboutTab = true;
            aboutScroll = 0;
            for (int i = 0; i < aboutLinkY.length; i++) aboutLinkY[i] = 0;
        } else {
            Group next = Group.values()[index];
            if (aboutTab || group != next) {
                tabT = 0f;
                tabSwitchNs = -1L;
            }
            aboutTab = false;
            group = next;
            scroll = 0;
        }
        editing = null;
        ensureTabVisible(index);
    }

    /** Scrolls the tab strip just enough to keep the selected tab fully visible. */
    private void ensureTabVisible(int index) {
        int max = tabMaxScroll();
        int left = tabX(index);
        int right = left + tabWidth(index);
        if (left < PAD) tabScroll -= PAD - left;
        else if (right > width - PAD) tabScroll += right - (width - PAD);
        tabScroll = clamp(tabScroll, 0, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void openLink(String url) {
        try {
            Util.getPlatform().openUri(URI.create(url));
        } catch (Throwable ignored) {
            // opening a browser is best-effort; never let it break the screen
        }
    }

    private int aboutMaxScroll() {
        return Math.max(0, aboutContentHeight - (listBottom() - listTop()));
    }

    /** Staggered fade-in for the About sections, same 0.16 s ramp as the config rows. */
    private static float stagger(float fade, float tabAge, float delay) {
        return fade * Math.min(1f, Math.max(0f, (tabAge - delay) / 0.16f));
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

        drawTabs(g, mouseX, mouseY, fade, dy);

        if (aboutTab) {
            drawAbout(g, mouseX, mouseY, fade, dy, nowNs);
        } else {
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

                drawRowText(g, it, y, rowT, nowNs);

                drawControl(g, it, y, rowT);
                y += ROW_H;
            }

            drawScrollbar(g, fade);
        }

        drawBottomBar(g, mouseX, mouseY, fade);

        if (feedbackOpen) drawFeedbackPanel(g, mouseX, mouseY, partialTick);

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

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY, float fade, int dy) {
        tabScroll = clamp(tabScroll, 0, tabMaxScroll());
        int top = 26 + dy;
        g.enableScissor(PAD, top, width - PAD, top + TAB_H);
        for (int i = 0; i < tabCount(); i++) {
            int tx = tabX(i);
            int tw = tabWidth(i);
            boolean active = tabActive(i);
            boolean tabHover = mouseX >= tx && mouseX <= tx + tw && mouseY >= top && mouseY <= top + TAB_H;
            float th = hover("tab:" + i, tabHover, lastDt);
            g.fill(tx, top, tx + tw, top + TAB_H, withAlpha(active ? COL_SELECTED : COL_PANEL_OFF, fade));
            if (th > 0.01f) {
                g.fill(tx, top, tx + tw, top + TAB_H, withAlpha(COL_ROW_HOVER, fade * th * 0.6f));
            }
            if (active) g.fill(tx, top, tx + tw, top + 1, withAlpha(COL_ACCENT, fade));
            g.text(font, tabLabel(i), tx + 10, top + (TAB_H - font.lineHeight) / 2 + 1,
                    withAlpha(active ? COL_ACCENT : lerpColor(COL_TEXT, COL_ACCENT, th * 0.6f), fade));
        }
        g.disableScissor();

        if (tabScroll > 0) {
            g.fill(PAD, top, PAD + 9, top + TAB_H, withAlpha(COL_PANEL, fade));
            drawTabArrow(g, PAD + 3, top + 6, false, fade);
        }
        if (tabScroll < tabMaxScroll()) {
            g.fill(width - PAD - 9, top, width - PAD, top + TAB_H, withAlpha(COL_PANEL, fade));
            drawTabArrow(g, width - PAD - 7, top + 6, true, fade);
        }
    }

    /** Small pixel chevron used by the scrollable tab strip. */
    private void drawTabArrow(GuiGraphicsExtractor g, int x, int y, boolean right, float alpha) {
        int col = withAlpha(COL_ACCENT, alpha);
        for (int i = 0; i < 3; i++) {
            int xx = right ? x + i : x + 2 - i;
            int h = 7 - i * 2;
            g.fill(xx, y + (7 - h) / 2, xx + 1, y + (7 - h) / 2 + h, col);
        }
    }

    private int aboutContentWidth() {
        return Math.max(60, width - PAD * 2 - 20);
    }

    private record AboutLayout(int textW, int feedbackY, int linksLabelY, int linksY, int cols, int cellW,
                               int totalH) {
    }

    private AboutLayout aboutLayout() {
        int pad = 10;
        int textW = Math.max(80, aboutContentWidth() - (ABOUT_ICON_SIZE + 12));
        int taglineLines = Math.max(1, font.split(Component.translatable("voxymap.about.tagline"), textW).size());
        int descLines = Math.max(1, font.split(Component.translatable("voxymap.about.description"), textW).size());
        int textBottom = 36 + taglineLines * 10 + 2 + descLines * 10;
        int header = Math.max(ABOUT_ICON_SIZE, textBottom - 10);
        int feedbackY = pad + header + 10;
        int linksLabelY = feedbackY + 26 + 8;
        int linksY = linksLabelY + 10;
        int cols = aboutContentWidth() >= 320 ? 2 : 1;
        int rows = (ABOUT_LINK_URLS.length + cols - 1) / cols;
        int cellW = (aboutContentWidth() - (cols - 1) * ABOUT_LINK_GAP) / cols;
        int totalH = linksY + rows * ABOUT_LINK_H + 4;
        return new AboutLayout(textW, feedbackY, linksLabelY, linksY, cols, cellW, totalH);
    }

    /** The About tab: mod icon, version/platform line, author, and clickable project links. */
    private void drawAbout(GuiGraphicsExtractor g, int mouseX, int mouseY, float fade, int dy, long nowNs) {
        int x0 = PAD;
        int x1 = width - PAD;
        int top = listTop() + dy;
        int bottom = listBottom();
        if (bottom <= top) return;

        if (tabSwitchNs < 0L) tabSwitchNs = nowNs;
        float tabAge = (nowNs - tabSwitchNs) / 1_000_000_000f;
        float tHeader = stagger(fade, tabAge, 0.00f);
        float tAuthor = stagger(fade, tabAge, 0.05f);
        float tLinks = stagger(fade, tabAge, 0.09f);
        if (tHeader <= 0f) return;

        g.fill(x0, top, x1, bottom, withAlpha(0x30101014, fade));

        AboutLayout layout = aboutLayout();
        aboutContentHeight = layout.totalH();
        aboutScroll = clamp(aboutScroll, 0, aboutMaxScroll());
        int baseY = top - aboutScroll;
        int x = x0 + 10;
        int right = x1 - 10;

        g.enableScissor(x0 + 1, top, x1 - 1, bottom);

        if (tHeader > 0.04f) {
            g.blit(RenderPipelines.GUI_TEXTURED, TEX_ABOUT_ICON, x, baseY + 10, 0f, 0f,
                    ABOUT_ICON_SIZE, ABOUT_ICON_SIZE, ABOUT_ICON_SIZE, ABOUT_ICON_SIZE,
                    ABOUT_ICON_SIZE, ABOUT_ICON_SIZE);
        }

        int textX = x + ABOUT_ICON_SIZE + 12;
        g.text(font, Component.literal("Voxy Map"), textX, baseY + 10, withAlpha(COL_TEXT, tHeader));
        g.text(font, Component.literal("v" + AboutInfo.modVersion() + "  ·  " + AboutInfo.platform()
                        + "  ·  MC " + AboutInfo.mcVersion()),
                textX, baseY + 22, withAlpha(COL_ACCENT, tHeader));

        int ty = baseY + 36;
        for (FormattedCharSequence line : font.split(Component.translatable("voxymap.about.tagline"), layout.textW())) {
            g.text(font, line, textX, ty, withAlpha(COL_TEXT, tHeader));
            ty += font.lineHeight + 1;
        }
        ty += 2;
        for (FormattedCharSequence line : font.split(Component.translatable("voxymap.about.description"), layout.textW())) {
            g.text(font, line, textX, ty, withAlpha(COL_DIM, tHeader));
            ty += font.lineHeight + 1;
        }

        // author badge in the top-right corner, so the links stay visible on short screens
        int authorX = right - ABOUT_AVATAR_SIZE;
        int authorY = baseY + 10;
        if (tAuthor > 0.04f) {
            g.blit(RenderPipelines.GUI_TEXTURED, TEX_ABOUT_AUTHOR, authorX, authorY, 0f, 0f,
                    ABOUT_AVATAR_SIZE, ABOUT_AVATAR_SIZE, ABOUT_AVATAR_SIZE, ABOUT_AVATAR_SIZE,
                    ABOUT_AVATAR_SIZE, ABOUT_AVATAR_SIZE);
        }
        Component authorName = Component.literal("Tsutsutsuu");
        int authorNameX = authorX - 8 - font.width(authorName);
        g.text(font, authorName, authorNameX, authorY + 1, withAlpha(COL_TEXT, tAuthor));
        g.text(font, Component.translatable("voxymap.about.author"),
                authorNameX, authorY + 11, withAlpha(COL_DIM, tAuthor));

        int fbY = baseY + layout.feedbackY();
        feedbackButtonY = fbY;
        boolean fbHover = mouseX >= x - 4 && mouseX <= right + 4 && mouseY >= fbY && mouseY < fbY + 24;
        float fh = hover("about:feedback", fbHover, lastDt);
        g.fill(x - 4, fbY, right + 4, fbY + 24, withAlpha(COL_SELECTED, tLinks));
        g.fill(x - 4, fbY, right + 4, fbY + 1, withAlpha(COL_ACCENT, tLinks));
        if (fh > 0.01f) {
            g.fill(x - 4, fbY, right + 4, fbY + 24, withAlpha(COL_ROW_HOVER, tLinks * fh * 0.8f));
        }
        Component feedbackLabel = Component.translatable("voxymap.about.feedback");
        if (tLinks > 0.04f) {
            g.blit(RenderPipelines.GUI_TEXTURED, TEX_ABOUT_BUG, x + 4, fbY + 4, 0f, 0f,
                    16, 16, 16, 16, 16, 16);
        }
        int fbTextY = fbY + (24 - font.lineHeight) / 2 + 1;
        g.text(font, feedbackLabel, x + 24, fbTextY, withAlpha(COL_ACCENT, tLinks));
        Component recommended = Component.translatable("voxymap.about.feedback.recommended");
        g.text(font, recommended, x + 24 + font.width(feedbackLabel) + 8, fbTextY,
                withAlpha(0xFFFFFFFF, tLinks));

        int dividerY = baseY + layout.linksLabelY() - 8;
        g.fill(x, dividerY, right, dividerY + 1, withAlpha(COL_BORDER, tLinks));
        g.text(font, Component.translatable("voxymap.about.links"), x, baseY + layout.linksLabelY(),
                withAlpha(COL_DIM, tLinks));
        Component license = Component.translatable("voxymap.about.license").copy()
                .append(Component.literal("  ·  "))
                .append(Component.translatable("voxymap.about.client"));
        g.text(font, license, right - font.width(license), baseY + layout.linksLabelY(),
                withAlpha(COL_DIM, tLinks));

        int cols = layout.cols();
        int cellW = layout.cellW();
        for (int i = 0; i < ABOUT_LINK_URLS.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int cellX = x + col * (cellW + ABOUT_LINK_GAP);
            int cellY = baseY + layout.linksY() + row * ABOUT_LINK_H;
            aboutLinkX[i] = cellX;
            aboutLinkW[i] = cellW;
            aboutLinkY[i] = cellY;
            float cellT = stagger(fade, tabAge, 0.11f + i * 0.03f);
            boolean hovered = mouseX >= cellX - 4 && mouseX <= cellX + cellW
                    && mouseY >= cellY && mouseY < cellY + ABOUT_LINK_H - 2;
            float lh = hover("about:" + i, hovered, lastDt);
            if (lh > 0.01f) {
                g.fill(cellX - 4, cellY, cellX + cellW, cellY + ABOUT_LINK_H - 2,
                        withAlpha(COL_ROW_HOVER, cellT * lh * 0.8f));
                g.fill(cellX - 4, cellY, cellX - 3, cellY + ABOUT_LINK_H - 2,
                        withAlpha(COL_ACCENT, cellT * lh));
            }
            if (cellT > 0.12f) {
                g.blit(RenderPipelines.GUI_TEXTURED, ABOUT_LINK_ICONS[i], cellX, cellY + 1, 0f, 0f,
                        32, 32, 32, 32, 32, 32);
            }
            g.text(font, Component.translatable(ABOUT_LINK_KEYS[i]), cellX + 40, cellY + 5,
                    withAlpha(hovered ? 0xFFFFFFFF : COL_TEXT, cellT));
            String url = ABOUT_LINK_URLS[i].replaceFirst("^https?://", "");
            g.text(font, Component.literal(ellipsize(url, Math.max(24, cellW - 44))),
                    cellX + 40, cellY + 17, withAlpha(COL_DIM, cellT));
        }
        g.disableScissor();

        if (aboutMaxScroll() > 0) drawAboutScrollbar(g, fade, top, bottom);
    }

    private void drawAboutScrollbar(GuiGraphicsExtractor g, float fade, int top, int bottom) {
        int trackX = width - PAD;
        int trackH = bottom - top;
        if (trackH <= 0) return;
        g.fill(trackX, top, trackX + 3, top + trackH, withAlpha(0x30FFFFFF, fade));
        int barH = Math.max(18, trackH * trackH / Math.max(1, aboutContentHeight));
        int barY = top + (trackH - barH) * aboutScroll / Math.max(1, aboutMaxScroll());
        g.fill(trackX, barY, trackX + 3, barY + barH, withAlpha(COL_ACCENT, fade));
    }

    private record FeedbackBox(int x, int y, int w, int h, int textX, int textY, int textW, int textH,
                               int checkX, int checkY, int sendX, int closeX, int btnY, int btnW, int btnH) {
    }

    private FeedbackBox feedbackBox() {
        int w = Math.min(width - 32, 460);
        int h = Math.min(height - 24, 240);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        int textX = x + 12;
        int textY = y + 50;
        int textW = w - 24;
        int btnW = 64;
        int btnH = 20;
        int closeX = x + w - 12 - btnW;
        int sendX = closeX - 8 - btnW;
        int btnY = y + h - 10 - btnH;
        int checkX = x + 12;
        int checkY = btnY - 26;
        int textH = checkY - 8 - textY;
        return new FeedbackBox(x, y, w, h, textX, textY, textW, textH,
                checkX, checkY, sendX, closeX, btnY, btnW, btnH);
    }

    private int feedbackLogX(FeedbackBox box) {
        return box.checkX() + 15 + font.width(Component.translatable("voxymap.feedback.attachMods")) + 14;
    }

    private int feedbackPasteX(FeedbackBox box) {
        return box.x() + box.w() - 12 - 52;
    }

    private void drawCheckbox(GuiGraphicsExtractor g, int x, int y, boolean on, Component label) {
        g.fill(x, y, x + 10, y + 10, on ? COL_SELECTED : 0xE0101216);
        g.fill(x, y, x + 10, y + 1, COL_BORDER);
        if (on) g.fill(x + 2, y + 3, x + 8, y + 8, COL_ACCENT);
        g.text(font, label, x + 15, y + 1, COL_TEXT);
    }

    /** Same shape/size/colour for every button inside the feedback panel. */
    private void drawSmallButton(GuiGraphicsExtractor g, int x, int y, int w, int h, Component label,
                                 int accent, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        float bh = hover("small:" + label.getString(), hovered, lastDt);
        g.fill(x, y, x + w, y + h, COL_PANEL_OFF);
        if (bh > 0.01f) g.fill(x, y, x + w, y + h, withAlpha(COL_ROW_HOVER, bh));
        g.fill(x, y, x + w, y + 1, lerpColor(accent, 0xFFFFFFFF, bh * 0.3f));
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1,
                lerpColor(COL_TEXT, 0xFFFFFFFF, bh));
    }

    /** In-game feedback editor: free text, report tips, optional mod list / log, one send button. */
    private void drawFeedbackPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        FeedbackBox box = feedbackBox();
        g.fill(0, 0, width, height, 0x99000000);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), 0xF014171C);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + 1, COL_ACCENT);

        Component title = Component.translatable("voxymap.feedback.title");
        g.text(font, title, box.x() + (box.w() - font.width(title)) / 2, box.y() + 10, COL_ACCENT);
        g.text(font, Component.literal(ellipsize(Component.translatable("voxymap.feedback.hint").getString(),
                        box.textW())), box.x() + 12, box.y() + 24, COL_TEXT);
        g.text(font, Component.literal(ellipsize(Component.translatable("voxymap.feedback.privacy").getString(),
                        box.textW())), box.x() + 12, box.y() + 36, COL_DIM);

        g.fill(box.textX(), box.textY(), box.textX() + box.textW(), box.textY() + box.textH(), 0xE0101216);
        g.fill(box.textX(), box.textY(), box.textX() + box.textW(), box.textY() + 1, COL_BORDER);

        if (feedbackInput != null) {
            // Window focus loss can clear the screen focus; reclaim it while the editor is open.
            if (!feedbackInput.isFocused() || getFocused() != feedbackInput) {
                feedbackInput.setFocused(true);
                setFocused(feedbackInput);
            }
            feedbackInput.extractRenderState(g, mouseX, mouseY, partialTick);
        }

        drawCheckbox(g, box.checkX(), box.checkY(), feedbackAttachMods,
                Component.translatable("voxymap.feedback.attachMods"));
        drawCheckbox(g, feedbackLogX(box), box.checkY(), feedbackAttachLog,
                Component.translatable("voxymap.feedback.attachLog"));

        int pasteX = feedbackPasteX(box);
        int pasteY = box.checkY() - 3;
        drawSmallButton(g, pasteX, pasteY, 52, 16, Component.translatable("voxymap.feedback.paste"),
                COL_BORDER, mouseX, mouseY);

        String status = feedbackStatus;
        if (!status.isEmpty()) {
            int statusRight = box.sendX() - 8;
            g.text(font, Component.literal(ellipsize(status, Math.max(20, statusRight - box.x() - 12))),
                    box.x() + 12, box.btnY() + 6, COL_DIM);
        }

        Component sendLabel = Component.translatable(feedbackSending
                ? "voxymap.feedback.sending" : "voxymap.feedback.send");
        drawSmallButton(g, box.sendX(), box.btnY(), box.btnW(), box.btnH(), sendLabel,
                COL_ACCENT, mouseX, mouseY);
        drawSmallButton(g, box.closeX(), box.btnY(), box.btnW(), box.btnH(),
                Component.translatable("voxymap.feedback.close"), COL_ACCENT, mouseX, mouseY);
    }

    private void handleFeedbackClick(int mx, int my) {
        FeedbackBox box = feedbackBox();
        if (mx >= box.checkX() - 2 && mx <= box.checkX() + 12
                && my >= box.checkY() - 2 && my <= box.checkY() + 12) {
            feedbackAttachMods = !feedbackAttachMods;
            return;
        }
        int logX = feedbackLogX(box);
        if (mx >= logX - 2 && mx <= logX + 12 && my >= box.checkY() - 2 && my <= box.checkY() + 12) {
            feedbackAttachLog = !feedbackAttachLog;
            return;
        }
        int pasteX = feedbackPasteX(box);
        int pasteY = box.checkY() - 3;
        if (mx >= pasteX && mx <= pasteX + 52 && my >= pasteY && my <= pasteY + 16) {
            pasteFeedback();
            return;
        }
        if (mx >= box.sendX() && mx <= box.sendX() + box.btnW()
                && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
            sendFeedback();
            return;
        }
        if (mx >= box.closeX() && mx <= box.closeX() + box.btnW()
                && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
            feedbackOpen = false;
        }
    }

    private void sendFeedback() {
        if (feedbackSending) return;
        String rawText = feedbackInput == null ? "" : feedbackInput.getValue().trim();
        String text = rawText.length() > FEEDBACK_MAX_CHARS
                ? rawText.substring(0, FEEDBACK_MAX_CHARS) : rawText;
        if (text.isEmpty()) {
            feedbackStatus = Component.translatable("voxymap.feedback.empty").getString();
            return;
        }
        feedbackSending = true;
        feedbackStatus = Component.translatable("voxymap.feedback.sending").getString();
        String version = "v" + AboutInfo.modVersion() + " · " + AboutInfo.platform()
                + " · MC " + AboutInfo.mcVersion();
        FeedbackSender.send(text, feedbackAttachLog, feedbackAttachMods, version, result -> {
            feedbackSending = false;
            if (result.ok()) {
                feedbackStatus = Component.translatable("voxymap.feedback.sent").getString();
                if (feedbackInput != null) feedbackInput.setValue("");
            } else if ("rate".equals(result.detail())) {
                feedbackStatus = Component.translatable("voxymap.feedback.rate").getString();
            } else {
                feedbackStatus = Component.translatable("voxymap.feedback.failed").getString();
                copyToClipboard(FeedbackSender.buildPayload(text, feedbackAttachLog, feedbackAttachMods, version));
            }
        });
    }

    private void copyToClipboard(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        } catch (Throwable ignored) {
        }
    }

    private void appendFeedback(String text) {
        int room = FEEDBACK_MAX_CHARS - feedbackText.length();
        if (room <= 0 || text == null || text.isEmpty()) return;
        feedbackText.append(text, 0, Math.min(room, text.length()));
    }

    private void deleteLastFeedbackChar() {
        int len = feedbackText.length();
        if (len == 0) return;
        int start = len - 1;
        if (Character.isLowSurrogate(feedbackText.charAt(start)) && start > 0
                && Character.isHighSurrogate(feedbackText.charAt(start - 1))) {
            start--;
        }
        feedbackText.delete(start, len);
    }

    private void pasteFeedback() {
        try {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip == null || clip.isEmpty() || feedbackInput == null) return;
            StringBuilder filtered = new StringBuilder(clip.length());
            for (int i = 0; i < clip.length(); i++) {
                char c = clip.charAt(i);
                if (c == '\n' || c == '\t' || c >= 32) filtered.append(c);
            }
            feedbackInput.setValue(feedbackInput.getValue() + filtered);
        } catch (Throwable ignored) {
        }
    }

    /** Character wrap (CJK-friendly) with explicit newlines preserved. */
    private List<String> wrapFeedback(int maxWidth) {
        List<String> lines = new ArrayList<>();
        String text = feedbackText.toString();
        if (text.isEmpty()) {
            lines.add("");
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int used = 0;
            for (int i = 0; i < paragraph.length(); ) {
                int cp = paragraph.codePointAt(i);
                int n = Character.charCount(cp);
                String ch = paragraph.substring(i, i + n);
                int cw = font.width(ch);
                if (used + cw > maxWidth && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                    used = 0;
                }
                line.append(ch);
                used += cw;
                i += n;
            }
            lines.add(line.toString());
        }
        return lines;
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

    /**
     * Draws a row's title (clipped with an ellipsis before the control column) and its help text.
     * Help text that does not fit scrolls continuously to the left, clipped to the text column, so
     * long translations can never run under the slider/toggle or off the panel.
     */
    private void drawRowText(GuiGraphicsExtractor g, Item it, int y, float alpha, long nowNs) {
        int textX = PAD + 6;
        int textRight = Math.max(textX + 24, controlX() - 4);
        int maxW = textRight - textX;

        String name = Component.translatable("voxymap.config." + it.name()).getString();
        if (font.width(name) > maxW) name = ellipsize(name, maxW);
        g.text(font, Component.literal(name), textX, y + 5, withAlpha(COL_TEXT, alpha));

        String desc = Component.translatable(it.descKey()).getString();
        if (desc.isEmpty() || maxW < 16) return;
        int descW = font.width(desc);
        if (descW <= maxW) {
            g.text(font, Component.literal(desc), textX, y + 17, withAlpha(COL_DIM, alpha));
            return;
        }
        if (descClockNs == 0L) descClockNs = nowNs;
        int cycle = descW + DESC_GAP_PX;
        float seconds = (nowNs - descClockNs) / 1_000_000_000f;
        int shift = (int) (seconds * DESC_SPEED_PX_S % cycle);
        g.enableScissor(textX, y + 16, textRight, y + 16 + font.lineHeight + 1);
        g.text(font, Component.literal(desc), textX - shift, y + 17, withAlpha(COL_DIM, alpha));
        g.text(font, Component.literal(desc), textX - shift + cycle, y + 17, withAlpha(COL_DIM, alpha));
        g.disableScissor();
    }

    /** Trims a single-line label to {@code maxW} pixels, ending with an ellipsis. */
    private String ellipsize(String text, int maxW) {
        String dots = "…";
        int dotsW = font.width(dots);
        if (maxW <= dotsW) return dots;
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) + dotsW > maxW) end--;
        return text.substring(0, end) + dots;
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