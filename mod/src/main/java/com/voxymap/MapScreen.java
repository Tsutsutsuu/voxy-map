package com.voxymap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
    private static final int SCALE_SLIDER_W = 110;

    private double centerX;
    private double centerZ;
    private double zoom = 0.5;
    private boolean dragging;
    private int menuX, menuY, menuWorldX, menuWorldZ, menuWorldY;
    private boolean menuOpen;
    private boolean linearFilter = true;
    private boolean styleMenuOpen;
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
    private static final ResourceLocation ARROW_CLEAN = ResourceLocation.fromNamespaceAndPath("voxymap", "textures/gui/arrow_clean.png");
    private static final ResourceLocation ARROW_CLASSIC = ResourceLocation.fromNamespaceAndPath("voxymap", "textures/gui/arrow_classic.png");

    private static Component styleName(String st) {
        return Component.translatable("voxymap.style." + st);
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
        return scaleSliderX() + (int) Math.round((sliderValue - 1) / 7.0 * (SCALE_SLIDER_W - 10));
    }

    private int scaleSliderValueAt(double mx) {
        double t = (mx - scaleSliderX()) / (double) (SCALE_SLIDER_W - 10);
        return Math.max(1, Math.min(8, (int) Math.round(t * 7.0) + 1));
    }

    private static ResourceLocation arrowTex() {
        return "classic".equals(VoxyMapConfig.ARROW_STYLE.get()) ? ARROW_CLASSIC : ARROW_CLEAN;
    }

    public MapScreen() {
        super(Component.translatable("voxymap.ui.title"));
    }

    @Override
    protected void init() {
        // always open centered on the player at the configured zoom
        zoom = VoxyMapConfig.DEFAULT_ZOOM.get();
        var p0 = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        sliderValue = data.getMapScale();
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
            if (!data.isReady() && !data.isLoading()) {
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF0A0A0A);
        MapData data = MapData.INSTANCE;
        double uiScale = Minecraft.getInstance().getWindow().getGuiScale();

        // draw order is the stacking order, bottom to top:
        //   1 map, 2 map overlays, 3 biome labels, 4 readouts/status text, 5 buttons, 6 dialog
        drawMap(g, data, uiScale);
        drawMapOverlays(g, data);
        // GuiGraphics batches text independently of fills, and endBatch() draws the batches in
        // RenderType order rather than call order, so text can survive a later fill. While the
        // modal dialog is open, skip every textual/interactive layer above the map: that is the
        // only way to guarantee the dialog covers them.
        if (!lodWarning && debugPanel) {
            // the debug panel is a modal layer of its own: skip the text/button layers for the same
            // batch-ordering reason as the dialog
            g.flush();
            drawDebugPanel(g, data, mouseX, mouseY);
            g.flush();
        } else if (!lodWarning) {
            // labels only when the map is at or above point-to-point, otherwise they cost more than they show
            if (data.isBiomeOverlay() && zoom * uiScale >= 1.0) drawBiomeLabels(g);
            drawStatusText(g, data);
            drawReadouts(g, mouseX, mouseY);
            g.flush();
            drawButtons(g, data);
            if (menuOpen) {
                drawContextMenu(g);
            }
            g.flush();
        }
        // the modal dialog always wins, whatever the map state is
        if (lodWarning) {
            drawLodWarning(g);
            g.flush();
        }
    }

    /** Layer 1: the map texture (nearest neighbour when zoomed in, the pre-filtered copy far out). */
    private void drawMap(GuiGraphics g, MapData data, double uiScale) {
        boolean useSmall = zoom * uiScale < 0.5;
        ResourceLocation tex = useSmall ? data.getTextureSmall() : data.getTexture();
        if (tex == null || !data.isReady()) return;
        // real screen-space ratio: 1 texture pixel per physical pixel or larger uses
        // nearest neighbour; smaller uses linear (pre-filtered copy when far out)
        boolean wantLinear = useSmall || zoom * uiScale < 1.0;
        if (wantLinear != linearFilter) {
            var t = Minecraft.getInstance().getTextureManager().getTexture(tex);
            if (t != null) t.setFilter(wantLinear, false);
            linearFilter = wantLinear;
        }
        double x0 = width / 2.0 - centerPx() * zoom;
        double y0 = height / 2.0 - centerPz() * zoom;
        double w = data.getMapWidth() * zoom;
        double h = data.getMapHeight() * zoom;
        int srcW = useSmall ? Math.max(1, data.getMapWidth() / 4) : data.getMapWidth();
        int srcH = useSmall ? Math.max(1, data.getMapHeight() / 4) : data.getMapHeight();
        g.blit(tex, (int) Math.round(x0), (int) Math.round(y0),
                Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)),
                0, 0, srcW, srcH, srcW, srcH);
    }

    /** Layer 2: things drawn on top of the map - biome boundaries, player marker, render-distance box. */
    private void drawMapOverlays(GuiGraphics g, MapData data) {
        if (data.isBiomeOverlay() && data.isReady()) {
            data.ensureBiomeTexture();
            if (data.getBiomeTexture() != null) {
                double x0 = width / 2.0 - centerPx() * zoom;
                double y0 = height / 2.0 - centerPz() * zoom;
                double w = data.getMapWidth() * zoom;
                double h = data.getMapHeight() * zoom;
                var bt = Minecraft.getInstance().getTextureManager().getTexture(data.getBiomeTexture());
                if (bt != null) bt.setFilter(false, false);
                g.blit(data.getBiomeTexture(), (int) Math.round(x0), (int) Math.round(y0),
                        Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)),
                        0, 0, data.getMapWidth(), data.getMapHeight(), data.getMapWidth(), data.getMapHeight());
            }
        }
        drawPlayerArrow(g);
        if (data.isRenderDistanceOverlay()) {
            drawRenderDistanceBox(g);
        }
    }

    private void drawPlayerArrow(GuiGraphics g) {
        var player = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        if (player == null || !data.isReady()) return;
        double px = (player.getX() - data.getMinWX()) / data.getPixelBlocks();
        double pz = (player.getZ() - data.getMinWZ()) / data.getPixelBlocks();
        float cx = (float) (width / 2.0 + (px - centerPx()) * zoom);
        float cy = (float) (height / 2.0 + (pz - centerPz()) * zoom);
        ResourceLocation arrow = arrowTex();
        var arrowTexture = Minecraft.getInstance().getTextureManager().getTexture(arrow);
        if (arrowTexture != null) arrowTexture.setFilter(false, false);
        // image centre sits on the player position, image top points north
        float angle = player.getYRot() + 180.0f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1f, 1f, 1f, 1f);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(angle));
        g.blit(arrow, -16, -16, 32, 32, 0, 0, 32, 32, 32, 32);
        pose.popPose();
        RenderSystem.disableBlend();
    }

    /** Debug panel (Q when debugEnabled): data-layer and view state, drawn as its own modal layer. */
    private void drawDebugPanel(GuiGraphics g, MapData data, int mouseX, int mouseY) {
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
        g.fill(x, y, x + pw, y + ph, 0xE8101010);
        g.fill(x, y, x + pw, y + 1, 0xFF606060);
        g.fill(x, y + ph - 1, x + pw, y + ph, 0xFF404040);
        int ty = y + pad;
        for (String l : shown) {
            g.drawString(font, l, x + pad, ty, 0xFFD8E8FF, false);
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
    private void drawStatusText(GuiGraphics g, MapData data) {
        if (!data.isReady()) {
            Component msg = data.isLoading()
                    ? Component.translatable("voxymap.ui.loading", data.getProgress())
                    : (data.getError().isEmpty() ? Component.translatable("voxymap.ui.no_data") : Component.literal(data.getError()));
            g.drawCenteredString(font, msg, width / 2, height / 2 - 10, 0xFFFFFF);
        }
        if (data.isLoading()) {
            g.drawCenteredString(font, Component.translatable("voxymap.ui.updating", data.getProgress()), width / 2, 24, 0xFFAA00);
        }
        if (data.isStyleChanging()) {
            g.drawCenteredString(font, Component.translatable("voxymap.ui.styleChanging"), width / 2, 24, 0xFFFF80);
        }
    }

    /** Layer 4: cursor coordinates and the key hint, above the labels but below the buttons. */
    private void drawReadouts(GuiGraphics g, int mouseX, int mouseY) {
        if (VoxyMapConfig.SHOW_COORDINATES.get()) {
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
            String coords = String.format(Locale.ROOT, "X %.0f  Y %d  Z %.0f", wx, gy, wz) + biomeSuffix;
            g.drawCenteredString(font, coords, width / 2, height - 16, 0xFFFFFF);
        }

        if (VoxyMapConfig.SHOW_HINT.get()) {
            g.drawCenteredString(font, Component.translatable("voxymap.ui.hint",
                    VoxyMapMod.openKey != null ? VoxyMapMod.openKey.getTranslatedKeyMessage() : Component.literal("N"),
                    VoxyMapMod.centerKey != null ? VoxyMapMod.centerKey.getTranslatedKeyMessage() : Component.literal("Space")),
                    width / 2, 8, 0xFFFFFF);
        }
    }

    /** Layer 5: buttons and their dropdown, above every readout but below the modal dialog. */
    private void drawButtons(GuiGraphics g, MapData data) {
        // map scale slider (top-right, above the palette dropdown); uses the same panel as the buttons
        boolean scaleEnabled = !data.isLoading();
        Component scaleLabel = Component.translatable("voxymap.ui.mapScale");
        String scaleValue = scaleValueText();
        int panelLeft = scalePanelLeft();
        int panelRight = width - 8;
        int rowTop = SCALE_ROW_Y;
        g.fill(panelLeft, rowTop, panelRight, rowTop + 20, 0xC0202020);
        g.drawString(font, scaleLabel, panelLeft + 6, rowTop + 6, scaleEnabled ? 0xFFE0E0E0 : 0xFF808080, false);
        int sliderX = scaleSliderX();
        int sliderRight = sliderX + SCALE_SLIDER_W;
        int trackY = rowTop + 8;
        g.fill(sliderX, trackY, sliderRight, trackY + 4, scaleEnabled ? 0xFF3A3A3A : 0xFF242424);
        int knobX = scaleKnobX();
        g.fill(sliderX, trackY, knobX + 5, trackY + 4, scaleEnabled ? 0xFF8FA8C8 : 0xFF4A4A4A);
        g.fill(knobX, trackY - 4, knobX + 10, trackY + 8, scaleEnabled ? 0xFFE6E6E6 : 0xFF707070);
        g.drawString(font, scaleValue, sliderRight + 8, rowTop + 6, scaleEnabled ? 0xFFE0E0E0 : 0xFF808080, false);

        // style selector button (top-right) with a dropdown menu
        Component styleLabel = Component.translatable("voxymap.ui.styleButton", styleName(data.getStyle()));
        int selW = font.width(styleLabel) + 28;
        int selX = width - 8 - selW;
        int selY = STYLE_ROW_Y;
        g.fill(selX, selY, selX + selW, selY + 20, 0xC0202020);
        g.drawString(font, styleLabel, selX + 6, selY + 4, 0xFFE0E0E0, false);
        g.drawString(font, Component.literal("v"), selX + selW - 14, selY + 4, 0xFFB0B0B0, false);
        if (styleMenuOpen) {
            int rowH = 20;
            int menuW = 0;
            for (String st : MapStyles.NAMES) {
                menuW = Math.max(menuW, font.width(styleName(st)) + 24);
            }
            int menuX = width - 8 - menuW;
            int menuY = selY + 24;
            g.fill(menuX, menuY, menuX + menuW, menuY + 6 * rowH + 4, 0xF0121212);
            g.fill(menuX, menuY, menuX + menuW, menuY + 1, 0xFF404040);
            for (int k = 0; k < MapStyles.NAMES.length; k++) {
                String st = MapStyles.NAMES[k];
                boolean sel = st.equals(data.getStyle());
                int ry = menuY + 2 + k * rowH;
                if (sel) g.fill(menuX, ry, menuX + menuW, ry + rowH, 0xE02A3A50);
                g.drawString(font, styleName(st), menuX + 6, ry + 6, sel ? 0xFFFFFF : 0xFFC0C0C0, false);
            }
        }

        // biome / render distance toggles bottom-left
        boolean biomesOn = data.isBiomeOverlay();
        boolean distanceOn = data.isRenderDistanceOverlay();
        Component biomeText = Component.translatable("voxymap.ui.biomes");
        int bw = font.width(biomeText) + 12;
        int bx = 8;
        int by = height - 30;
        g.fill(bx, by, bx + bw, by + 20, biomesOn ? 0xC0405020 : 0xC0202020);
        g.drawString(font, biomeText, bx + 6, by + 4, biomesOn ? 0xFFFFFF : 0xFFE0E0E0, false);
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int rx2 = bx;
        int ry2 = by - 24;
        g.fill(rx2, ry2, rx2 + rdw, ry2 + 20, distanceOn ? 0xC0405020 : 0xC0202020);
        g.drawString(font, rdText, rx2 + 6, ry2 + 4, distanceOn ? 0xFFFFFF : 0xFFE0E0E0, false);

        // refresh button bottom-right
        Component refresh = MapData.INSTANCE.isLoading()
                ? Component.translatable("voxymap.ui.refreshing")
                : Component.translatable("voxymap.ui.refresh");
        int rw = font.width(refresh) + 12;
        int rx = width - 8 - rw;
        g.fill(rx, height - 30, width - 8, height - 10, 0xC0202020);
        g.drawString(font, refresh, rx + 6, height - 26, 0xFFE0E0E0, false);

        // config button top-left
        Component cfg = Component.translatable("voxymap.ui.config");
        int cw = font.width(cfg) + 12;
        g.fill(8, 8, 8 + cw, 28, 0xC0202020);
        g.drawString(font, cfg, 14, 12, 0xFFE0E0E0, false);
    }

    private void drawRenderDistanceBox(GuiGraphics g) {
        var player = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        if (player == null || !data.isReady()) return;
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

    private void drawLodWarning(GuiGraphics g) {
        DialogBox box = dialogBox();
        g.fill(0, 0, width, height, 0x99000000);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), 0xF0181818);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + 1, 0xFF606060);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.title"),
                width / 2, box.y() + 10, 0xFFFF80);
        int ty = box.y() + 10 + font.lineHeight + 8;
        for (FormattedCharSequence line : box.lines()) {
            g.drawString(font, line, width / 2 - font.width(line) / 2, ty, 0xFFE0E0E0, false);
            ty += font.lineHeight;
        }
        g.fill(box.firstBtnX(), box.btnY(), box.firstBtnX() + box.btnW(), box.btnY() + box.btnH(), 0xFF3A2A2A);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.refreshNow"),
                box.firstBtnX() + box.btnW() / 2, box.btnY() + 6, 0xFFE0E0E0);
        g.fill(box.secondBtnX(), box.btnY(), box.secondBtnX() + box.btnW(), box.btnY() + box.btnH(), 0xFF2A3A2A);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.wait"),
                box.secondBtnX() + box.btnW() / 2, box.btnY() + 6, 0xFFE0E0E0);
    }


    private void drawBiomeLabels(GuiGraphics g) {
        MapData data = MapData.INSTANCE;
        if (!data.isReady()) return;
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
            g.pose().pushPose();
            g.pose().translate((float) sx, (float) sy, 0f);
            g.pose().scale(labelScale, labelScale, 1f);
            if (VoxyMapConfig.BIOME_LABEL_BACKGROUND.get()) {
                int bx0 = -info.width / 2 - 2;
                int by0 = -font.lineHeight / 2 - 1;
                g.fill(bx0, by0, bx0 + info.width + 4, by0 + font.lineHeight + 2, 0x80000000);
            }
            g.drawString(font, info.text, -(int) (info.width / 2f), -font.lineHeight / 2, 0xFFFFFF, true);
            g.pose().popPose();
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

    private void drawContextMenu(GuiGraphics g) {
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
        g.fill(x, y, x + w, y + h, 0xF0181818);
        g.fill(x, y, x + w, y + 1, 0xFF505050);
        g.drawString(font, copy, x + 8, y + 6, 0xFFE0E0E0, false);
        int row = 1;
        if (teleport) {
            g.drawString(font, tp, x + 8, y + 6 + rowH * row, 0xFFE0E0E0, false);
            row++;
        }
        g.drawString(font, exp, x + 8, y + 6 + rowH * row, 0xFFE0E0E0, false);
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
                    mc.player.displayClientMessage(Component.translatable(
                            ok ? "voxymap.ui.exported" : "voxymap.ui.export_failed", out.toString()), false);
                }
            });
        });
    }

    private void openConfig() {
        var container = net.neoforged.fml.ModList.get().getModContainerById("voxymap");
        container.ifPresent(mod -> Minecraft.getInstance().setScreen(
                new net.neoforged.neoforge.client.gui.ConfigurationScreen(mod, this)));
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE
                || (VoxyMapMod.openKey != null && VoxyMapMod.openKey.matches(keyCode, scanCode))) {
            onClose();
            return true;
        }
        if (VoxyMapMod.centerKey != null && VoxyMapMod.centerKey.matches(keyCode, scanCode)) {
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        MapData data = MapData.INSTANCE;
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
                styleMenuOpen = false;
                return true;
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
        return super.mouseClicked(mx, my, button);
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
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (sliderDragging) {
            sliderValue = scaleSliderValueAt(mx);
            return true;
        }
        if (dragging) {
            centerX -= dx * MapData.INSTANCE.getPixelBlocks() / zoom;
            centerZ -= dy * MapData.INSTANCE.getPixelBlocks() / zoom;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
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
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
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
