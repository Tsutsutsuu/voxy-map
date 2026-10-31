package com.voxymap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fullscreen map: N to open, Esc/N to close, scroll to zoom, drag to pan,
 * bottom-left style switcher, bottom-centre cursor coordinates, right-click
 * context menu (copy coords / OP teleport), bottom-right manual refresh.
 */
public class MapScreen extends Screen {
    private static final double ZOOM_MIN = 0.04;
    private static final double ZOOM_MAX = 8.0;
    /** Top-right rows: map scale slider above the palette dropdown. */
    private static final int SCALE_ROW_Y = 8;
    private static final int STYLE_ROW_Y = 32;
    private static final int SCALE_SLIDER_W = 56;
    private static final int SCALE_KNOB_W = 6;

    private double centerX;
    private double centerZ;
    private double zoom = 0.5;
    private boolean dragging;
    private int menuX, menuY, menuWorldX, menuWorldZ, menuWorldY;
    private boolean menuOpen;
    private boolean linearFilter = true;
    private boolean styleMenuOpen;
    private float configPressT;
    private float openT = 1f;
    private boolean closing;
    private float closeT;
    private long lastAnimNs;
    private float frameDt = 0.016f;
    private boolean lodWarning;
    private boolean debugPanel;
    /** Map-scale slider: dragging only previews, the rebuild starts on mouse release. */
    private boolean sliderDragging;
    private int sliderValue = 2;
    /**
     * Label text + pixel width per biome id. Measuring text through the font is far more expensive
     * than the culling maths, so it is done once per biome and rebuilt when the language or the
     * biome table of the current world changes.
     */
    private static final class LabelInfo {
        final String name;
        final Component text;
        final int width;

        LabelInfo(String name, Component text, int width) {
            this.name = name;
            this.text = text;
            this.width = width;
        }
    }

    private final Map<Integer, LabelInfo> labelCache = new HashMap<>();
    private String labelLanguage;
    /** Animated hover progress per control (0..1), and dropdown open progress. */
    private final Map<String, Float> hoverStates = new HashMap<>();
    private float styleMenuT;
    private float contextMenuT;
    /** Screen-space UI panels; biome labels overlapping these are culled (x0,y0,x1,y1). */
    private final List<int[]> labelBlockers = new ArrayList<>();
    private static final Identifier ARROW_CLEAN = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/arrow_clean.png");
    private static final Identifier ARROW_CLASSIC = Identifier.fromNamespaceAndPath("voxymap", "textures/gui/arrow_classic.png");

    private static Component styleName(String st) {
        return Component.translatable("voxymap.style." + st);
    }

    private static Boolean classicArrowAvailable;

    /** Falls back to the clean marker when the classic texture is missing instead of a purple cube. */
    private static Identifier arrowTex() {
        if ("classic".equals(VoxyMapConfig.ARROW_STYLE.get())) {
            if (classicArrowAvailable == null) {
                classicArrowAvailable = Minecraft.getInstance().getResourceManager()
                        .getResource(ARROW_CLASSIC).isPresent();
                if (!classicArrowAvailable) {
                    System.err.println("[voxymap] arrow_classic.png is missing from the resources, "
                            + "using the clean marker instead");
                }
            }
            if (classicArrowAvailable) {
                return ARROW_CLASSIC;
            }
        }
        return ARROW_CLEAN;
    }

    // ---- map scale slider geometry (draw and hit test share these) ----

    private int scaleLabelW() {
        return font.width(Component.translatable("voxymap.ui.mapScale"));
    }

    private String scaleValueText() {
        return "1:" + sliderValue;
    }

    private int scalePanelLeft() {
        int total = 6 + scaleLabelW() + 8 + SCALE_SLIDER_W + 8 + font.width("1:8") + 6;
        return width - 8 - total;
    }

    private int scaleSliderX() {
        return scalePanelLeft() + 6 + scaleLabelW() + 8;
    }

    private int scaleKnobX() {
        return scaleSliderX() + (int) Math.round((sliderValue - 1) / 7.0 * (SCALE_SLIDER_W - SCALE_KNOB_W));
    }

    private int scaleSliderValueAt(double mx) {
        double t = (mx - scaleSliderX()) / (double) (SCALE_SLIDER_W - SCALE_KNOB_W);
        return Math.max(1, Math.min(8, (int) Math.round(t * 7.0) + 1));
    }

    // ---- theme: one accent colour, three interaction states ----

    /** normal buttons/panels: 75% */
    private static final int COL_PANEL = 0xC014171C;
    /** disabled: same 75%, darker colour */
    private static final int COL_PANEL_OFF = 0xC0101216;
    /** modal dialog: 90% */
    private static final int COL_PANEL_DIALOG = 0xE614171C;
    private static final int COL_BORDER = 0xFF323A44;
    private static final int COL_BORDER_OFF = 0xFF272C33;
    private static final int COL_ACCENT = 0xFF4F9BC4;
    /** Selected / enabled tint: the same deep translucent blue as the dropdown selection. */
    private static final int COL_SELECTED = 0xC02A3A50;
    /** Row hover behind the cursor inside menus. */
    private static final int COL_ROW_HOVER = 0x504F9BC4;
    private static final int COL_TEXT = 0xFFE8EAED;
    private static final int COL_TEXT_DIM = 0xFF8C949E;
    private static final int COL_TEXT_OFF = 0xFF5A6068;
    private static final int COL_WARN = 0xFFE0A040;

    private static int lerpColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Time-based easing: independent of the frame rate (180 fps vs 60 fps behaves the same). */
    private float approach(float cur, float target) {
        return cur + (target - cur) * Math.min(1f, frameDt * 14f);
    }

    private float hoverAmount(String key, boolean hovered) {
        float cur = hoverStates.getOrDefault(key, 0f);
        float target = hovered ? 1f : 0f;
        if (Math.abs(cur - target) < 0.01f) cur = target;
        else cur += (target - cur) * Math.min(1f, frameDt * 16f);
        hoverStates.put(key, cur);
        return cur;
    }

    /** Flat button, height 20, text vertically centred, tinted by the hover progress. */
    private void drawButton(GuiGraphicsExtractor g, int x, int y, int w, Component label, float hover, boolean enabled) {
        g.fill(x, y, x + w, y + 20, enabled ? COL_PANEL : COL_PANEL_OFF);
        // hover is a translucent overlay, exactly like the dropdown rows
        if (enabled && hover > 0.01f) {
            g.fill(x, y, x + w, y + 20, withAlpha(COL_ROW_HOVER, hover));
        }
        g.fill(x, y, x + w, y + 1, enabled ? lerpColor(COL_BORDER, COL_ACCENT, hover * 0.5f) : COL_BORDER_OFF);
        int color = enabled ? lerpColor(COL_TEXT, 0xFFFFFFFF, hover) : COL_TEXT_OFF;
        g.text(font, label, x + 6, y + 6, color);
    }

    /** Toggle button: deep blue tint while enabled (same as a selected dropdown row). */
    private void drawToggle(GuiGraphicsExtractor g, String key, int x, int y, int w, Component label, boolean on, boolean hovered) {
        float hov = hoverAmount(key, hovered);
        g.fill(x, y, x + w, y + 20, on ? COL_SELECTED : COL_PANEL);
        if (hov > 0.01f) {
            g.fill(x, y, x + w, y + 20, withAlpha(COL_ROW_HOVER, hov));
        }
        g.fill(x, y, x + w, y + 1, on ? lerpColor(COL_ACCENT, 0xFFFFFFFF, hov * 0.4f)
                : lerpColor(COL_BORDER, COL_ACCENT, hov * 0.5f));
        g.text(font, label, x + 6, y + 6,
                on ? 0xFFFFFFFF : lerpColor(COL_TEXT, 0xFFFFFFFF, hov));
    }

    public MapScreen() {
        super(Component.translatable("voxymap.ui.title"));
    }

    @Override
    protected void init() {
        openT = 0f;
        closing = false;
        closeT = 0f;
        // restore the last zoom for this world (or the configured default)
        zoom = VoxyMapConfig.REMEMBER_ZOOM.get() ? MapData.INSTANCE.getZoom() : VoxyMapConfig.DEFAULT_ZOOM.get();
        if (zoom <= 0) zoom = VoxyMapConfig.DEFAULT_ZOOM.get();
        var p0 = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        sliderValue = data.getMapScale();
        if (!data.isOverworld()) {
            // the map only knows the overworld LOD storage: nothing to load here
            return;
        }
        // A world created minutes ago is still being filled by SeedGen: show the dialog and wait
        // instead of building a nearly empty map. Either the initial delay expires (MapData.tick)
        // or the dialog's "refresh now" button starts the build.
        boolean waitingForSeedGen = data.isInitialRefreshPending() && data.isNewWorldNow();
        lodWarning = waitingForSeedGen;
        if (p0 != null) {
            centerX = p0.getX();
            centerZ = p0.getZ();
        }
        if (waitingForSeedGen) {
            return;
        }
        if (data.needsWorldReload()) {
            // the grid kept from the previous world must not be shown here
            data.dropSnapshotForWorldChange();
            data.updateAsync();
        } else {
            // rejoining the same world: the cached colour grid is re-uploaded instead of rebuilt
            data.ensureMainTextures();
            if (data.getMapWidth() <= 0 && !data.isLoading()) {
                data.updateAsync();
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        // The dialog is only meaningful while the initial delay of a fresh world is still running:
        // once it expires (or the player refreshed from the dialog) the map starts building in the
        // background, so drop the dialog instead of letting it cover the map.
        MapData data = MapData.INSTANCE;
        if (lodWarning && !data.isInitialRefreshPending()) {
            lodWarning = false;
        }
        // keep the slider in sync unless the player is dragging it or a rebuild is running
        if (!sliderDragging && !data.isLoading()) {
            sliderValue = data.getMapScale();
        }
    }

    private double centerPx() {
        MapData d = MapData.INSTANCE;
        return (centerX - d.getMinWX()) / d.getPixelBlocks();
    }

    private double centerPz() {
        MapData d = MapData.INSTANCE;
        return (centerZ - d.getMinWZ()) / d.getPixelBlocks();
    }

    private double screenToWorldX(double sx) {
        MapData d = MapData.INSTANCE;
        double px = centerPx() + (sx - width / 2.0) / zoom;
        return d.getMinWX() + px * d.getPixelBlocks();
    }

    private double screenToWorldZ(double sy) {
        MapData d = MapData.INSTANCE;
        double pz = centerPz() + (sy - height / 2.0) / zoom;
        return d.getMinWZ() + pz * d.getPixelBlocks();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long nowNs = System.nanoTime();
        frameDt = lastAnimNs == 0 ? 0.016f : (nowNs - lastAnimNs) / 1_000_000_000f;
        lastAnimNs = nowNs;
        g.fill(0, 0, width, height, 0xFF0A0A0A);
        MapData data = MapData.INSTANCE;
        if (!data.isOverworld()) {
            // nether / end: no map is built or drawn, only the notice
            drawStatusLine(g, Component.translatable("voxymap.ui.overworldOnly"), 24, COL_WARN);
            return;
        }
        // open/close animation (time based): scale + fade-in of the whole map screen
        if (!closing) {
            openT = Math.min(1f, openT + frameDt / 0.18f);
        } else {
            closeT = Math.min(1f, closeT + frameDt / 0.15f);
            if (closeT >= 1f) {
                finishClose();
                return;
            }
        }
        float animScale = closing
                ? 1f - 0.05f * (1f - (1f - closeT) * (1f - closeT))
                : 0.95f + 0.05f * (1f - (1f - openT) * (1f - openT));
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, height / 2f);
        g.pose().scale(animScale, animScale);
        g.pose().translate(-width / 2f, -height / 2f);
        double uiScale = Minecraft.getInstance().getWindow().getGuiScale();
        // dropdown open/close animation
        frameDt = Math.min(0.1f, Math.max(0.001f, frameDt));
        styleMenuT = approach(styleMenuT, styleMenuOpen ? 1f : 0f);
        configPressT = Math.max(0f, configPressT - frameDt * 4f);
        contextMenuT = approach(contextMenuT, menuOpen ? 1f : 0f);

        // draw order is the stacking order, bottom to top:
        //   1 map, 2 map overlays, 3 biome labels, 4 readouts/status text, 5 buttons, 6 dialog
        drawMap(g, data, uiScale);
        drawMapOverlays(g, data);
        // GuiGraphicsExtractor batches text independently of fills, and endBatch() draws the batches in
        // RenderType order rather than call order, so text can survive a later fill. While the
        // modal dialog is open, skip every textual/interactive layer above the map: that is the
        // only way to guarantee the dialog covers them.
        if (!lodWarning && debugPanel) {
            // the debug panel is a modal layer of its own: skip the text/button layers for the same
            // batch-ordering reason as the dialog
            drawDebugPanel(g, data, mouseX, mouseY);
        } else if (!lodWarning) {
            // labels only when the map is at or above point-to-point, otherwise they cost more than they show
            if (data.isBiomeOverlay() && zoom * uiScale >= 1.0) {
                collectLabelBlockers(mouseX, mouseY);
                drawBiomeLabels(g);
            }
            drawStatusText(g, data);
            drawReadouts(g, mouseX, mouseY);
            drawButtons(g, data, mouseX, mouseY);
            if (contextMenuT > 0.02f) {
                drawContextMenu(g, mouseX, mouseY);
            }
        }
        // the modal dialog always wins, whatever the map state is
        if (lodWarning) {
            drawLodWarning(g, mouseX, mouseY);
        }
        g.pose().popMatrix();
    }

    /** Layer 1: the map texture (nearest neighbour when zoomed in, the pre-filtered copy far out). */
    private void drawMap(GuiGraphicsExtractor g, MapData data, double uiScale) {
        boolean mainReady = data.getTexture() != null;
        // zoomed far out (or while the big texture is rebuilt) use the pre-filtered 1/4 copy
        boolean useSmall = !mainReady || zoom * uiScale < 0.5;
        Identifier tex = useSmall ? data.getTextureSmall() : data.getTexture();
        if (tex == null) tex = data.getTexture();
        if (tex == null) return;
        if (data.getMapWidth() <= 0 || data.getMapHeight() <= 0) return;
        // real screen-space ratio: 1 texture pixel per physical pixel or larger uses
        // nearest neighbour; smaller uses linear (pre-filtered copy when far out)
        double x0 = width / 2.0 - centerPx() * zoom;
        double y0 = height / 2.0 - centerPz() * zoom;
        double w = data.getMapWidth() * zoom;
        double h = data.getMapHeight() * zoom;
        int srcW = useSmall ? Math.max(1, data.getMapWidth() / 4) : data.getMapWidth();
        int srcH = useSmall ? Math.max(1, data.getMapHeight() / 4) : data.getMapHeight();
        g.blit(RenderPipelines.GUI_TEXTURED, tex, (int) Math.round(x0), (int) Math.round(y0),
                0f, 0f, Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)),
                srcW, srcH, srcW, srcH);
    }

    /** Layer 2: things drawn on top of the map - biome boundaries, player marker, render-distance box. */
    private void drawMapOverlays(GuiGraphicsExtractor g, MapData data) {
        if (data.isBiomeOverlay() && data.getMapWidth() > 0) {
            data.ensureBiomeTexture();
            if (data.getBiomeTexture() != null) {
                double x0 = width / 2.0 - centerPx() * zoom;
                double y0 = height / 2.0 - centerPz() * zoom;
                double w = data.getMapWidth() * zoom;
                double h = data.getMapHeight() * zoom;
                g.blit(RenderPipelines.GUI_TEXTURED, data.getBiomeTexture(),
                        (int) Math.round(x0), (int) Math.round(y0),
                        0f, 0f, Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)),
                        data.getMapWidth(), data.getMapHeight(), data.getMapWidth(), data.getMapHeight());
            }
        }
        drawPlayerArrow(g);
        if (data.isRenderDistanceOverlay()) {
            drawRenderDistanceBox(g);
        }
    }

    private void drawPlayerArrow(GuiGraphicsExtractor g) {
        var player = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        if (player == null || data.getMapWidth() <= 0) return;
        double px = (player.getX() - data.getMinWX()) / data.getPixelBlocks();
        double pz = (player.getZ() - data.getMinWZ()) / data.getPixelBlocks();
        float cx = (float) (width / 2.0 + (px - centerPx()) * zoom);
        float cy = (float) (height / 2.0 + (pz - centerPz()) * zoom);
        Identifier arrow = arrowTex();
        // image centre sits on the player position, image top points north
        float angle = player.getYRot() + 180.0f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.rotate((float) Math.toRadians(angle));
        g.blit(RenderPipelines.GUI_TEXTURED, arrow, -16, -16, 0f, 0f, 32, 32, 32, 32, 32, 32);
        pose.popMatrix();
    }

    /** Debug panel (Q when debugEnabled): data-layer and view state, drawn as its own modal layer. */
    private void drawDebugPanel(GuiGraphicsExtractor g, MapData data, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>(data.debugLines());
        Minecraft mc = Minecraft.getInstance();
        double uiScale = mc.getWindow().getGuiScale();
        lines.add(String.format(Locale.ROOT, "view: zoom=%.3f uiScale=%.1f fps=%d", zoom, uiScale, mc.getFps()));
        double wx = screenToWorldX(mouseX);
        double wz = screenToWorldZ(mouseY);
        lines.add(String.format(Locale.ROOT, "hover: %.1f %.1f  height=%d  biome=%d",
                wx, wz, data.heightAt((int) Math.round(wx), (int) Math.round(wz)),
                data.biomeAt((int) Math.round(wx), (int) Math.round(wz))));
        lines.add("labels: " + labelPanelState(data, zoom * uiScale) + " / "
                + (data.getBiomeLabelsFlat().length / 3) + " total");
        lines.add("press Q to close");

        int pad = 8;
        int lineH = font.lineHeight + 2;
        // keep the panel on screen: long lines (the storage path in particular) are trimmed from the
        // left so the informative tail survives
        int contentMax = Math.max(80, width - 16 - pad * 2);
        List<String> shown = new ArrayList<>(lines.size());
        for (String l : lines) shown.add(fitRight(l, contentMax));
        int panelW = 0;
        for (String l : shown) panelW = Math.max(panelW, font.width(l));
        int x = 8;
        int y = 34;
        int pw = panelW + pad * 2;
        int ph = shown.size() * lineH + pad * 2;
        g.fill(x, y, x + pw, y + ph, COL_PANEL);
        g.fill(x, y, x + pw, y + 1, COL_BORDER);
        int ty = y + pad;
        for (String l : shown) {
            g.text(font, l, x + pad, ty, COL_TEXT_DIM);
            ty += lineH;
        }
    }

    /** Trims a string to {@code maxWidth}, keeping its tail (useful for paths and ids). */
    private String fitRight(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        int room = maxWidth - font.width(ellipsis);
        int cut = s.length();
        while (cut > 0 && font.width(s.substring(cut)) > room) cut--;
        return ellipsis + s.substring(cut);
    }

    /** Layer 4: loading / empty / style-change messages, below the buttons and the dialog. */
    private void drawStatusText(GuiGraphicsExtractor g, MapData data) {
        if (data.getMapWidth() <= 0) {
            // first build: use the same top line and warning colour as a refresh, so both states match
            Component msg = data.isLoading()
                    ? Component.translatable("voxymap.ui.updating", data.getProgress())
                    : (data.getError().isEmpty() ? Component.translatable("voxymap.ui.no_data") : Component.literal(data.getError()));
            drawStatusLine(g, msg, 24, data.isLoading() ? COL_WARN : COL_TEXT);
        } else if (data.isLoading()) {
            // an old map is on screen while the refresh runs in the background
            drawStatusLine(g, Component.translatable("voxymap.ui.updating", data.getProgress()), 24, COL_WARN);
        }
        if (data.isStyleChanging()) {
            drawStatusLine(g, Component.translatable("voxymap.ui.styleChanging"), 24, COL_ACCENT);
        }
    }

    /** Centred status text with a faint plate so it stays readable over the terrain. */
    private void drawStatusLine(GuiGraphicsExtractor g, Component text, int y, int color) {
        int w = font.width(text);
        int x = width / 2 - w / 2;
        g.fill(x - 5, y - 2, x + w + 5, y + font.lineHeight + 2, 0x6614171C);
        g.centeredText(font, text, width / 2, y, color);
    }

    // ---- shared layout/text helpers (drawing and label culling must agree) ----

    private int configWidth() {
        return font.width(Component.translatable("voxymap.ui.config")) + 12;
    }

    private int biomesWidth() {
        return font.width(Component.translatable("voxymap.ui.biomes")) + 12;
    }

    private int distanceWidth() {
        return font.width(Component.translatable("voxymap.ui.renderDistance")) + 12;
    }

    private int styleSelWidth() {
        return font.width(Component.translatable("voxymap.ui.styleButton", styleName(MapData.INSTANCE.getStyle()))) + 28;
    }

    private int styleMenuWidth() {
        int w = 0;
        for (String st : MapStyles.NAMES) {
            w = Math.max(w, font.width(styleName(st)) + 24);
        }
        return w;
    }

    private Component refreshComponent() {
        return MapData.INSTANCE.isLoading()
                ? Component.translatable("voxymap.ui.refreshing")
                : Component.translatable("voxymap.ui.refresh");
    }

    private Component hintComponent() {
        Component openKey = VoxyMapMod.openKey != null
                ? VoxyMapMod.openKey.getTranslatedKeyMessage() : Component.literal("N");
        Component centerKey = VoxyMapMod.centerKey != null
                ? VoxyMapMod.centerKey.getTranslatedKeyMessage() : Component.literal("Space");
        return Component.translatable("voxymap.ui.hint", openKey, centerKey);
    }

    /** Cursor coordinate line: world position, ground height and biome name. */
    private Component coordsComponent(int mouseX, int mouseY) {
        double wx = screenToWorldX(mouseX);
        double wz = screenToWorldZ(mouseY);
        MapData md = MapData.INSTANCE;
        int gy = md.heightAt((int) Math.round(wx), (int) Math.round(wz));
        int biomeId = md.biomeAt((int) Math.round(wx), (int) Math.round(wz));
        String biomeSuffix = "";
        if (biomeId >= 0) {
            String key = md.getBiomeTranslationKey(biomeId);
            if (!key.isEmpty()) {
                biomeSuffix = "  " + net.minecraft.client.resources.language.I18n.get(key);
            } else {
                String fallback = md.getBiomeFallbackName(biomeId);
                if (fallback != null && !fallback.isEmpty()) biomeSuffix = "  " + fallback;
            }
        }
        return Component.literal(String.format(Locale.ROOT, "X %.0f  Y %d  Z %.0f", wx, gy, wz) + biomeSuffix);
    }

    /** Layer 4: cursor coordinates and the key hint, above the labels but below the buttons. */
    private void drawReadouts(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (VoxyMapConfig.SHOW_COORDINATES.get()) {
            Component coords = coordsComponent(mouseX, mouseY);
            int tw = font.width(coords);
            int tx = width / 2 - tw / 2;
            // a faint plate keeps the text readable over bright terrain
            g.fill(tx - 5, height - 20, tx + tw + 5, height - 6, 0x6614171C);
            g.text(font, coords, tx, height - 16, COL_TEXT, true);
        }
        if (VoxyMapConfig.SHOW_HINT.get()) {
            Component hint = hintComponent();
            int hintW = font.width(hint);
            g.fill(width / 2 - hintW / 2 - 5, 6, width / 2 + hintW / 2 + 5, 20, 0x6614171C);
            g.centeredText(font, hint, width / 2, 8, COL_TEXT);
        }
    }

    /** Layer 5: buttons and their dropdown, above every readout but below the modal dialog. */
    private void drawButtons(GuiGraphicsExtractor g, MapData data, int mouseX, int mouseY) {
        // map scale slider (top-right, above the palette dropdown)
        boolean scaleEnabled = !data.isLoading();
        Component scaleLabel = Component.translatable("voxymap.ui.mapScale");
        String scaleValue = scaleValueText();
        int panelLeft = scalePanelLeft();
        int panelRight = width - 8;
        int rowTop = SCALE_ROW_Y;
        boolean sliderHover = mouseX >= panelLeft && mouseX <= panelRight && mouseY >= rowTop && mouseY <= rowTop + 20;
        float sliderHov = scaleEnabled ? hoverAmount("scale", sliderHover || sliderDragging) : 0f;
        g.fill(panelLeft, rowTop, panelRight, rowTop + 20, scaleEnabled ? COL_PANEL : COL_PANEL_OFF);
        if (scaleEnabled && sliderHov > 0.01f) {
            g.fill(panelLeft, rowTop, panelRight, rowTop + 20, withAlpha(COL_ROW_HOVER, sliderHov));
        }
        g.fill(panelLeft, rowTop, panelRight, rowTop + 1,
                scaleEnabled ? lerpColor(COL_BORDER, COL_ACCENT, sliderHov * 0.5f) : COL_BORDER_OFF);
        g.text(font, scaleLabel, panelLeft + 6, rowTop + 6, scaleEnabled ? COL_TEXT : COL_TEXT_OFF);
        int sliderX = scaleSliderX();
        int sliderRight = sliderX + SCALE_SLIDER_W;
        int trackY = rowTop + 8;
        g.fill(sliderX, trackY, sliderRight, trackY + 4, scaleEnabled ? 0xFF343A42 : 0xFF24282E);
        int knobX = scaleKnobX();
        int knobW = sliderDragging || sliderHov > 0.4f ? SCALE_KNOB_W + 2 : SCALE_KNOB_W;
        int knobLeft = knobX - (knobW - SCALE_KNOB_W) / 2;
        g.fill(sliderX, trackY, knobLeft + knobW / 2, trackY + 4, scaleEnabled ? COL_ACCENT : 0xFF3F4850);
        g.fill(knobLeft, trackY - 3, knobLeft + knobW, trackY + 7,
                scaleEnabled ? lerpColor(COL_TEXT, 0xFFFFFFFF, sliderHov) : COL_TEXT_OFF);
        g.text(font, scaleValue, sliderRight + 8, rowTop + 6, scaleEnabled ? COL_TEXT : COL_TEXT_OFF);

        // style selector button (top-right) with an animated dropdown
        Component styleLabel = Component.translatable("voxymap.ui.styleButton", styleName(data.getStyle()));
        int selW = font.width(styleLabel) + 28;
        int selX = width - 8 - selW;
        int selY = STYLE_ROW_Y;
        float styleHov = hoverAmount("style", mouseX >= selX && mouseX <= selX + selW && mouseY >= selY && mouseY <= selY + 20);
        drawButton(g, selX, selY, selW, styleLabel, styleHov, true);
        g.text(font, Component.literal("v"), selX + selW - 14, selY + 6,
                lerpColor(COL_TEXT_DIM, COL_TEXT, styleHov));
        if (styleMenuT > 0.02f) {
            int rowH = 20;
            int menuW = 0;
            for (String st : MapStyles.NAMES) {
                menuW = Math.max(menuW, font.width(styleName(st)) + 24);
            }
            int menuX = width - 8 - menuW;
            int menuY = selY + 24;
            // the panel fades in together with its rows so the open does not pop
            g.fill(menuX, menuY, menuX + menuW, menuY + 6 * rowH + 4,
                    withAlpha(COL_PANEL, Math.min(1f, styleMenuT * 1.35f)));
            g.fill(menuX, menuY, menuX + menuW, menuY + 1,
                    withAlpha(COL_BORDER, Math.min(1f, styleMenuT * 1.35f)));
            for (int k = 0; k < MapStyles.NAMES.length; k++) {
                float rowT = rowAlpha(styleMenuT, k);
                if (rowT <= 0.02f) continue;
                String st = MapStyles.NAMES[k];
                boolean selected = st.equals(data.getStyle());
                int ry = menuY + 2 + k * rowH;
                boolean rowHover = rowT > 0.6f && mouseX >= menuX && mouseX <= menuX + menuW
                        && mouseY >= ry && mouseY <= ry + rowH;
                float rowHov = hoverAmount("style:" + st, rowHover);
                if (selected) {
                    g.fill(menuX, ry, menuX + menuW, ry + rowH, withAlpha(COL_SELECTED, rowT));
                } else if (rowHov > 0.01f) {
                    g.fill(menuX, ry, menuX + menuW, ry + rowH, withAlpha(COL_ROW_HOVER, rowT * rowHov));
                }
                int rowColor = selected ? COL_ACCENT : lerpColor(COL_TEXT, 0xFFFFFFFF, rowHov);
                g.text(font, styleName(st), menuX + 6, ry + 6, withAlpha(rowColor, rowT));
            }
        }

        // biome / render distance toggles bottom-left
        boolean biomesOn = data.isBiomeOverlay();
        boolean distanceOn = data.isRenderDistanceOverlay();
        Component biomeText = Component.translatable("voxymap.ui.biomes");
        int bw = font.width(biomeText) + 12;
        int bx = 8;
        int by = height - 30;
        drawToggle(g, "biomes", bx, by, bw, biomeText, biomesOn,
                mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 20);
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int ry2 = by - 24;
        drawToggle(g, "distance", bx, ry2, rdw, rdText, distanceOn,
                mouseX >= bx && mouseX <= bx + rdw && mouseY >= ry2 && mouseY <= ry2 + 20);

        // refresh button bottom-right
        boolean refreshing = data.isLoading();
        Component refresh = refreshing
                ? Component.translatable("voxymap.ui.refreshing")
                : Component.translatable("voxymap.ui.refresh");
        int rw = font.width(refresh) + 12;
        int rx = width - 8 - rw;
        drawButton(g, rx, height - 30, rw, refresh,
                hoverAmount("refresh", mouseX >= rx && mouseX <= width - 8 && mouseY >= height - 30 && mouseY <= height - 10),
                !refreshing);

        // config button top-left
        Component cfg = Component.translatable("voxymap.ui.config");
        int cw = font.width(cfg) + 12;
        drawButton(g, 8, 8, cw, cfg,
                hoverAmount("config", mouseX >= 8 && mouseX <= 8 + cw && mouseY >= 8 && mouseY <= 28),
                true);
        if (configPressT > 0.01f) {
            g.fill(8, 8, 8 + cw, 28, withAlpha(COL_ACCENT, configPressT * 0.45f));
        }
    }

    private void drawRenderDistanceBox(GuiGraphicsExtractor g) {
        var player = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        if (player == null || data.getMapWidth() <= 0) return;
        double dist = Minecraft.getInstance().options.renderDistance().get() * 16.0;
        double px = player.getX();
        double pz = player.getZ();
        double gx0 = (px - dist - data.getMinWX()) / data.getPixelBlocks();
        double gx1 = (px + dist - data.getMinWX()) / data.getPixelBlocks();
        double gz0 = (pz - dist - data.getMinWZ()) / data.getPixelBlocks();
        double gz1 = (pz + dist - data.getMinWZ()) / data.getPixelBlocks();
        int x0 = (int) Math.round(width / 2.0 + (gx0 - centerPx()) * zoom);
        int x1 = (int) Math.round(width / 2.0 + (gx1 - centerPx()) * zoom);
        int z0 = (int) Math.round(height / 2.0 + (gz0 - centerPz()) * zoom);
        int z1 = (int) Math.round(height / 2.0 + (gz1 - centerPz()) * zoom);
        int yellow = 0xE0FFFF00;
        g.fill(x0, z0, x0 + 1, z1 + 1, yellow);
        g.fill(x1, z0, x1 + 1, z1 + 1, yellow);
        g.fill(x0, z0, x1 + 1, z0 + 1, yellow);
        g.fill(x0, z1, x1 + 1, z1 + 1, yellow);
    }

    /**
     * Layout of the LOD dialog. The width follows the wrapped message (so long English strings stay
     * inside the panel) and the buttons grow with their labels; the whole panel is capped to the
     * screen, and on narrow screens the buttons shrink instead of spilling over.
     */
    private record DialogBox(int x, int y, int w, int h, int btnW, int btnH,
                             int firstBtnX, int secondBtnX, int btnY, List<FormattedCharSequence> lines) {
    }

    private DialogBox dialogBox() {
        Component title = Component.translatable("voxymap.ui.lodWarning.title");
        Component message = Component.translatable("voxymap.ui.lodWarning.message");
        Component refreshLabel = Component.translatable("voxymap.ui.lodWarning.refreshNow");
        Component waitLabel = Component.translatable("voxymap.ui.lodWarning.wait");
        int maxText = Math.max(160, Math.min(width - 80, 640));
        List<FormattedCharSequence> lines = font.split(message, maxText);
        int content = font.width(title);
        for (FormattedCharSequence line : lines) content = Math.max(content, font.width(line));
        int gap = 20;
        int btnW = Math.max(170, Math.max(font.width(refreshLabel), font.width(waitLabel)) + 20);
        int minWidth = btnW * 2 + gap + 24;
        int w = Math.min(width - 24, Math.max(420, Math.max(content + 48, minWidth)));
        btnW = Math.max(80, Math.min(btnW, (w - 24 - gap) / 2));
        int h = 10 + font.lineHeight + 8 + lines.size() * font.lineHeight + 16 + 20 + 12;
        int x = width / 2 - w / 2;
        int y = height / 2 - h / 2;
        int totalButtons = btnW * 2 + gap;
        int firstBtnX = width / 2 - totalButtons / 2;
        int secondBtnX = firstBtnX + btnW + gap;
        int btnY = y + h - 20 - 12;
        return new DialogBox(x, y, w, h, btnW, 20, firstBtnX, secondBtnX, btnY, lines);
    }

    private void drawLodWarning(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        DialogBox box = dialogBox();
        g.fill(0, 0, width, height, 0x99000000);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), COL_PANEL_DIALOG);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + 1, COL_BORDER);
        g.centeredText(font, Component.translatable("voxymap.ui.lodWarning.title"),
                width / 2, box.y() + 10, COL_WARN);
        int ty = box.y() + 10 + font.lineHeight + 8;
        for (FormattedCharSequence line : box.lines()) {
            g.text(font, line, width / 2 - font.width(line) / 2, ty, COL_TEXT);
            ty += font.lineHeight;
        }
        drawDialogButton(g, box.firstBtnX(), box.btnY(), box.btnW(), box.btnH(),
                Component.translatable("voxymap.ui.lodWarning.refreshNow"), COL_WARN, mouseX, mouseY);
        drawDialogButton(g, box.secondBtnX(), box.btnY(), box.btnW(), box.btnH(),
                Component.translatable("voxymap.ui.lodWarning.wait"), COL_ACCENT, mouseX, mouseY);
    }

    /** Dialog button: same panel language as the HUD buttons, with a coloured top edge. */
    private void drawDialogButton(GuiGraphicsExtractor g, int x, int y, int w, int h, Component label, int accent, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        float hov = hoverAmount("dialog:" + label.getString(), hovered);
        g.fill(x, y, x + w, y + h, COL_PANEL);
        if (hov > 0.01f) {
            g.fill(x, y, x + w, y + h, withAlpha(COL_ROW_HOVER, hov));
        }
        g.fill(x, y, x + w, y + 1, lerpColor(accent, 0xFFFFFFFF, hov * 0.3f));
        g.centeredText(font, label, x + w / 2, y + (h - font.lineHeight) / 2 + 1,
                lerpColor(COL_TEXT, 0xFFFFFFFF, hov));
    }


    /**
     * Collects the exact screen rectangles of the HUD controls and text plates. Biome labels whose
     * box overlaps one of them are skipped: GuiGraphicsExtractor batches text in its own order, so a label
     * can otherwise end up drawn on top of a button even though the button is drawn later.
     */
    private void collectLabelBlockers(int mouseX, int mouseY) {
        labelBlockers.clear();
        // config button (top-left)
        labelBlockers.add(new int[]{6, 6, 8 + configWidth() + 2, 30});
        // map scale panel (top-right)
        labelBlockers.add(new int[]{scalePanelLeft() - 2, SCALE_ROW_Y - 2, width - 6, SCALE_ROW_Y + 22});
        // palette button
        int selW = styleSelWidth();
        labelBlockers.add(new int[]{width - 8 - selW - 2, STYLE_ROW_Y - 2, width - 6, STYLE_ROW_Y + 22});
        // palette dropdown (only while open)
        if (styleMenuOpen) {
            int menuW = styleMenuWidth();
            labelBlockers.add(new int[]{width - 8 - menuW - 2, STYLE_ROW_Y + 22, width - 6,
                    STYLE_ROW_Y + 24 + 6 * 20 + 6});
        }
        // biome / distance toggles (bottom-left)
        labelBlockers.add(new int[]{6, height - 32, 8 + biomesWidth() + 2, height - 8});
        labelBlockers.add(new int[]{6, height - 56, 8 + distanceWidth() + 2, height - 32});
        // refresh (bottom-right)
        Component refresh = refreshComponent();
        int rw = font.width(refresh) + 12;
        labelBlockers.add(new int[]{width - 8 - rw - 2, height - 32, width - 6, height - 8});
        // right-click menu
        if (contextMenuT > 0.02f) {
            int rowH = 20;
            int menuW = Math.max(font.width(Component.translatable("voxymap.ui.copy")),
                    Math.max(font.width(Component.translatable("voxymap.ui.teleport")),
                            font.width(Component.translatable("voxymap.ui.export")))) + 24;
            int rows = VoxyMapConfig.TELEPORT_ENABLED.get() ? 3 : 2;
            int menuH = rowH * rows + 4;
            int x = Math.min(menuX, width - menuW - 4);
            int y = Math.min(menuY, height - menuH - 4);
            labelBlockers.add(new int[]{x - 2, y - 2, x + menuW + 2, y + menuH + 2});
        }
        // top hint plate
        if (VoxyMapConfig.SHOW_HINT.get()) {
            int w = font.width(hintComponent());
            labelBlockers.add(new int[]{width / 2 - w / 2 - 6, 4, width / 2 + w / 2 + 6, 22});
        }
        // bottom coordinate plate
        if (VoxyMapConfig.SHOW_COORDINATES.get()) {
            int w = font.width(coordsComponent(mouseX, mouseY));
            labelBlockers.add(new int[]{width / 2 - w / 2 - 6, height - 22, width / 2 + w / 2 + 6, height - 4});
        }
        // status line plates (same place as drawStatusLine uses)
        if (MapData.INSTANCE.isLoading() && MapData.INSTANCE.getMapWidth() > 0) {
            int w = font.width(Component.translatable("voxymap.ui.updating", MapData.INSTANCE.getProgress()));
            labelBlockers.add(new int[]{width / 2 - w / 2 - 6, 22, width / 2 + w / 2 + 6, 40});
        }
        if (MapData.INSTANCE.isStyleChanging()) {
            int w = font.width(Component.translatable("voxymap.ui.styleChanging"));
            labelBlockers.add(new int[]{width / 2 - w / 2 - 6, 22, width / 2 + w / 2 + 6, 40});
        }
    }

    private boolean labelBlocked(double x0, double y0, double x1, double y1) {
        for (int[] r : labelBlockers) {
            if (x1 > r[0] && x0 < r[2] && y1 > r[1] && y0 < r[3]) return true;
        }
        return false;
    }

    private void drawBiomeLabels(GuiGraphicsExtractor g) {
        MapData data = MapData.INSTANCE;
        if (data.getMapWidth() <= 0) return;
        // two discrete label sizes only on very large GUI scales (4x+); otherwise default size
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float labelScale = (guiScale >= 4.0 && zoom < 1.0) ? 0.5f : 1.0f;
        int[] labels = data.getBiomeLabelsFlat();
        double cx = centerPx();
        double cz = centerPz();
        double halfLine = font.lineHeight * labelScale / 2.0 + 2;
        for (int i = 0; i + 2 < labels.length; i += 3) {
            double sx = width / 2.0 + (labels[i] - cx) * zoom;
            double sy = height / 2.0 + (labels[i + 1] - cz) * zoom;
            // cheap reject before touching the font: most of the labels are far outside the window
            if (sx < -96 || sx > width + 96 || sy < -halfLine - 6 || sy > height + halfLine + 6) continue;
            LabelInfo info = labelInfo(data, labels[i + 2]);
            if (info == null) continue;
            // exact culling with the real label size: skip boxes that do not intersect the window
            double halfW = info.width * labelScale / 2.0 + 2;
            if (sx + halfW < 0 || sx - halfW > width) continue;
            // and skip labels covered by a HUD control
            if (labelBlocked(sx - halfW, sy - halfLine, sx + halfW, sy + halfLine)) continue;
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            g.pose().scale(labelScale, labelScale);
            if (VoxyMapConfig.BIOME_LABEL_BACKGROUND.get()) {
                int bx0 = -info.width / 2 - 2;
                int by0 = -font.lineHeight / 2 - 1;
                g.fill(bx0, by0, bx0 + info.width + 4, by0 + font.lineHeight + 2, 0x5514171C);
            }
            g.text(font, info.text, -(int) (info.width / 2f), -font.lineHeight / 2, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
    }

    /** Label text and width for a biome, cached across frames (invalidated by language or world). */
    private LabelInfo labelInfo(MapData data, int biomeId) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!language.equals(labelLanguage)) {
            labelLanguage = language;
            labelCache.clear();
        }
        String name = data.getBiomeName(biomeId);
        if (name == null) return null;
        LabelInfo cached = labelCache.get(biomeId);
        if (cached != null && cached.name.equals(name)) return cached;
        Component text;
        String key = data.getBiomeTranslationKey(biomeId);
        if (!key.isEmpty()) {
            text = Component.translatable(key);
        } else {
            String fallback = data.getBiomeFallbackName(biomeId);
            if (fallback == null) return null;
            text = Component.literal(fallback);
        }
        LabelInfo info = new LabelInfo(name, text, font.width(text));
        labelCache.put(biomeId, info);
        return info;
    }

    /**
     * Debug readout for the label layer. Labels are only drawn once zoom * guiScale reaches 1.0, so
     * below that threshold there is nothing to count: report the state instead of scanning the
     * labels (the scan is per-frame and only meaningful when the layer is actually drawn).
     */
    private String labelPanelState(MapData data, double labelZoom) {
        if (labelZoom < 1.0) {
            return String.format(Locale.ROOT, "not drawn (zoom*uiScale=%.2f < 1.0)", labelZoom);
        }
        return countVisibleLabels(data) + " in view";
    }

    /** Number of label boxes that intersect the window at the current view (debug readout). */
    private int countVisibleLabels(MapData data) {
        int[] labels = data.getBiomeLabelsFlat();
        double cx = centerPx();
        double cz = centerPz();
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float labelScale = (guiScale >= 4.0 && zoom < 1.0) ? 0.5f : 1.0f;
        double halfLine = font.lineHeight * labelScale / 2.0 + 2;
        int count = 0;
        for (int i = 0; i + 2 < labels.length; i += 3) {
            double sx = width / 2.0 + (labels[i] - cx) * zoom;
            double sy = height / 2.0 + (labels[i + 1] - cz) * zoom;
            if (sx < -96 || sx > width + 96 || sy < -halfLine - 6 || sy > height + halfLine + 6) continue;
            LabelInfo info = labelInfo(data, labels[i + 2]);
            if (info == null) continue;
            double halfW = info.width * labelScale / 2.0 + 2;
            if (sx + halfW < 0 || sx - halfW > width) continue;
            count++;
        }
        return count;
    }

    private void drawContextMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean teleport = VoxyMapConfig.TELEPORT_ENABLED.get();
        Component copy = Component.translatable("voxymap.ui.copy");
        Component tp = Component.translatable("voxymap.ui.teleport");
        Component exp = Component.translatable("voxymap.ui.export");
        int rowH = 20;
        int w = Math.max(font.width(copy), Math.max(font.width(tp), font.width(exp))) + 24;
        int rows = teleport ? 3 : 2;
        int h = rowH * rows + 4;
        int x = Math.min(menuX, width - w - 4);
        int y = Math.min(menuY, height - h - 4);
        float t = contextMenuT;
        // panel fades in with its rows
        g.fill(x, y, x + w, y + h, withAlpha(COL_PANEL, Math.min(1f, t * 1.35f)));
        g.fill(x, y, x + w, y + 1, withAlpha(COL_BORDER, Math.min(1f, t * 1.35f)));
        drawMenuRow(g, x, y + 2, w, rowH, copy, mouseX, mouseY, rowAlpha(t, 0));
        if (teleport) {
            drawMenuRow(g, x, y + 2 + rowH, w, rowH, tp, mouseX, mouseY, rowAlpha(t, 1));
        }
        drawMenuRow(g, x, y + 2 + rowH * (teleport ? 2 : 1), w, rowH, exp, mouseX, mouseY,
                rowAlpha(t, teleport ? 2 : 1));
    }

    /** Staggered fade-in for menu rows. */
    private static float rowAlpha(float t, int index) {
        return Math.min(1f, Math.max(0f, t * 1.6f - index * 0.12f));
    }

    private void drawMenuRow(GuiGraphicsExtractor g, int x, int y, int w, int rowH, Component label, int mouseX, int mouseY, float alpha) {
        boolean hovered = alpha > 0.5f && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + rowH;
        float hov = hoverAmount("ctx:" + label.getString(), hovered);
        if (hov > 0.01f) {
            g.fill(x, y, x + w, y + rowH, withAlpha(COL_ROW_HOVER, alpha * hov));
        }
        g.text(font, label, x + 8, y + 6, withAlpha(lerpColor(COL_TEXT, 0xFFFFFFFF, hov), alpha));
    }

    private void exportMap() {
        MapData data = MapData.INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        var dir = mc.gameDirectory.toPath().resolve("voxymap-exports");
        String name = "map_" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
        java.nio.file.Path out = dir.resolve(name);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            boolean ok = data.exportPng(out);
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.translatable(
                            ok ? "voxymap.ui.exported" : "voxymap.ui.export_failed", out.toString()));
                }
            });
        });
    }

    private void openConfig() {
        Minecraft.getInstance().setScreenAndShow(new MapConfigScreen(this));
    }

    private boolean inMenu(int mx, int my, int row) {
        boolean teleport = VoxyMapConfig.TELEPORT_ENABLED.get();
        Component copy = Component.translatable("voxymap.ui.copy");
        Component tp = Component.translatable("voxymap.ui.teleport");
        Component exp = Component.translatable("voxymap.ui.export");
        int rowH = 20;
        int w = Math.max(font.width(copy), Math.max(font.width(tp), font.width(exp))) + 24;
        int rows = teleport ? 3 : 2;
        int h = rowH * rows + 4;
        int x = Math.min(menuX, width - w - 4);
        int y = Math.min(menuY, height - h - 4);
        return mx >= x && mx <= x + w && my >= y + row * rowH && my <= y + (row + 1) * rowH;
    }

    @Override
    public void onClose() {
        // animate first; finishClose() does the real close once the animation ended
        if (closing) return;
        closing = true;
        closeT = 0f;
    }

    private void finishClose() {
        if (VoxyMapConfig.REMEMBER_ZOOM.get()) {
            MapData.INSTANCE.setZoom(zoom);
        }
        if (VoxyMapConfig.RELEASE_TEXTURES_ON_CLOSE.get()) {
            try {
                MapData.INSTANCE.releaseTextures();
            } catch (Throwable t) {
                System.err.println("[voxymap] release textures failed: " + t);
            }
        }
        super.onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int keyCode = e.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE
                || (VoxyMapMod.openKey != null && VoxyMapMod.openKey.matches(e))) {
            onClose();
            return true;
        }
        if (VoxyMapMod.centerKey != null && VoxyMapMod.centerKey.matches(e)) {
            var p = Minecraft.getInstance().player;
            if (p != null) {
                centerX = p.getX();
                centerZ = p.getZ();
            }
            return true;
        }
        if (VoxyMapConfig.DEBUG_ENABLED.get() && keyCode == GLFW.GLFW_KEY_Q) {
            debugPanel = !debugPanel;
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (closing) return true;
        double mx = e.x();
        double my = e.y();
        int button = e.button();
        MapData data = MapData.INSTANCE;
        if (!data.isOverworld()) return true;
        if (lodWarning && button == 0) {
            DialogBox box = dialogBox();
            if (mx >= box.firstBtnX() && mx <= box.firstBtnX() + box.btnW()
                    && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
                data.skipInitialDelay();
                data.updateAsync(true);
                lodWarning = false;
                return true;
            }
            if (mx >= box.secondBtnX() && mx <= box.secondBtnX() + box.btnW()
                    && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
                // cancel: close the map screen
                lodWarning = false;
                onClose();
                return true;
            }
            return true;
        }
        // map scale slider: capture the drag here, the rebuild only happens on release
        if (button == 0 && !data.isLoading()) {
            int panelLeft = scalePanelLeft();
            if (mx >= panelLeft && mx <= width - 8 && my >= SCALE_ROW_Y && my <= SCALE_ROW_Y + 20) {
                sliderDragging = true;
                sliderValue = scaleSliderValueAt(mx);
                return true;
            }
        }
        if (menuOpen && button == 0) {
            if (inMenu((int) mx, (int) my, 0)) {
                copyCoords();
                menuOpen = false;
                return true;
            }
            if (VoxyMapConfig.TELEPORT_ENABLED.get() && inMenu((int) mx, (int) my, 1)) {
                teleport();
                menuOpen = false;
                return true;
            }
            int exportRow = VoxyMapConfig.TELEPORT_ENABLED.get() ? 2 : 1;
            if (inMenu((int) mx, (int) my, exportRow)) {
                exportMap();
                menuOpen = false;
                return true;
            }
            menuOpen = false;
            return true;
        }
        Component styleLabel2 = Component.translatable("voxymap.ui.styleButton", styleName(data.getStyle()));
        int selW2 = font.width(styleLabel2) + 28;
        int selX2 = width - 8 - selW2;
        int selY2 = STYLE_ROW_Y;
        if (styleMenuOpen) {
            int rowH = 20;
            int menuW = 0;
            for (String st : MapStyles.NAMES) {
                menuW = Math.max(menuW, font.width(styleName(st)) + 24);
            }
            int menuX = width - 8 - menuW;
            int menuY = selY2 + 24;
            for (int k = 0; k < MapStyles.NAMES.length; k++) {
                int ry = menuY + 2 + k * rowH;
                if (button == 0 && mx >= menuX && mx <= menuX + menuW && my >= ry && my <= ry + rowH) {
                    if (!data.isStyleChanging()) {
                        // setStyle also stores the choice in this world's per-world state file
                        data.setStyle(MapStyles.NAMES[k]);
                    }
                    styleMenuOpen = false;
                    return true;
                }
            }
            if (button == 0 && !(mx >= selX2 && mx <= selX2 + selW2 && my >= selY2 && my <= selY2 + 20)) {
                // just close the dropdown and fall through, so the same click can start dragging
                styleMenuOpen = false;
            }
        }
        if (button == 0 && mx >= selX2 && mx <= selX2 + selW2 && my >= selY2 && my <= selY2 + 20) {
            styleMenuOpen = !styleMenuOpen;
            return true;
        }
        Component biomeText = Component.translatable("voxymap.ui.biomes");
        int bw = font.width(biomeText) + 12;
        int bx = 8;
        int by = height - 30;
        if (button == 0 && mx >= bx && mx <= bx + bw && my >= by && my <= by + 20) {
            data.setBiomeOverlay(!data.isBiomeOverlay());
            return true;
        }
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int rx2 = bx;
        int ry2 = by - 24;
        if (button == 0 && mx >= rx2 && mx <= rx2 + rdw && my >= ry2 && my <= ry2 + 20) {
            data.setRenderDistanceOverlay(!data.isRenderDistanceOverlay());
            return true;
        }
        Component refresh = data.isLoading()
                ? Component.translatable("voxymap.ui.refreshing")
                : Component.translatable("voxymap.ui.refresh");
        int rw = font.width(refresh) + 12;
        int rx = width - 8 - rw;
        if (button == 0 && mx >= rx && mx <= width - 8 && my >= height - 30 && my <= height - 10) {
            data.updateAsync(true);
            return true;
        }
        Component cfg = Component.translatable("voxymap.ui.config");
        int cw = font.width(cfg) + 12;
        if (button == 0 && mx >= 8 && mx <= 8 + cw && my >= 8 && my <= 28) {
            configPressT = 1f;
            openConfig();
            return true;
        }
        if (button == 0) {
            dragging = true;
            return true;
        }
        if (button == 1) {
            menuOpen = true;
            menuX = (int) mx;
            menuY = (int) my;
            double wx = screenToWorldX(mx);
            double wz = screenToWorldZ(my);
            menuWorldX = (int) Math.round(wx);
            menuWorldZ = (int) Math.round(wz);
            menuWorldY = data.heightAt(menuWorldX, menuWorldZ);
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    private void copyCoords() {
        String text = menuWorldX + " " + menuWorldY + " " + menuWorldZ;
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    private void teleport() {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            conn.sendCommand("tp " + menuWorldX + " " + menuWorldY + " " + menuWorldZ);
        }
        onClose();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        double mx = e.x();
        double my = e.y();
        if (sliderDragging) {
            sliderValue = scaleSliderValueAt(mx);
            return true;
        }
        if (dragging) {
            centerX -= dx * MapData.INSTANCE.getPixelBlocks() / zoom;
            centerZ -= dy * MapData.INSTANCE.getPixelBlocks() / zoom;
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        double mx = e.x();
        double my = e.y();
        if (sliderDragging) {
            // release: commit the new map scale, store it per world and rebuild at that scale
            sliderDragging = false;
            MapData data = MapData.INSTANCE;
            int value = scaleSliderValueAt(mx);
            sliderValue = value;
            if (value != data.getMapScale()) {
                data.setMapScale(value);
                data.updateAsync(true);
            }
            return true;
        }
        dragging = false;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!MapData.INSTANCE.isOverworld()) return true;
        if (scrollY == 0) return false;
        double worldX = screenToWorldX(mx);
        double worldZ = screenToWorldZ(my);
        double factor = scrollY > 0 ? 1.15 : 1.0 / 1.15;
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * factor));
        double pb = MapData.INSTANCE.getPixelBlocks();
        centerX = worldX - (mx - width / 2.0) * pb / zoom;
        centerZ = worldZ - (my - height / 2.0) * pb / zoom;
        return true;
    }
}
