package com.voxymap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Fullscreen map: N to open, Esc/N to close, scroll to zoom, drag to pan,
 * bottom-left style switcher, bottom-centre cursor coordinates, right-click
 * context menu (copy coords / OP teleport), bottom-right manual refresh.
 */
public class MapScreen extends Screen {
    private static final double ZOOM_MIN = 0.04;
    private static final double ZOOM_MAX = 8.0;

    private double centerX;
    private double centerZ;
    private double zoom = 0.5;
    private boolean dragging;
    private int menuX, menuY, menuWorldX, menuWorldZ, menuWorldY;
    private boolean menuOpen;
    private boolean linearFilter = true;
    private static boolean biomeOverlay;
    private static boolean biomeInitialized;
    private boolean styleMenuOpen;
    private boolean lodWarning;
    private static boolean renderDistanceOverlay;
    private static boolean renderDistanceInitialized;
    private static final ResourceLocation ARROW_CLEAN = ResourceLocation.fromNamespaceAndPath("voxymap", "textures/gui/arrow_clean.png");
    private static final ResourceLocation ARROW_CLASSIC = ResourceLocation.fromNamespaceAndPath("voxymap", "textures/gui/arrow_classic.png");

    private static Component styleName(String st) {
        return Component.translatable("voxymap.style." + st);
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
        MapData.INSTANCE.ensureMainTextures();
        if (!biomeInitialized) {
            biomeOverlay = VoxyMapConfig.SHOW_BIOMES.get();
            biomeInitialized = true;
        }
        if (!renderDistanceInitialized) {
            renderDistanceOverlay = VoxyMapConfig.SHOW_RENDER_DISTANCE.get();
            renderDistanceInitialized = true;
        }
        var p0 = Minecraft.getInstance().player;
        if (MapData.INSTANCE.isInitialRefreshPending() && MapData.INSTANCE.isNewWorldNow()) {
            lodWarning = true;
        }
        if (p0 != null) {
            centerX = p0.getX();
            centerZ = p0.getZ();
        }
        MapData data = MapData.INSTANCE;
        if (!data.isReady() && !data.isLoading()) {
            data.updateAsync();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
        boolean useSmall = zoom * uiScale < 0.5;
        ResourceLocation tex = useSmall ? data.getTextureSmall() : data.getTexture();
        if (tex != null && data.isReady()) {
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
            if (biomeOverlay) data.ensureBiomeTexture();
            if (biomeOverlay && data.getBiomeTexture() != null) {
                var bt = Minecraft.getInstance().getTextureManager().getTexture(data.getBiomeTexture());
                if (bt != null) bt.setFilter(false, false);
                g.blit(data.getBiomeTexture(), (int) Math.round(x0), (int) Math.round(y0),
                        Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)),
                        0, 0, data.getMapWidth(), data.getMapHeight(), data.getMapWidth(), data.getMapHeight());
            }
        } else {
            Component msg = data.isLoading()
                    ? Component.translatable("voxymap.ui.loading", data.getProgress())
                    : (data.getError().isEmpty() ? Component.translatable("voxymap.ui.no_data") : Component.literal(data.getError()));
            g.drawCenteredString(font, msg, width / 2, height / 2 - 10, 0xFFFFFF);
        }

        drawPlayerArrow(g);
        drawHud(g, mouseX, mouseY);
        // labels only when the map is at or above point-to-point, otherwise they cost more than they show
        if (biomeOverlay && zoom * uiScale >= 1.0) drawBiomeLabels(g);

        if (data.isLoading()) {
            g.drawCenteredString(font, Component.translatable("voxymap.ui.updating", data.getProgress()), width / 2, 24, 0xFFAA00);
        }
        if (data.isStyleChanging()) {
            g.drawCenteredString(font, Component.translatable("voxymap.ui.styleChanging"), width / 2, 24, 0xFFFF80);
        }

        if (menuOpen) {
            drawContextMenu(g);
        }
        if (renderDistanceOverlay) {
            drawRenderDistanceBox(g);
        }
        if (lodWarning) {
            drawLodWarning(g);
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

    private void drawHud(GuiGraphics g, int mouseX, int mouseY) {
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

        // style selector button (top-right) with a dropdown menu
        MapData md2 = MapData.INSTANCE;
        Component styleLabel = Component.translatable("voxymap.ui.styleButton", styleName(md2.getStyle()));
        int selW = font.width(styleLabel) + 28;
        int selX = width - 8 - selW;
        int selY = 8;
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
                boolean sel = st.equals(md2.getStyle());
                int ry = menuY + 2 + k * rowH;
                if (sel) g.fill(menuX, ry, menuX + menuW, ry + rowH, 0xE02A3A50);
                g.drawString(font, styleName(st), menuX + 6, ry + 6, sel ? 0xFFFFFF : 0xFFC0C0C0, false);
            }
        }

        // biome / render distance toggles bottom-left
        Component biomeText = Component.translatable("voxymap.ui.biomes");
        int bw = font.width(biomeText) + 12;
        int bx = 8;
        int by = height - 30;
        g.fill(bx, by, bx + bw, by + 20, biomeOverlay ? 0xC0405020 : 0xC0202020);
        g.drawString(font, biomeText, bx + 6, by + 4, biomeOverlay ? 0xFFFFFF : 0xFFE0E0E0, false);
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int rx2 = bx;
        int ry2 = by - 24;
        g.fill(rx2, ry2, rx2 + rdw, ry2 + 20, renderDistanceOverlay ? 0xC0405020 : 0xC0202020);
        g.drawString(font, rdText, rx2 + 6, ry2 + 4, renderDistanceOverlay ? 0xFFFFFF : 0xFFE0E0E0, false);

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

        if (VoxyMapConfig.SHOW_HINT.get()) {
            g.drawCenteredString(font, Component.translatable("voxymap.ui.hint",
                    VoxyMapMod.openKey != null ? VoxyMapMod.openKey.getTranslatedKeyMessage() : Component.literal("N"),
                    VoxyMapMod.centerKey != null ? VoxyMapMod.centerKey.getTranslatedKeyMessage() : Component.literal("Space")),
                    width / 2, 8, 0xFFFFFF);
        }
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

    private void drawLodWarning(GuiGraphics g) {
        int bw = 420, bh = 120;
        int bx = width / 2 - bw / 2;
        int by = height / 2 - bh / 2;
        g.fill(0, 0, width, height, 0x99000000);
        g.fill(bx, by, bx + bw, by + bh, 0xF0181818);
        g.fill(bx, by, bx + bw, by + 1, 0xFF606060);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.title"), width / 2, by + 10, 0xFFFF80);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.message"), width / 2, by + 34, 0xFFE0E0E0);
        int btnW = 170, btnH = 20;
        int b1x = width / 2 - btnW - 10;
        int b2x = width / 2 + 10;
        int btnY = by + bh - btnH - 12;
        g.fill(b1x, btnY, b1x + btnW, btnY + btnH, 0xFF3A2A2A);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.refreshNow"), b1x + btnW / 2, btnY + 6, 0xFFE0E0E0);
        g.fill(b2x, btnY, b2x + btnW, btnY + btnH, 0xFF2A3A2A);
        g.drawCenteredString(font, Component.translatable("voxymap.ui.lodWarning.wait"), b2x + btnW / 2, btnY + 6, 0xFFE0E0E0);
    }


    private void drawBiomeLabels(GuiGraphics g) {
        MapData data = MapData.INSTANCE;
        if (!data.isReady()) return;
        // two discrete label sizes only on very large GUI scales (4x+); otherwise default size
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float labelScale = (guiScale >= 4.0 && zoom < 1.0) ? 0.5f : 1.0f;
        for (int[] l : data.getBiomeLabels()) {
            double sx = width / 2.0 + (l[0] - centerPx()) * zoom;
            double sy = height / 2.0 + (l[1] - centerPz()) * zoom;
            if (sx < -120 || sx > width + 120 || sy < -20 || sy > height + 20) continue;
            String key = data.getBiomeTranslationKey(l[2]);
            Component comp;
            if (!key.isEmpty()) {
                comp = Component.translatable(key);
            } else {
                String fallback = data.getBiomeFallbackName(l[2]);
                if (fallback == null) continue;
                comp = Component.literal(fallback);
            }
            int tw = font.width(comp);
            g.pose().pushPose();
            g.pose().translate((float) sx, (float) sy, 0f);
            g.pose().scale(labelScale, labelScale, 1f);
            if (VoxyMapConfig.BIOME_LABEL_BACKGROUND.get()) {
                int bx0 = -tw / 2 - 2;
                int by0 = -font.lineHeight / 2 - 1;
                g.fill(bx0, by0, bx0 + tw + 4, by0 + font.lineHeight + 2, 0x80000000);
            }
            g.drawString(font, comp, -(int) (tw / 2f), -font.lineHeight / 2, 0xFFFFFF, true);
            g.pose().popPose();
        }
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        MapData data = MapData.INSTANCE;
        if (lodWarning && button == 0) {
            int bw = 420, bh = 120;
            int by = height / 2 - bh / 2;
            int btnW = 170, btnH = 20;
            int b1x = width / 2 - btnW - 10;
            int b2x = width / 2 + 10;
            int btnY = by + bh - btnH - 12;
            if (mx >= b1x && mx <= b1x + btnW && my >= btnY && my <= btnY + btnH) {
                data.skipInitialDelay();
                data.updateAsync(true);
                lodWarning = false;
                return true;
            }
            if (mx >= b2x && mx <= b2x + btnW && my >= btnY && my <= btnY + btnH) {
                // cancel: close the map screen
                lodWarning = false;
                onClose();
                return true;
            }
            return true;
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
        int selY2 = 8;
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
                    if (!data.isStyleChanging()) data.setStyle(MapStyles.NAMES[k]);
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
            biomeOverlay = !biomeOverlay;
            return true;
        }
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int rx2 = bx;
        int ry2 = by - 24;
        if (button == 0 && mx >= rx2 && mx <= rx2 + rdw && my >= ry2 && my <= ry2 + 20) {
            renderDistanceOverlay = !renderDistanceOverlay;
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
        if (dragging) {
            centerX -= dx * MapData.INSTANCE.getPixelBlocks() / zoom;
            centerZ -= dy * MapData.INSTANCE.getPixelBlocks() / zoom;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
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
