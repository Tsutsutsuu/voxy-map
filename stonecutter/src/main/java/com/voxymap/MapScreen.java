package com.voxymap;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
    /** pitch used for "straight down" (entering 3D, centring on the player, exit animation) */
    private static final float PITCH_STRAIGHT_DOWN = (float) Math.toRadians(-89.0);
    /** Top-right rows: map scale slider above the palette dropdown. */
    private static final int SCALE_ROW_Y = 8;
    private static final int STYLE_ROW_Y = 32;
    private static final int SCALE_SLIDER_W = 56;
    private static final int SCALE_KNOB_W = 6;

    // ---- view animation timing: one reference duration and one curve for everything ----
    /**
     * Reference duration of the view animations. The pre-existing 3D exit rotation used 0.22 s,
     * so the Space centring animation uses the same value instead of inventing its own speed.
     */
    private static final float VIEW_ANIM_SECONDS = 0.22f;
    /** The map open/close zoom used these durations before; they stay, only the curve is shared. */
    private static final float OPEN_ANIM_SECONDS = 0.18f;
    private static final float CLOSE_ANIM_SECONDS = 0.15f;

    /**
     * Cubic ease-out shared by every view animation (open, close, 3D exit, Space centring):
     * fast start, gentle landing. Replaces the old mixed linear / quadratic curves.
     */
    private static float easeOutCubic(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private double centerX;
    private double centerZ;
    private double zoom = 0.5;
    private int lastViewCenterTx = Integer.MIN_VALUE;
    private int lastViewCenterTz = Integer.MIN_VALUE;
    private double lastViewZoom = -1.0;
    private long lastViewCancelMs;
    private boolean dragging;
    private int menuX, menuY, menuWorldX, menuWorldZ, menuWorldY;
    private boolean menuOpen;
    /** 3D height-field view toggle. */
    private boolean view3d;
    private Map3D.Camera cam3d;
    /** dedicated background pool for PNG export */
    private static final java.util.concurrent.ExecutorService ASYNC_3D =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "voxymap-3d-async");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private float fov3d = 55f;
    /** 2D zoom captured when entering 3D from the 2D view; -1 when unknown (camera restored). */
    private double zoomAt3dEnter = -1.0;
    /** camera distance at that moment, used to detect "the player never zoomed while in 3D". */
    private float distanceAt3dEnter = -1f;
    private int labels3dDrawn;
    private int labels3dTotal;
    /** middle-drag started in 2D: switch to 3D on the first move and keep orbiting */
    private boolean pending3dOrbit;
    /** middle press while in 3D: a short click goes back to 2D, a long press / drag orbits. */
    private boolean middlePress3d;
    private boolean middleDragged3d;
    private double middlePressX;
    private double middlePressY;
    private long middlePressAtMs;
    /** middle press while in 2D: a short click enters 3D, a drag enters and orbits. */
    private boolean middlePress2d;
    private double middlePress2dX;
    private double middlePress2dY;
    private double middlePress2dAtMs;
    /** A middle click (switch) must be short and must not move; anything longer is an orbit. */
    private static final long MIDDLE_CLICK_MAX_MS = 300L;
    private static final double MIDDLE_CLICK_SLOP_PX = 4.0;
    /**
     * Space centring is animated in both views. {@code < 0} means idle; otherwise the view centre
     * (2D pan) or the 3D camera target/rotation travels from "from" to "to".
     */
    private float centerAnimT = -1f;
    private boolean centerAnim3d;
    private double centerAnimFromX;
    private double centerAnimFromZ;
    private double centerAnimToX;
    private double centerAnimToZ;
    private double centerAnimFromY;
    private double centerAnimToY;
    private float centerAnimFromYaw;
    private float centerAnimYawDelta;
    private float centerAnimFromPitch;
    private float centerAnimToPitch;
    /**
     * Scroll zoom is interpolated instead of stepping: the wheel only moves the target, the visible
     * zoom eases towards it. In 2D the world point under the cursor is pinned while it eases; in 3D
     * the orbit distance is eased between the configured limits.
     */
    private boolean zoomSmoothing;
    private double targetZoom = -1.0;
    private double zoomAnchorWorldX;
    private double zoomAnchorWorldZ;
    private double zoomAnchorScreenX;
    private double zoomAnchorScreenY;
    private float targetDistance3d = -1f;
    /** ~0.15 s to reach 90% of a wheel step, frame-rate independent. */
    private static final float ZOOM_EASE_PER_SECOND = 16f;
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
    private void drawToggle(GuiGraphicsExtractor g, String key, int x, int y, int w, Component label,
                            boolean on, boolean hovered, boolean enabled) {
        if (!enabled) {
            // same disabled palette as the regular buttons
            g.fill(x, y, x + w, y + 20, COL_PANEL_OFF);
            g.fill(x, y, x + w, y + 1, COL_BORDER_OFF);
            g.text(font, label, x + 6, y + 6, COL_TEXT_OFF);
            return;
        }
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

    /** Used by the GPU 3D renderer (called from GuiRenderer before the GUI batch). */
    public boolean isGpu3dView() {
        return view3d;
    }

    public Map3D.Camera gpu3dCamera() {
        return cam3d;
    }

    @Override
    protected void init() {
        // init() also runs when this screen is resumed from the config screen. The 3D camera and
        // the GPU terrain survive that round trip (removed() only restores the process-wide
        // overrides), so only the transition state is reset here.
        openT = 0f;
        closing = false;
        closeT = 0f;
        // never carry a half-finished gesture or a centring animation across screens
        cancelCenterAnim();
        pending3dOrbit = false;
        middlePress2d = false;
        middlePress3d = false;
        middleDragged3d = false;
        // load this world's per-world UI state before deciding whether to reopen in 3D. It used to be
        // loaded from the async map build only, so a new/slow world could inherit the previous
        // world's 3D mode and camera (and then write those values into the previous world's state
        // file while its storage path was still the current one).
        MapData data0 = MapData.INSTANCE;
        data0.loadCurrentWorldUiState();
        // restore this world's last 3D mode if the player asked to remember it
        if (!view3d && VoxyMapConfig.REMEMBER_3D_VIEW.get() && data0.getView3d()) {
            toggle3D(true);
        } else if (view3d) {
            // Resumed from another screen while 3D was still active: removed() had to restore the
            // process-wide overrides for that screen, so re-apply the 3D-only ones here.
            apply3dOverrides();
        }
        // restore the last zoom for this world (or the configured default)
        zoom = VoxyMapConfig.REMEMBER_ZOOM.get() ? MapData.INSTANCE.getZoom() : VoxyMapConfig.DEFAULT_ZOOM.get();
        if (zoom <= 0) zoom = VoxyMapConfig.DEFAULT_ZOOM.get();
        // a fresh screen starts from a settled zoom, never from a previous screen's easing
        zoomSmoothing = false;
        targetZoom = zoom;
        targetDistance3d = -1f;
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
        boolean waitingForSeedGen = !data.isTile2DMode() && data.isInitialRefreshPending() && data.isNewWorldNow();
        lodWarning = waitingForSeedGen;
        if (p0 != null) {
            centerX = p0.getX();
            centerZ = p0.getZ();
        }
        if (waitingForSeedGen) {
            data.onMapOpened();
            return;
        }
        if (data.needsWorldReload()) {
            // the grid kept from the previous world must not be shown here
            data.dropSnapshotForWorldChange();
        }
        data.onMapOpened();
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
            // the SeedGen delay expired while the map stayed open: start the fast build now
            data.skipInitialDelay();
            if (!data.isLoading()) {
                data.updateAsync(true, MapData.RefreshTrigger.OPEN_MAP);
            }
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
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!view3d) super.extractBackground(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long nowNs = System.nanoTime();
        frameDt = lastAnimNs == 0 ? 0.016f : (nowNs - lastAnimNs) / 1_000_000_000f;
        lastAnimNs = nowNs;
        // Keep the overlay/picking FOV identical to the value Gpu3DMap reads every frame for the
        // terrain pass: a FOV change made in the config screen must not desync the two.
        syncFov3d();
        // The GPU 3D view owns the full-screen background (and the world is skipped); drawing the
        // normal opaque map background here would cover the GPU pass.
        if (!view3d) {
            g.fill(0, 0, width, height, 0xFF0A0A0A);
        }
        MapData data = MapData.INSTANCE;
        if (!data.isOverworld()) {
            // nether / end: no map is built or drawn, only the notice
            drawStatusLine(g, Component.translatable("voxymap.ui.overworldOnly"), 24, COL_WARN);
            return;
        }
        // open/close animation (time based): keep the original quadratic ease-out here, it was
        // tuned for the map appearing/disappearing - the shared cubic curve is only for view moves.
        if (!closing) {
            openT = Math.min(1f, openT + frameDt / OPEN_ANIM_SECONDS);
        } else {
            closeT = Math.min(1f, closeT + frameDt / CLOSE_ANIM_SECONDS);
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

        // Space centring: animate before the camera/map are read for this frame
        advanceCenterAnim();
        // scroll zoom: ease the visible zoom / camera distance towards the wheel target
        advanceZoomSmoothing();

        // 3D -> 2D exit animation: ease the rotation back to straight down, then really switch
        if (exit3dT >= 0f && cam3d != null) {
            exit3dT = Math.min(1f, exit3dT + frameDt / VIEW_ANIM_SECONDS);
            float et = easeOutCubic(exit3dT);
            cam3d.yaw = exitFromYaw * (1f - et);
            float targetPitch = (float) Math.toRadians(-89.0);
            cam3d.pitch = exitFromPitch + (targetPitch - exitFromPitch) * et;
            if (exit3dT >= 1f) {
                finish3dExit();
            }
        }
        // draw order is the stacking order, bottom to top:
        //   1 map, 2 map overlays, 3 biome labels, 4 readouts/status text, 5 buttons, 6 dialog
        if (view3d) {
            if (data.isBiomeOverlay()) {
                data.ensureBiomeLabelsGlobal();
            }
            pump3DTiles(data);
            Gpu3DMap.tick(data, cam3d);
            drawPlayerMarker3d(g);
            // The 3D render-distance overlay is not drawn here: Gpu3DMap's overlay pass builds the
            // yellow terrain ribbon with the same camera/projection as the GPU terrain itself.
            // the label blockers (buttons, plates) are the same controls in 3D; without this the
            // 3D labels could be drawn on top of them because the 2D branch never ran
            collectLabelBlockers(mouseX, mouseY);
            drawBiomeLabels3d(g);
        } else {
            drawMap(g, data, uiScale);
            drawMapOverlays(g, data);
        }
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
            if (!view3d && data.isBiomeOverlay() && zoom * uiScale >= 1.0) {
                collectLabelBlockers(mouseX, mouseY);
                drawBiomeLabels(g);
            }
            if (view3d) {
                if (data.getMapWidth() <= 0) drawStatusText(g, data);
            } else {
                drawStatusText(g, data);
            }
            drawReadouts(g, mouseX, mouseY);
            drawButtons(g, data, mouseX, mouseY);
            drawHintStrip(g);
            if (contextMenuT > 0.02f) {
                drawContextMenu(g, mouseX, mouseY);
            }
        }
        // the modal dialogs always win, whatever the map state is
        if (lodWarning) {
            drawLodWarning(g, mouseX, mouseY);
        } else if (MapData.INSTANCE.oversizePixels > 0) {
            drawOversizeWarning(g, mouseX, mouseY);
        }
        g.pose().popMatrix();
    }

    /** Layer 1: the unified tile map. */
    private void drawMap(GuiGraphicsExtractor g, MapData data, double uiScale) {
        drawUnifiedTiles(g, data, uiScale);
    }

    /** Migration path: one store renders both the seeded base tiles and live-updated tiles. */
    private void maybeCancelStaleSource(LiveTileStore live) {
        int cx = live.tileIndexX(centerX);
        int cz = live.tileIndexZ(centerZ);
        long now = System.currentTimeMillis();
        boolean moved = cx != lastViewCenterTx || cz != lastViewCenterTz;
        boolean zoomed = lastViewZoom > 0 && Math.abs(zoom - lastViewZoom) / lastViewZoom > 0.15;
        if ((moved || zoomed) && now - lastViewCancelMs > 250L) {
            live.cancelPendingSourceRequests();
            lastViewCenterTx = cx;
            lastViewCenterTz = cz;
            lastViewZoom = zoom;
            lastViewCancelMs = now;
        }
    }

    private void drawUnifiedTiles(GuiGraphicsExtractor g, MapData data, double uiScale) {
        LiveTileStore live = data.getLiveTiles();
        if (live == null) return;
        MapPerf.INSTANCE.beginFrame2d();
        live.beginFrame();
        int pb = Math.max(1, data.getPixelBlocks());
        double worldPerScreenPx = pb / Math.max(1.0e-6, zoom);
        double left = centerX - (width / 2.0) * worldPerScreenPx;
        double right = centerX + (width / 2.0) * worldPerScreenPx;
        double top = centerZ - (height / 2.0) * worldPerScreenPx;
        double bottom = centerZ + (height / 2.0) * worldPerScreenPx;
        int tx0 = live.tileIndexX(left), tx1 = live.tileIndexX(right);
        int tz0 = live.tileIndexZ(top), tz1 = live.tileIndexZ(bottom);
        maybeCancelStaleSource(live);
        String style = data.getStyle();
        int sea = data.seaLevel();
        int ccx = live.tileIndexX(centerX), ccz = live.tileIndexZ(centerZ);
        int maxR = Math.max(Math.max(Math.abs(tx0 - ccx), Math.abs(tx1 - ccx)),
                Math.max(Math.abs(tz0 - ccz), Math.abs(tz1 - ccz)));
        // Seed/draw from the viewport centre outwards: with asynchronous seed creation this keeps
        // the player's immediate area sharp instead of filling top-to-bottom.
        for (int r = 0; r <= maxR; r++) {
            for (int tx = ccx - r; tx <= ccx + r; tx++) {
                drawUnifiedTile(g, data, live, tx, ccz - r, style, sea, pb, tx0, tx1, tz0, tz1);
                if (r != 0) drawUnifiedTile(g, data, live, tx, ccz + r, style, sea, pb, tx0, tx1, tz0, tz1);
            }
            for (int tz = ccz - r + 1; tz <= ccz + r - 1; tz++) {
                drawUnifiedTile(g, data, live, ccx - r, tz, style, sea, pb, tx0, tx1, tz0, tz1);
                if (r != 0) drawUnifiedTile(g, data, live, ccx + r, tz, style, sea, pb, tx0, tx1, tz0, tz1);
            }
        }
        int viewRadius = Math.max(tx1 - tx0, tz1 - tz0) / 2 + 4;
        live.endTextureFrame();
        int cap = Math.max(256, VoxyMapConfig.TILE_RESIDENT_CAP.get());
        live.trimResident(cap, Math.max(256, cap * 7 / 10));
        MapPerf.INSTANCE.endFrame2d((tx1 - tx0 + 1) * (tz1 - tz0 + 1), live.tileCount());
    }

    private void drawUnifiedTile(GuiGraphicsExtractor g, MapData data, LiveTileStore live,
                                 int tx, int tz, String style, int sea, int pb,
                                 int tx0, int tx1, int tz0, int tz1) {
        if (tx < tx0 || tx > tx1 || tz < tz0 || tz > tz1) return;
        LiveTileStore.LiveTile tile = live.get(tx, tz);
        if (tile == null || tile.seedEpoch < 0) {
            // Event-created tiles exist before the first seed pass; make sure they are
            // upgraded to the base snapshot instead of staying as coarse live-only patches.
            tile = live.getOrCreateSeed(tx, tz);
        }
        if (tile == null) return;
        Identifier tex = live.textureFor(tile, style, sea, data.getColorEpoch());
        if (tex == null) return;
        MapPerf.INSTANCE.tileDrawn();
        double sx = width / 2.0 + (tile.minWX - centerX) * zoom / pb;
        double sy = height / 2.0 + (tile.minWZ - centerZ) * zoom / pb;
        double sw = LiveTileStore.TILE_PIXELS * zoom;
        double sh = LiveTileStore.TILE_PIXELS * zoom;
        if (sx + sw < 0 || sy + sh < 0 || sx > width || sy > height) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) sx, (float) sy);
        pose.scale((float) zoom, (float) zoom);
        g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, tex,
                0, 0, 0f, 0f, LiveTileStore.TILE_PIXELS, LiveTileStore.TILE_PIXELS,
                LiveTileStore.TILE_PIXELS, LiveTileStore.TILE_PIXELS,
                LiveTileStore.TILE_PIXELS, LiveTileStore.TILE_PIXELS);
        pose.popMatrix();
    }

    /** Layer 2: things drawn on top of the map - player marker, render-distance box. */
    private void drawMapOverlays(GuiGraphicsExtractor g, MapData data) {
        if (data.isBiomeOverlay()) {
            data.ensureBiomeLabelsGlobal();
        }
        drawPlayerArrow(g);
        if (data.isRenderDistanceOverlay()) {
            drawRenderDistanceBox(g);
        }
    }

    private void drawPlayerArrow(GuiGraphicsExtractor g) {
        var player = Minecraft.getInstance().player;
        MapData data = MapData.INSTANCE;
        if (player == null) return;
        float cx = (float) (width / 2.0 + (player.getX() - centerX) * zoom / data.getPixelBlocks());
        float cy = (float) (height / 2.0 + (player.getZ() - centerZ) * zoom / data.getPixelBlocks());
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
        List<String> primary = new ArrayList<>(data.debugLines());
        List<String> secondary = new ArrayList<>(MapPerf.INSTANCE.lines(data));
        Minecraft mc = Minecraft.getInstance();
        double uiScale = mc.getWindow().getGuiScale();
        secondary.add(String.format(Locale.ROOT, "view: zoom=%.3f ui=%d fps=%d",
                zoom, (int) uiScale, mc.getFps()));
        double wx = screenToWorldX(mouseX);
        double wz = screenToWorldZ(mouseY);
        secondary.add(String.format(Locale.ROOT, "hover: %.0f %.0f h=%d b=%d",
                wx, wz, data.heightAt((int) Math.round(wx), (int) Math.round(wz)),
                data.biomeAt((int) Math.round(wx), (int) Math.round(wz))));
        secondary.add("labels: " + labelPanelState(data, zoom * uiScale) + " / "
                + (data.getBiomeLabelsFlat().length / 3) + " total");
        if (view3d) {
            primary.addAll(Gpu3DMap.debugLines());
            int[] dbgEdges = data.isBiomeOverlay() ? data.getBiomeEdgeSnapshot() : null;
            primary.add("labels3d: " + labels3dDrawn + " / " + labels3dTotal);
            primary.add("overlay: biome=" + (dbgEdges == null ? "off" : "on")
                    + " rd=" + (data.isRenderDistanceOverlay() ? "on" : "off"));
            if (cam3d != null) {
                primary.add(String.format(Locale.ROOT, "cam: t=(%.0f,%.0f,%.0f) y=%.2f p=%.2f d=%.0f",
                        cam3d.targetX, cam3d.targetY, cam3d.targetZ, cam3d.yaw, cam3d.pitch, cam3d.distance));
            }
        }
        primary.add("press Q to close");

        boolean twoColumn = uiScale >= 4.0 && width >= 900;
        if (!twoColumn) primary.addAll(secondary);
        int pad = 8;
        int gap = 12;
        int lineH = font.lineHeight + 2;
        float scale = twoColumn ? 0.5f : 1f;
        int maxLines = twoColumn ? Math.max(primary.size(), secondary.size()) : primary.size();
        int availableW = Math.max(200, width - 16);
        int maxColPx = twoColumn
                ? (int) ((availableW / (2.0 * scale)) - gap - pad)
                : (int) ((availableW / scale) - pad * 2);
        List<String> left = new ArrayList<>(primary.size());
        for (String l : primary) left.add(fitRight(l, maxColPx));
        List<String> right = new ArrayList<>(secondary.size());
        for (String l : secondary) right.add(fitRight(l, maxColPx));
        int w1 = 0, w2 = 0;
        for (String l : left) w1 = Math.max(w1, font.width(l));
        for (String l : right) w2 = Math.max(w2, font.width(l));
        int unscaledW = twoColumn ? w1 + w2 + gap + pad * 2 : Math.max(w1, w2) + pad * 2;
        // shrink slightly if the native GUI scale makes the two columns wider than the screen
        if (unscaledW * scale > availableW) scale = availableW / (float) unscaledW;
        int unscaledH = maxLines * lineH + pad * 2;
        if (unscaledH * scale > height - 40) scale = (height - 40) / (float) unscaledH;
        int pw = Math.round(unscaledW * scale);
        int ph = Math.round(unscaledH * scale);
        int x = 8;
        int y = 34;
        g.fill(x, y, x + pw, y + ph, COL_PANEL);
        g.fill(x, y, x + pw, y + 1, COL_BORDER);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + pad * scale, y + pad * scale);
        pose.scale(scale, scale);
        int ty = 0;
        for (String l : left) {
            g.text(font, l, 0, ty, COL_TEXT_DIM);
            ty += lineH;
        }
        if (twoColumn) {
            ty = 0;
            for (String l : right) {
                g.text(font, l, w1 + gap, ty, COL_TEXT_DIM);
                ty += lineH;
            }
        }
        pose.popMatrix();
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

    // ---- 2D/3D switching: always seamless, with a short linear rotation on the way back ----
    private float exit3dT = -1f;
    private float exitFromYaw;
    private float exitFromPitch;

    private void toggle3D() {
        toggle3D(false);
    }

    private void toggle3D(boolean restoreCamera) {
        MapData data = MapData.INSTANCE;
        // a second switch request while the exit animation runs must not restart / fight it
        if (exit3dT >= 0f) return;
        cancelCenterAnim();
        // settle any wheel easing at the value the player currently sees before switching views
        cancelZoomSmoothing();
        // switching views closes any open menu, whose anchor is screen-space and no longer matches
        menuOpen = false;
        styleMenuOpen = false;
        if (view3d) {
            // begin the linear rotation-then-switch animation instead of cutting straight to 2D
            if (cam3d != null) {
                exit3dT = 0f;
                exitFromYaw = cam3d.yaw;
                exitFromPitch = cam3d.pitch;
            } else {
                finish3dExit();
            }
            return;
        }
        view3d = true;
        data.setView3d(true);
        ensureCamera3d();
        var player = Minecraft.getInstance().player;
        double focusX;
        double focusZ;
        if (restoreCamera && data.hasView3dCamera()) {
            // reopening the map: restore the saved rotation/zoom and focus the player
            focusX = player != null ? player.getX() : centerX;
            focusZ = player != null ? player.getZ() : centerZ;
            cam3d.setTarget(focusX, groundMeshY(focusX, focusZ), focusZ);
            cam3d.yaw = data.getView3dYaw();
            cam3d.pitch = data.getView3dPitch();
            cam3d.distance = data.getView3dDistance();
            // There is no matching 2D zoom for a restored camera: exit recomputes it from the
            // current distance.
            zoomAt3dEnter = -1.0;
            distanceAt3dEnter = -1f;
        } else {
            // seamless switch: keep the 2D focus, match the 2D scale, look straight down
            focusX = centerX;
            focusZ = centerZ;
            cam3d.setTarget(focusX, groundMeshY(focusX, focusZ), focusZ);
            double pb = data.getPixelBlocks();
            double tanHalf = Math.tan(Math.toRadians(fov3d * 0.5));
            // 2D: world units per screen pixel = pb / zoom. 3D: the view covers
            // 2 * distance * tan(fov/2) world units vertically. Matching the two gives the distance
            // below, so entering 3D looks like the same top-down map (modulo the -89 deg pitch).
            double dist = pb * height / (2.0 * Math.max(0.05, zoom) * Math.max(1e-4, tanHalf));
            dist = Math.max(minDistance3d(), Math.min(maxDistance3d(), dist));
            cam3d.distance = (float) dist;
            // The distance may have been clamped (2D zoomed further out than MAX_DISTANCE_3D), so
            // inverting the formula on exit would not return the original zoom. Remember the pair:
            // if the player never zooms in 3D, finish3dExit() restores the exact 2D zoom.
            zoomAt3dEnter = zoom;
            distanceAt3dEnter = cam3d.distance;
            cam3d.yaw = 0f;
            cam3d.pitch = PITCH_STRAIGHT_DOWN;
        }
        // start the 3D distance easing settled on the seamless enter distance
        targetDistance3d = cam3d != null ? cam3d.distance : -1f;
        apply3dOverrides();
    }

    /** Finishes the exit: switch to 2D, match position and scale, restore overlays. */
    private void finish3dExit() {
        exit3dT = -1f;
        view3d = false;
        MapData data = MapData.INSTANCE;
        data.setView3d(false);
        if (cam3d != null) {
            centerX = cam3d.targetX;
            centerZ = cam3d.targetZ;
            if (zoomAt3dEnter > 0.0 && Math.abs(cam3d.distance - distanceAt3dEnter) < 1.0e-3f) {
                // Never zoomed while in 3D: restore the original 2D zoom. This keeps the
                // 2D -> 3D -> 2D round trip lossless even when the enter distance was clamped to
                // MAX_DISTANCE_3D.
                zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoomAt3dEnter));
            } else {
                // invert the enter formula so the 2D scale matches the 3D view
                double pb = data.getPixelBlocks();
                double tanHalf = Math.tan(Math.toRadians(fov3d * 0.5));
                double z = pb * height / (2.0 * Math.max(1.0, cam3d.distance) * Math.max(1e-4, tanHalf));
                zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
            }
        }
        zoomAt3dEnter = -1.0;
        distanceAt3dEnter = -1f;
        restore3dOverrides();
        // the 2D zoom derived from the 3D distance is settled, do not ease towards a stale target
        zoomSmoothing = false;
        targetZoom = zoom;
        targetDistance3d = -1f;
        // Keep the GPU tiles across 2D/3D toggles: re-entering 3D should not rebuild every tile.
        // They are released when the screen is really closed (finishClose) or by MapData when the
        // world changes.
        if (!VoxyMapConfig.KEEP_3D_TILES_IN_2D.get()) Gpu3DMap.clear();
        data.clearTileColorFrame();
    }

    /**
     * Undoes the process-wide 3D-only overrides (hidden contours, skipped label rebuilds) so the
     * 2D view is left in the state the player's settings describe. Called both when leaving the 3D
     * view normally and when the map screen is closed while still in 3D.
     */
    private void restore3dOverrides() {
        MapData data = MapData.INSTANCE;
        data.skipBiomeLabels = false;
        if (MapData.contourOverride != null) {
            MapData.contourOverride = null;
            data.requestColorRerender();
        }
    }

    /**
     * Applies the process-wide 3D-only overrides (no 2D label rebuilds, contours hidden). Called
     * whenever the 3D view becomes active, including on `init()` when the map is resumed from the
     * config screen while 3D was already active.
     */
    private void apply3dOverrides() {
        MapData data = MapData.INSTANCE;
        data.skipBiomeLabels = true;
        if (VoxyMapConfig.AUTO_HIDE_CONTOURS_3D.get()
                && VoxyMapConfig.DRAW_CONTOURS.get() && MapData.contourOverride == null) {
            // contours are a 2D decoration: hide them in 3D (cheap colour-only re-render)
            MapData.contourOverride = Boolean.FALSE;
            data.requestColorRerender();
        }
    }

    /**
     * Keeps overlay/picking FOV in sync with the value {@code Gpu3DMap} reads for the terrain pass
     * every frame, so a FOV change made in the config screen applies immediately and consistently.
     */
    private void syncFov3d() {
        fov3d = Math.max(30f, Math.min(110f, VoxyMapConfig.FOV_3D.get()));
    }

    /** Closest allowed 3D camera distance (tolerates a swapped min/max config). */
    private static float minDistance3d() {
        return Math.min(VoxyMapConfig.MIN_DISTANCE_3D.get(), VoxyMapConfig.MAX_DISTANCE_3D.get());
    }

    /** Farthest allowed 3D camera distance (tolerates a swapped min/max config). */
    private static float maxDistance3d() {
        return Math.max(VoxyMapConfig.MIN_DISTANCE_3D.get(), VoxyMapConfig.MAX_DISTANCE_3D.get());
    }

    private void ensureCamera3d() {
        if (cam3d != null) return;
        MapData data = MapData.INSTANCE;
        cam3d = new Map3D.Camera();
        var player = Minecraft.getInstance().player;
        double tx;
        double tz;
        if (player != null) {
            tx = player.getX();
            tz = player.getZ();
        } else {
            tx = data.getMinWX() + data.getMapWidth() * (double) data.getPixelBlocks() / 2.0;
            tz = data.getMinWZ() + data.getMapHeight() * (double) data.getPixelBlocks() / 2.0;
        }
        double ty = groundMeshY(tx, tz);
        cam3d.setTarget(tx, ty, tz);
        cam3d.distance = 1500f;
        syncFov3d();
    }

    private void pump3DTiles(MapData data) {
        if (cam3d == null || !data.isTile2DMode()) return;
        double radiusWorld = Math.max(2048.0, Math.min(8192.0, cam3d.distance * 2.0));
        int pb = Math.max(1, data.getPixelBlocks());
        int radiusTiles = (int) Math.ceil(radiusWorld / (256.0 * pb));
        data.pump3DTiles(cam3d.targetX, cam3d.targetZ, radiusTiles,
                data.getStyle(), data.seaLevel(), data.getColorEpoch());
    }

    private void drawStatusText(GuiGraphicsExtractor g, MapData data) {
        if (data.isTile2DMode()) {
            if (!data.hasLiveStore()) {
                drawStatusLine(g, Component.translatable("voxymap.ui.no_data"), 24, COL_TEXT);
            }
            return;
        }
        if (data.getMapWidth() <= 0) {
            // first build: show the same top line as a refresh, or the actual error
            Component msg = data.outOfMemory
                    ? Component.translatable("voxymap.ui.outOfMemory")
                    : (data.getError().isEmpty()
                        ? Component.translatable("voxymap.ui.no_data")
                        : Component.literal(data.getError()));
            drawStatusLine(g, msg, 24, COL_TEXT);
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
        if (view3d) {
            return Component.translatable("voxymap.ui.hint3d", openKey, centerKey);
        }
        return Component.translatable("voxymap.ui.hint", openKey, centerKey);
    }

    private int hintPanelLeft() {
        return 8 + configWidth() + 10;
    }

    private int hintPanelWidth() {
        return Math.max(90, scalePanelLeft() - 10 - hintPanelLeft());
    }

    private long hintScrollStartMs;
    private String lastHintText = "";

    /** Top control hint in the gap between the config button and the map-scale panel. */
    private void drawHintStrip(GuiGraphicsExtractor g) {
        if (!VoxyMapConfig.SHOW_HINT.get()) return;
        Component hint = hintComponent();
        String text = hint.getString();
        if (!text.equals(lastHintText)) {
            lastHintText = text;
            hintScrollStartMs = System.currentTimeMillis();
        }
        int left = hintPanelLeft();
        int panelW = hintPanelWidth();
        int textW = font.width(hint);
        int pad = 4;
        g.fill(left, 6, left + panelW, 20, 0x6614171C);
        if (textW <= panelW - pad * 2) {
            g.centeredText(font, hint, left + panelW / 2, 8, COL_TEXT);
            return;
        }
        long elapsed = System.currentTimeMillis() - hintScrollStartMs;
        int period = textW + 48;
        int offset = (int) ((elapsed * 24L / 1000L) % period);
        g.enableScissor(left + pad, 6, left + panelW - pad, 20);
        int tx = left + pad - offset;
        g.text(font, hint, tx, 8, COL_TEXT, true);
        g.text(font, hint, tx + period, 8, COL_TEXT, true);
        g.disableScissor();
    }

    /** Cursor coordinate line: world position, ground height and biome name. */
    private Component coordsComponent(int mouseX, int mouseY) {
        MapData md = MapData.INSTANCE;
        double wx;
        double wy;
        double wz;
        if (view3d) {
            double[] hit = pickWorld3d(mouseX, mouseY);
            if (hit == null) return Component.empty();
            wx = hit[0];
            wy = hit[1];
            wz = hit[2];
        } else {
            wx = screenToWorldX(mouseX);
            wz = screenToWorldZ(mouseY);
            wy = md.heightAt((int) Math.round(wx), (int) Math.round(wz));
        }
        int gy = (int) Math.round(wy);
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

    /**
     * Casts a ray from the 3D camera through the given screen point and intersects the height
     * field. Returns {@code {x, y, z}} in world coordinates, or null when the ray misses the map
     * (looking at the sky).
     */
    private double[] pickWorld3d(double sx, double sy) {
        MapData data = MapData.INSTANCE;
        if (cam3d == null || !data.isReady() || data.getMapWidth() <= 0) return null;
        double ex = cam3d.eyeX();
        double ey = cam3d.eyeY();
        double ez = cam3d.eyeZ();
        // camera basis: forward, right, up
        double fx = cam3d.targetX - ex;
        double fy = cam3d.targetY - ey;
        double fz = cam3d.targetZ - ez;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-6) return null;
        fx /= fl;
        fy /= fl;
        fz /= fl;
        double rl = Math.sqrt(fx * fx + fz * fz);
        if (rl < 1e-6) return null;
        double rx = -fz / rl;
        double rz = fx / rl;
        // up = cross(right, forward)
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;
        double ndcX = (sx / Math.max(1, width)) * 2.0 - 1.0;
        double ndcY = 1.0 - (sy / Math.max(1, height)) * 2.0;
        double aspect = (double) width / Math.max(1, height);
        double tanHalf = Math.tan(Math.toRadians(fov3d * 0.5));
        double dx = fx + rx * ndcX * tanHalf * aspect + ux * ndcY * tanHalf;
        double dy = fy + uy * ndcY * tanHalf;
        double dz = fz + rz * ndcX * tanHalf * aspect + uz * ndcY * tanHalf;
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1e-6) return null;
        dx /= dl;
        dy /= dl;
        dz /= dl;
        // first, the camera might already be under the surface (orbiting into a hill)
        if (ey <= groundMeshY(ex, ez) && cam3d.distance < 4.0) {
            return new double[]{ex, ey / meshScale(), ez};
        }
        // March only as far as the camera can see (far plane) and the map can extend; without the
        // AABB clip a ray aimed at the sky outside the map would loop until maxT and, worse, the
        // out-of-map samples below would report sea level as terrain.
        double maxT = Math.min(60000.0, Math.max(4000.0, cam3d.distance * 10.0));
        double mapWX = data.getRenderMinWX();
        double mapWZ = data.getRenderMinWZ();
        double mapWidth = data.getRenderWidthPx() * (double) data.getPixelBlocks();
        double mapHeight = data.getRenderHeightPx() * (double) data.getPixelBlocks();
        if (mapWidth > 0 && mapHeight > 0) {
            double exitT = rayBoxExit(ex, ez, dx, dz, mapWX, mapWZ, mapWX + mapWidth, mapWZ + mapHeight);
            if (exitT <= 0.0) return null;
            maxT = Math.min(maxT, exitT);
        }
        // Cap the step: at the far end of the zoom range 0.3 % of the distance exceeds the width of
        // a 1-block ridge and the ray would tunnel through it.
        double step = Math.max(1.0, Math.min(4.0, cam3d.distance * 0.003));
        double prevT = 0.0;
        int emptyCrossings = 0;
        for (double t = step; t <= maxT; t += step) {
            double px = ex + dx * t;
            double py = ey + dy * t;
            double pz = ez + dz * t;
            if (py <= groundMeshY(px, pz)) {
                // binary refine the crossing
                double lo = prevT;
                double hi = t;
                for (int i = 0; i < 14; i++) {
                    double mid = (lo + hi) * 0.5;
                    if (ey + dy * mid <= groundMeshY(ex + dx * mid, ez + dz * mid)) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                double ht = (lo + hi) * 0.5;
                double hx = ex + dx * ht;
                double hz = ez + dz * ht;
                // heightAt()/isWaterAt() return seaLevel()/false outside the mapped area and for
                // unexplored cells, so a crossing there is not real terrain: skip it (a hole inside
                // the map can still hit the ground behind it) and give up after a few holes.
                if (data.isEmptyAt((int) Math.floor(hx), (int) Math.floor(hz))) {
                    if (++emptyCrossings > 8) return null;
                    prevT = t;
                    continue;
                }
                // display the picked point in world blocks, not in the vertically stretched space
                return new double[]{hx, groundYAt(hx, hz), hz};
            }
            prevT = t;
        }
        return null;
    }

    /**
     * Distance along the horizontal ray at which it leaves the given X/Z rectangle, or a negative
     * value when the ray never enters it. Used to clip the pick march to the mapped area.
     */
    private static double rayBoxExit(double ox, double oz, double dx, double dz,
                                     double minX, double minZ, double maxX, double maxZ) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;
        if (Math.abs(dx) < 1e-9) {
            if (ox < minX || ox > maxX) return -1.0;
        } else {
            double t1 = (minX - ox) / dx;
            double t2 = (maxX - ox) / dx;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (Math.abs(dz) < 1e-9) {
            if (oz < minZ || oz > maxZ) return -1.0;
        } else {
            double t1 = (minZ - oz) / dz;
            double t2 = (maxZ - oz) / dz;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (tMax < Math.max(0.0, tMin)) return -1.0;
        return tMax;
    }

    /** Terrain (or water surface) height used by the 3D ray picker, in world blocks. */
    private double groundYAt(double wx, double wz) {
        MapData d = MapData.INSTANCE;
        int ix = (int) Math.round(wx);
        int iz = (int) Math.round(wz);
        return d.isWaterAt(ix, iz) ? d.seaLevel() : d.heightAt(ix, iz);
    }

    /** Vertical stretch applied to the mesh: the camera, picking and overlays work in this space. */
    private static double meshScale() {
        return Math.max(0.01, VoxyMapConfig.VERTICAL_SCALE_3D.get());
    }

    /** Terrain/water height in mesh space (world height multiplied by verticalScale3d). */
    private double groundMeshY(double wx, double wz) {
        return groundYAt(wx, wz) * meshScale();
    }

    // ---- Space: linear animation that centres the view on the player ----

    /**
     * Starts the linear centring animation. In 2D it moves the map centre; in 3D it moves the orbit
     * target onto the player and rotates back to "straight down", exactly like the old instant
     * behaviour but readable. Any manual pan/orbit/zoom cancels it, so input always wins.
     */
    private void startCenterAnim(double targetX, double targetZ) {
        cancelZoomSmoothing(); // the pan owns the centre while it runs
        centerAnim3d = view3d && cam3d != null;
        if (centerAnim3d) {
            centerAnimFromX = cam3d.targetX;
            centerAnimFromZ = cam3d.targetZ;
            centerAnimFromY = cam3d.targetY;
            centerAnimToY = groundMeshY(targetX, targetZ);
            centerAnimFromYaw = cam3d.yaw;
            // shortest way around, so the orbit does not spin the long way to face north
            float delta = 0f - cam3d.yaw;
            while (delta > Math.PI) delta -= 2f * (float) Math.PI;
            while (delta < -Math.PI) delta += 2f * (float) Math.PI;
            centerAnimYawDelta = delta;
            centerAnimFromPitch = cam3d.pitch;
            centerAnimToPitch = PITCH_STRAIGHT_DOWN;
        } else {
            centerAnimFromX = centerX;
            centerAnimFromZ = centerZ;
        }
        centerAnimToX = targetX;
        centerAnimToZ = targetZ;
        centerAnimT = 0f;
    }

    /** Advances the centring animation; called once per frame before the map/camera are used. */
    private void advanceCenterAnim() {
        if (centerAnimT < 0f) return;
        centerAnimT = Math.min(1f, centerAnimT + frameDt / VIEW_ANIM_SECONDS);
        // same duration and easing as the 3D exit: this is a view animation, not a new feel
        float t = easeOutCubic(centerAnimT);
        double x = centerAnimFromX + (centerAnimToX - centerAnimFromX) * t;
        double z = centerAnimFromZ + (centerAnimToZ - centerAnimFromZ) * t;
        if (centerAnim3d && cam3d != null) {
            cam3d.targetX = x;
            cam3d.targetY = centerAnimFromY + (centerAnimToY - centerAnimFromY) * t;
            cam3d.targetZ = z;
            cam3d.yaw = centerAnimFromYaw + centerAnimYawDelta * t;
            cam3d.pitch = centerAnimFromPitch + (centerAnimToPitch - centerAnimFromPitch) * t;
        } else {
            centerX = x;
            centerZ = z;
        }
        if (centerAnimT >= 1f) centerAnimT = -1f;
    }

    private void cancelCenterAnim() {
        centerAnimT = -1f;
    }

    // ---- scroll zoom: interpolated instead of stepping one wheel notch at a time ----

    /**
     * Eases the visible zoom (2D) or camera distance (3D) towards its target. Frame-rate
     * independent exponential approach (same family as the menu fades), so a wheel burst keeps
     * feeding the target and the motion stays continuous.
     */
    private void advanceZoomSmoothing() {
        if (view3d) {
            if (cam3d == null) return;
            if (targetDistance3d < 0f) {
                targetDistance3d = cam3d.distance;
                return;
            }
            float min = (float) minDistance3d();
            float max = (float) Math.max(maxDistance3d(), targetDistance3d);
            // config limits can change while the view is open: keep the target inside them
            targetDistance3d = Math.max(min, Math.min(max, targetDistance3d));
            float alpha = 1f - (float) Math.exp(-frameDt * ZOOM_EASE_PER_SECOND);
            float next = cam3d.distance + (targetDistance3d - cam3d.distance) * alpha;
            next = Math.max(min, Math.min(max, next));
            if (Math.abs(targetDistance3d - next) <= Math.max(0.5f, targetDistance3d * 0.0005f)) {
                next = targetDistance3d;
            }
            cam3d.distance = next;
            return;
        }
        if (targetZoom <= 0.0) {
            targetZoom = zoom;
            return;
        }
        if (!zoomSmoothing) return;
        double alpha = 1.0 - Math.exp(-frameDt * ZOOM_EASE_PER_SECOND);
        zoom += (targetZoom - zoom) * alpha;
        if (Math.abs(targetZoom - zoom) <= Math.abs(targetZoom) * 1.0e-4) {
            zoom = targetZoom;
            zoomSmoothing = false;
        }
        // Pin the world point that was under the cursor when the wheel moved: the map zooms around
        // the cursor instead of drifting away from it.
        double pb = MapData.INSTANCE.getPixelBlocks();
        centerX = zoomAnchorWorldX - (zoomAnchorScreenX - width / 2.0) * pb / zoom;
        centerZ = zoomAnchorWorldZ - (zoomAnchorScreenY - height / 2.0) * pb / zoom;
    }

    /** Stops the zoom easing at its current value (manual pan/centre/view switch wins). */
    private void cancelZoomSmoothing() {
        zoomSmoothing = false;
        targetZoom = zoom;
        if (view3d && cam3d != null) targetDistance3d = cam3d.distance;
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
    }

    /** Layer 5: buttons and their dropdown, above every readout but below the modal dialog. */
    private void drawButtons(GuiGraphicsExtractor g, MapData data, int mouseX, int mouseY) {
        // while map data / colours are being rebuilt most controls are inert: paint them disabled
        boolean busy = data.isLoading() || data.isStyleChanging();
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
        float styleHov = busy ? 0f
                : hoverAmount("style", mouseX >= selX && mouseX <= selX + selW && mouseY >= selY && mouseY <= selY + 20);
        drawButton(g, selX, selY, selW, styleLabel, styleHov, !busy);
        g.text(font, Component.literal("v"), selX + selW - 14, selY + 6,
                busy ? COL_TEXT_OFF : lerpColor(COL_TEXT_DIM, COL_TEXT, styleHov));
        // 3D view toggle, left of the style selector
        Component d3 = Component.translatable("voxymap.ui.view3d");
        int d3w = font.width(d3) + 12;
        int d3x = selX - 6 - d3w;
        float d3hov = busy ? 0f
                : hoverAmount("view3d", mouseX >= d3x && mouseX <= d3x + d3w && mouseY >= selY && mouseY <= selY + 20);
        drawButton(g, d3x, selY, d3w, d3, d3hov, !busy);
        if (view3d && !busy) {
            g.fill(d3x, selY, d3x + d3w, selY + 1, COL_ACCENT);
        }
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
                int rowColor = busy ? COL_TEXT_OFF
                        : (selected ? COL_ACCENT : lerpColor(COL_TEXT, 0xFFFFFFFF, rowHov));
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
                mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 20, !busy);
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int ry2 = by - 24;
        drawToggle(g, "distance", bx, ry2, rdw, rdText, distanceOn,
                mouseX >= bx && mouseX <= bx + rdw && mouseY >= ry2 && mouseY <= ry2 + 20, !busy);

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
        if (player == null) return;
        double dist = Minecraft.getInstance().options.renderDistance().get() * 16.0;
        double px = player.getX();
        double pz = player.getZ();
        double pb = Math.max(1, data.getPixelBlocks());
        int x0 = (int) Math.round(width / 2.0 + (px - dist - centerX) * zoom / pb);
        int x1 = (int) Math.round(width / 2.0 + (px + dist - centerX) * zoom / pb);
        int z0 = (int) Math.round(height / 2.0 + (pz - dist - centerZ) * zoom / pb);
        int z1 = (int) Math.round(height / 2.0 + (pz + dist - centerZ) * zoom / pb);
        int yellow = 0xE0FFFF00;
        g.fill(x0, z0, x0 + 1, z1 + 1, yellow);
        g.fill(x1, z0, x1 + 1, z1 + 1, yellow);
        g.fill(x0, z0, x1 + 1, z0 + 1, yellow);
        g.fill(x0, z1, x1 + 1, z1 + 1, yellow);
    }

    // The 3D render-distance overlay is drawn by Gpu3DMap's overlay pass (Gpu3DMap.prepareOverlay):
    // it builds the yellow ribbon on the terrain with the same camera/projection as the GPU
    // terrain. The old 2D-projected box + Bresenham line helpers were dead code and are removed.

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

    private record OversizeBox(int x, int y, int w, int h, int firstBtnX, int secondBtnX, int btnY, int btnW, int btnH) {
    }

    private OversizeBox oversizeBox() {
        int w = 340;
        int h = 136;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        int btnW = 158;
        int btnH = 20;
        int btnY = y + h - btnH - 10;
        int firstBtnX = x + 8;
        int secondBtnX = x + w - 8 - btnW;
        return new OversizeBox(x, y, w, h, firstBtnX, secondBtnX, btnY, btnW, btnH);
    }

    /** Warns before loading a map that may not fit in memory; the threshold is configurable. */
    private void drawOversizeWarning(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        MapData data = MapData.INSTANCE;
        OversizeBox box = oversizeBox();
        boolean hasSafe = data.oversizeSafePixelBlocks > 0;
        g.fill(0, 0, width, height, 0x99000000);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), COL_PANEL_DIALOG);
        g.fill(box.x(), box.y(), box.x() + box.w(), box.y() + 1, COL_BORDER);
        g.centeredText(font, Component.translatable("voxymap.ui.oversize.title"),
                width / 2, box.y() + 10, COL_WARN);
        int ty = box.y() + 10 + font.lineHeight + 10;
        Component l1 = Component.translatable("voxymap.ui.oversize.line1",
                String.format(java.util.Locale.ROOT, "%.2f", data.oversizePixels / 1e8),
                String.format(java.util.Locale.ROOT, "%.2f", data.oversizeLimit / 1e8));
        g.centeredText(font, l1, width / 2, ty, COL_TEXT);
        ty += font.lineHeight + 2;
        g.centeredText(font, Component.translatable("voxymap.ui.oversize.line2"), width / 2, ty, COL_TEXT_DIM);
        ty += font.lineHeight + 2;
        g.centeredText(font, Component.translatable("voxymap.ui.oversize.line3"), width / 2, ty, COL_TEXT_DIM);
        if (!hasSafe) {
            ty += font.lineHeight + 2;
            g.centeredText(font, Component.translatable("voxymap.ui.oversize.noSafe"),
                    width / 2, ty, COL_WARN);
        }
        if (hasSafe) {
            drawDialogButton(g, box.firstBtnX(), box.btnY(), box.btnW(), box.btnH(),
                    Component.translatable("voxymap.ui.oversize.safe"), COL_ACCENT, mouseX, mouseY);
        }
        int forceX = hasSafe ? box.secondBtnX() : box.x() + (box.w() - box.btnW()) / 2;
        drawDialogButton(g, forceX, box.btnY(), box.btnW(), box.btnH(),
                Component.translatable("voxymap.ui.oversize.force"), COL_WARN, mouseX, mouseY);
    }

    /**
     * Player marker: a small arrow drawn as a horizontal plane at the player's position, floating
     * above the terrain, with a vertical white line down to the ground.
     */
    private void drawPlayerMarker3d(GuiGraphicsExtractor g) {
        var player = Minecraft.getInstance().player;
        if (player == null || cam3d == null) return;
        MapData data = MapData.INSTANCE;
        if (!data.isReady()) return;
        double px = player.getX();
        double pz = player.getZ();
        int bx = (int) Math.floor(px);
        int bz = (int) Math.floor(pz);
        double ground = groundMeshY(bx, bz);
        double markY = ground + Math.max(0.0, VoxyMapConfig.PLAYER_MARKER_HEIGHT_3D.get());
        org.joml.Matrix4f vp = cam3d.viewProjection((float) width / Math.max(1f, (float) height), fov3d);
        org.joml.Vector3f top = cam3d.worldToScreen(px, markY, pz, vp, width, height);
        org.joml.Vector3f base = cam3d.worldToScreen(px, ground, pz, vp, width, height);
        if (top == null || base == null) return;
        // vertical line from the marker down to the terrain (clipped to the screen: an extreme
        // camera angle can project this line tens of thousands of pixels tall)
        int lineX = Math.round(top.x);
        int y0 = Math.min(Math.round(top.y), Math.round(base.y));
        int y1 = Math.max(Math.round(top.y), Math.round(base.y));
        if (y1 >= 0 && y0 <= height) {
            g.fill(lineX, Math.max(0, y0), lineX + 1, Math.min(height, y1 + 1), 0xFFFFFFFF);
        }
        // arrow: a HORIZONTAL quad (normal = world +Y) textured with the 2D arrow artwork.
        // v=0 is the TIP, so it sits at the player's facing end; the quad is subdivided so the
        // affine per-cell mapping stays within a pixel of the true perspective mapping.
        double yawRad = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = Math.cos(yawRad);
        double rz = Math.sin(yawRad);
        double scale = Math.max(0.2, VoxyMapConfig.PLAYER_MARKER_SCALE_3D.get());
        double half = Math.max(4.0, cam3d.distance * 0.022 * scale);
        double ex = rx * half;
        double ez = rz * half;
        double ax = fx * half;
        double az = fz * half;
        // tip end (v = 0) in the facing direction, tail end (v = 1) behind the player
        org.joml.Vector3f c00 = cam3d.worldToScreen(px + ax - ex, markY, pz + az - ez, vp, width, height);
        org.joml.Vector3f c10 = cam3d.worldToScreen(px + ax + ex, markY, pz + az + ez, vp, width, height);
        org.joml.Vector3f c11 = cam3d.worldToScreen(px - ax + ex, markY, pz - az + ez, vp, width, height);
        org.joml.Vector3f c01 = cam3d.worldToScreen(px - ax - ex, markY, pz - az - ez, vp, width, height);
        if (c00 != null && c10 != null && c11 != null && c01 != null) {
            drawArrowImage(g, c00, c10, c11, c01);
        }
    }

    // ---- CPU-rasterised 3D arrow: exact perspective sampling instead of GUI blit scanlines ----
    private NativeImage arrowImg;
    private Identifier arrowImgId;
    private NativeImage sourceArrow;
    private static final int ARROW_IMG = 128;
    private String arrowCacheKey = "";

    private void ensureArrowImage() {
        if (arrowImg != null) return;
        arrowImg = new NativeImage(ARROW_IMG, ARROW_IMG, false);
        arrowImgId = Identifier.fromNamespaceAndPath("voxymap", "arrow3d");
        Minecraft mc = Minecraft.getInstance();
        DynamicTexture tex = new DynamicTexture(() -> "voxymap_arrow3d", arrowImg);
        mc.getTextureManager().register(arrowImgId, tex);
        try {
            var res = mc.getResourceManager().getResource(arrowTex());
            if (res.isPresent()) {
                try (var stream = res.get().open()) {
                    sourceArrow = NativeImage.read(stream);
                }
            }
        } catch (Throwable ignored) {
        }
        if (sourceArrow == null) {
            sourceArrow = new NativeImage(32, 32, false);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    sourceArrow.setPixelABGR(x, y, 0xFFFFFFFF);
                }
            }
        }
    }

    /** Rasterises the arrow quad on the CPU (per-pixel inverse mapping), then blits the texture. */
    private void drawArrowImage(GuiGraphicsExtractor g,
                                org.joml.Vector3f c00, org.joml.Vector3f c10,
                                org.joml.Vector3f c11, org.joml.Vector3f c01) {
        ensureArrowImage();
        float minX = Math.min(Math.min(c00.x, c10.x), Math.min(c11.x, c01.x));
        float maxX = Math.max(Math.max(c00.x, c10.x), Math.max(c11.x, c01.x));
        float minY = Math.min(Math.min(c00.y, c10.y), Math.min(c11.y, c01.y));
        float maxY = Math.max(Math.max(c00.y, c10.y), Math.max(c11.y, c01.y));
        int bx0 = (int) Math.floor(minX) - 1;
        int by0 = (int) Math.floor(minY) - 1;
        int bx1 = (int) Math.ceil(maxX) + 1;
        int by1 = (int) Math.ceil(maxY) + 1;
        int pw = bx1 - bx0;
        int ph = by1 - by0;
        if (pw <= 0 || ph <= 0 || pw > 512 || ph > 512) return;
        String key = bx0 + "," + by0 + "," + pw + "," + ph + "," + c00.x + "," + c00.y
                + "," + c10.x + "," + c10.y + "," + c11.x + "," + c11.y
                + "," + c01.x + "," + c01.y;
        if (key.equals(arrowCacheKey)) {
            // nothing moved: reuse the already uploaded texture
            g.blit(RenderPipelines.GUI_TEXTURED, arrowImgId, bx0, by0, 0f, 0f, pw, ph,
                    ARROW_IMG, ARROW_IMG, ARROW_IMG, ARROW_IMG);
            return;
        }
        arrowCacheKey = key;
        int iw = sourceArrow.getWidth();
        int ih = sourceArrow.getHeight();
        for (int py = 0; py < ARROW_IMG; py++) {
            for (int px = 0; px < ARROW_IMG; px++) {
                arrowImg.setPixelABGR(px, py, 0);
            }
        }
        float[][] pts = {
                {c00.x, c00.y, 0f, 0f},
                {c10.x, c10.y, 1f, 0f},
                {c11.x, c11.y, 1f, 1f},
                {c01.x, c01.y, 0f, 1f}
        };
        for (int py = 0; py < ARROW_IMG; py++) {
            float sy = by0 + (py + 0.5f) * ph / (float) ARROW_IMG;
            for (int px = 0; px < ARROW_IMG; px++) {
                float sx = bx0 + (px + 0.5f) * pw / (float) ARROW_IMG;
                float[] uv = inverseBilinear(pts, sx, sy);
                if (uv == null) continue;
                float u = uv[0];
                float v = uv[1];
                if (u < 0f || u > 1f || v < 0f || v > 1f) continue;
                int tx = Math.max(0, Math.min(iw - 1, (int) (u * iw)));
                int ty = Math.max(0, Math.min(ih - 1, (int) (v * ih)));
                int c = sourceArrow.getPixel(tx, ty);
                int a = (c >>> 24) & 0xFF;
                if (a < 8) continue;
                arrowImg.setPixelABGR(px, py, c);
            }
        }
        Minecraft mc = Minecraft.getInstance();
        var tex = mc.getTextureManager().getTexture(arrowImgId);
        if (tex instanceof DynamicTexture dt) {
            dt.upload();
        }
        g.blit(RenderPipelines.GUI_TEXTURED, arrowImgId, bx0, by0, 0f, 0f, pw, ph,
                ARROW_IMG, ARROW_IMG, ARROW_IMG, ARROW_IMG);
    }

    /** Screen point -> (u,v) inside the two-triangle quad; null when outside. */
    private static float[] inverseBilinear(float[][] p, float x, float y) {
        for (int tri = 0; tri < 2; tri++) {
            int a = 0;
            int b = tri == 0 ? 1 : 2;
            int c = tri == 0 ? 2 : 3;
            float ax = p[a][0], ay = p[a][1], au = p[a][2], av = p[a][3];
            float bx = p[b][0], by = p[b][1], bu = p[b][2], bv = p[b][3];
            float cx = p[c][0], cy = p[c][1], cu = p[c][2], cv = p[c][3];
            float d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
            if (Math.abs(d) < 1e-6f) continue;
            float w0 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / d;
            float w1 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / d;
            float w2 = 1f - w0 - w1;
            if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
            return new float[]{w0 * au + w1 * bu + w2 * cu, w0 * av + w1 * bv + w2 * cv};
        }
        return null;
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
        // 3D view toggle sits immediately left of the palette button
        int d3w = font.width(Component.translatable("voxymap.ui.view3d")) + 12;
        int d3x = width - 8 - selW - 6 - d3w;
        labelBlockers.add(new int[]{d3x - 2, STYLE_ROW_Y - 2, d3x + d3w + 2, STYLE_ROW_Y + 22});
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
        // top hint plate in the gap between config and the scale panel
        if (VoxyMapConfig.SHOW_HINT.get()) {
            labelBlockers.add(new int[]{hintPanelLeft() - 4, 4,
                    hintPanelLeft() + hintPanelWidth() + 4, 22});
        }
        // bottom coordinate plate
        if (VoxyMapConfig.SHOW_COORDINATES.get()) {
            int w = font.width(coordsComponent(mouseX, mouseY));
            labelBlockers.add(new int[]{width / 2 - w / 2 - 6, height - 22, width / 2 + w / 2 + 6, height - 4});
        }
        // status line plates are no longer used: progress display was removed
    }

    private boolean labelBlocked(double x0, double y0, double x1, double y1) {
        for (int[] r : labelBlockers) {
            if (x1 > r[0] && x0 < r[2] && y1 > r[1] && y0 < r[3]) return true;
        }
        return false;
    }

    private void drawBiomeLabels(GuiGraphicsExtractor g) {
        MapData data = MapData.INSTANCE;
        if (!data.isTile2DMode() && data.getMapWidth() <= 0) return;
        // two discrete label sizes only on very large GUI scales (4x+); otherwise default size
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float labelScale = (guiScale >= 4.0 && zoom < 1.0) ? 0.5f : 1.0f;
        int[] labels = data.getBiomeLabelsFlat();
        double cx = centerPx();
        double cz = centerPz();
        double halfLine = font.lineHeight * labelScale / 2.0 + 2;
        labels3dTotal = labels.length / 3;
        labels3dDrawn = 0;
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

    /**
     * 3D biome labels: anchors are projected into the 3D view, limited by a horizontal range around
     * the camera and hidden when the terrain blocks the line of sight.
     */
    private void drawBiomeLabels3d(GuiGraphicsExtractor g) {
        MapData data = MapData.INSTANCE;
        if (!data.isBiomeOverlay() || cam3d == null || data.getMapWidth() <= 0) return;
        int[] labels = data.getBiomeLabelsFlat();
        if (labels.length == 0) {
            labels3dTotal = 0;
            labels3dDrawn = 0;
            return;
        }
        labels3dTotal = labels.length / 3;
        labels3dDrawn = 0;
        int pb = data.getPixelBlocks();
        long minWX = data.getMinWX();
        long minWZ = data.getMinWZ();
        double eyeX = cam3d.eyeX();
        double eyeY = cam3d.eyeY();
        double eyeZ = cam3d.eyeZ();
        double range = Math.max(200.0, VoxyMapConfig.BIOME_LABEL_3D_RANGE.get());
        // same visibility rule as the 2D labels: hide when the effective zoom is too small.
        // The 3D camera distance maps back to a 2D-equivalent zoom:
        //   zoomEq = pixelBlocks * screenHeight / (2 * distance * tan(fov/2))
        double tanHalf = Math.tan(Math.toRadians(fov3d * 0.5));
        double zoomEq = data.getPixelBlocks() * height
                / (2.0 * Math.max(1.0, cam3d.distance) * Math.max(1e-4, tanHalf));
        double uiScaleNow = Minecraft.getInstance().getWindow().getGuiScale();
        if (zoomEq * uiScaleNow < 1.0) return;
        org.joml.Matrix4f vp = cam3d.viewProjection(
                (float) width / Math.max(1f, (float) height), fov3d);
        // same rule as the 2D labels: half-size text on very large GUI scales
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float labelScale = guiScale >= 4.0 ? 0.5f : 1.0f;
        double halfLine = font.lineHeight * labelScale / 2.0 + 2;
        for (int i = 0; i + 2 < labels.length; i += 3) {
            double wx = minWX + (double) labels[i] * pb;
            double wz = minWZ + (double) labels[i + 1] * pb;
            double dxh = wx - eyeX;
            double dzh = wz - eyeZ;
            if (dxh * dxh + dzh * dzh > range * range) continue;
            int ix = (int) Math.floor(wx);
            int iz = (int) Math.floor(wz);
            double wy = groundMeshY(wx, wz) + 6.0;
            org.joml.Vector3f p = cam3d.worldToScreen(wx, wy, wz, vp, width, height);
            if (p == null) continue;
            double sx = p.x;
            double sy = p.y;
            if (sx < -96 || sx > width + 96 || sy < -40 || sy > height + 40) continue;
            if (isOccluded3d(eyeX, eyeY, eyeZ, wx, wy, wz)) continue;
            LabelInfo info = labelInfo(data, labels[i + 2]);
            if (info == null) continue;
            double halfW = info.width * labelScale / 2.0 + 2;
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
            labels3dDrawn++;
        }
    }

    /**
     * True when the terrain blocks the line of sight between the camera and a label anchor.
     */
    private boolean isOccluded3d(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3) return false;
        double step = Math.max(2.0, len * 0.004);
        for (double d = step; d < len - step; d += step) {
            double t = d / len;
            if (ey + dy * t <= groundMeshY(ex + dx * t, ez + dz * t)) return true;
        }
        return false;
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
        ASYNC_3D.execute(() -> {
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
        if (cam3d != null) {
            // keep rotation/zoom for the next time this world's map is opened
            MapData.INSTANCE.setView3dCamera(cam3d.yaw, cam3d.pitch, cam3d.distance);
        }
        if (VoxyMapConfig.REMEMBER_ZOOM.get()) {
            MapData.INSTANCE.setZoom(zoom);
        }
        // closing the map while still in 3D used to leave the contour/label overrides set for the
        // rest of the session (2D contours vanished, biome labels stopped rebuilding)
        restore3dOverrides();
        // Real close: release the GPU terrain unless the player asked to keep 3D tiles around.
        // A config-screen round trip goes through removed() only (no finishClose) and keeps them;
        // MapData clears them on a world change.
        if (!VoxyMapConfig.KEEP_3D_TILES_IN_2D.get()) {
            Gpu3DMap.clear();
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
    public void removed() {
        // Reached both when the map is really closed and when another screen temporarily replaces
        // it (e.g. the config screen). The process-wide 3D overrides must not leak into the
        // replacing screen, so restore them here (idempotent); init() re-applies them when the map
        // comes back while `view3d` is still on.
        restore3dOverrides();
        MapData.INSTANCE.onMapClosed();
        releaseArrowTexture();
        // Gpu3DMap is intentionally NOT cleared here: a 3D -> config screen -> map round trip must
        // keep the built terrain. finishClose() releases it on a real close (unless
        // keep3dTilesIn2d) and MapData clears it when the world changes.
        super.removed();
    }

    /** Releases the 3D arrow texture and the source image it samples from. */
    private void releaseArrowTexture() {
        if (arrowImgId != null) {
            Minecraft.getInstance().getTextureManager().release(arrowImgId);
            arrowImgId = null;
        }
        arrowImg = null;
        arrowCacheKey = "";
        if (sourceArrow != null) {
            sourceArrow.close();
            sourceArrow = null;
        }
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
                // Linear animation in both views (2D pan / 3D camera target + rotation reset), so
                // the switch from "looking somewhere else" to "on the player" is readable.
                startCenterAnim(p.getX(), p.getZ());
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
        if (MapData.INSTANCE.oversizePixels > 0) {
            OversizeBox box = oversizeBox();
            boolean hasSafe = data.oversizeSafePixelBlocks > 0;
            if (button == 0 && hasSafe && mx >= box.firstBtnX() && mx <= box.firstBtnX() + box.btnW()
                    && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
                data.retryWithSafeGrid();
                return true;
            }
            int forceX = hasSafe ? box.secondBtnX() : box.x() + (box.w() - box.btnW()) / 2;
            if (button == 0 && mx >= forceX && mx <= forceX + box.btnW()
                    && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
                data.retryOversized();
                return true;
            }
            return true;
        }
        if (lodWarning && button == 0) {
            DialogBox box = dialogBox();
            if (mx >= box.firstBtnX() && mx <= box.firstBtnX() + box.btnW()
                    && my >= box.btnY() && my <= box.btnY() + box.btnH()) {
                data.skipInitialDelay();
                data.updateAsync(true, MapData.RefreshTrigger.MANUAL_DIALOG);
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
                    if (!data.isStyleChanging() && !data.isLoading()) {
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
            // disabled while the map data is being (re)loaded
            if (!data.isLoading() && !data.isStyleChanging()) {
                styleMenuOpen = !styleMenuOpen;
            }
            return true;
        }
        Component d3m = Component.translatable("voxymap.ui.view3d");
        int d3wm = font.width(d3m) + 12;
        if (button == 0 && mx >= selX2 - 6 - d3wm && mx <= selX2 - 6 && my >= selY2 && my <= selY2 + 20) {
            // match the painted disabled state: no switching while a style change is running
            if (!data.isLoading() && !data.isStyleChanging()) {
                toggle3D();
            }
            return true;
        }
        Component biomeText = Component.translatable("voxymap.ui.biomes");
        int bw = font.width(biomeText) + 12;
        int bx = 8;
        int by = height - 30;
        if (button == 0 && mx >= bx && mx <= bx + bw && my >= by && my <= by + 20) {
            if (!data.isLoading() && !data.isStyleChanging()) {
                data.setBiomeOverlay(!data.isBiomeOverlay());
            }
            return true;
        }
        Component rdText = Component.translatable("voxymap.ui.renderDistance");
        int rdw = font.width(rdText) + 12;
        int rx2 = bx;
        int ry2 = by - 24;
        if (button == 0 && mx >= rx2 && mx <= rx2 + rdw && my >= ry2 && my <= ry2 + 20) {
            if (!data.isLoading() && !data.isStyleChanging()) {
                data.setRenderDistanceOverlay(!data.isRenderDistanceOverlay());
            }
            return true;
        }
        Component refresh = data.isLoading()
                ? Component.translatable("voxymap.ui.refreshing")
                : Component.translatable("voxymap.ui.refresh");
        int rw = font.width(refresh) + 12;
        int rx = width - 8 - rw;
        if (button == 0 && mx >= rx && mx <= width - 8 && my >= height - 30 && my <= height - 10) {
            if (!data.isLoading()) {
                data.updateAsync(true, MapData.RefreshTrigger.MANUAL_BUTTON);
            }
            return true;
        }
        Component cfg = Component.translatable("voxymap.ui.config");
        int cw = font.width(cfg) + 12;
        if (button == 0 && mx >= 8 && mx <= 8 + cw && my >= 8 && my <= 28) {
            configPressT = 1f;
            openConfig();
            return true;
        }
        if (view3d) {
            // left = pan, middle = orbit (handled by mouseDragged) unless it is a short click,
            // which switches back to the 2D view; right = the same context menu, positioned at
            // the ray-picked terrain point
            if (button == 2) {
                middlePress3d = true;
                middleDragged3d = false;
                middlePressX = mx;
                middlePressY = my;
                middlePressAtMs = System.currentTimeMillis();
                return true;
            }
            if (button == 1) {
                double[] hit = pickWorld3d(mx, my);
                if (hit != null) {
                    menuOpen = true;
                    menuX = (int) mx;
                    menuY = (int) my;
                    menuWorldX = (int) Math.round(hit[0]);
                    menuWorldY = (int) Math.round(hit[1]);
                    menuWorldZ = (int) Math.round(hit[2]);
                }
            }
            return true;
        }
        if (button == 2) {
            // middle: a short click switches views, a drag enters 3D on the first movement and
            // keeps orbiting. The click/drag decision is made in mouseReleased / mouseDragged.
            pending3dOrbit = true;
            middlePress2d = true;
            middlePress2dX = mx;
            middlePress2dY = my;
            middlePress2dAtMs = System.currentTimeMillis();
            return true;
        }
        if (button == 0) {
            cancelCenterAnim();
            cancelZoomSmoothing(); // a drag must not keep the old cursor anchor
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
        if (pending3dOrbit && !view3d) {
            // continue the same drag as a 3D orbit (seamless: scale matched, looking straight down)
            cancelCenterAnim();
            toggle3D();
            pending3dOrbit = false;
            middlePress2d = false; // the drag already switched; the release must not switch again
        }
        if (sliderDragging) {
            sliderValue = scaleSliderValueAt(mx);
            return true;
        }
        if (view3d && cam3d != null) {
            // the exit animation owns the camera until it finishes; ignore input during it
            if (exit3dT >= 0f) return true;
            if (e.button() == 0) {
                cancelCenterAnim();
                // left drag: pan along the camera's screen axes (motion matches the cursor)
                double scale = cam3d.distance * 0.0015 * VoxyMapConfig.PAN_SENS_3D.get();
                double yaw = cam3d.yaw;
                double rightX = Math.cos(yaw);
                double rightZ = -Math.sin(yaw);
                double fwdX = -Math.sin(yaw);
                double fwdZ = -Math.cos(yaw);
                cam3d.targetX -= rightX * dx * scale - fwdX * dy * scale;
                cam3d.targetZ -= rightZ * dx * scale - fwdZ * dy * scale;
            } else if (e.button() == 2) {
                // Middle: a press that moves is an orbit; a short press without movement is a
                // click that switches back to 2D (decided in mouseReleased).
                if (middlePress3d
                        && (Math.abs(mx - middlePressX) > MIDDLE_CLICK_SLOP_PX
                        || Math.abs(my - middlePressY) > MIDDLE_CLICK_SLOP_PX)) {
                    middleDragged3d = true;
                }
                cancelCenterAnim();
                // middle drag: orbit around the target
                double sens = 0.006 * VoxyMapConfig.ROTATE_SENS_3D.get();
                cam3d.yaw -= (float) (dx * sens);
                float minPitch = (float) Math.toRadians(-5);
                cam3d.pitch = Math.max(PITCH_STRAIGHT_DOWN,
                        Math.min(minPitch, cam3d.pitch - (float) (dy * sens * 0.66)));
            } else {
                // right button: no camera action
                return true;
            }
            return true;
        }
        if (dragging) {
            cancelCenterAnim();
            centerX -= dx * MapData.INSTANCE.getPixelBlocks() / zoom;
            centerZ -= dy * MapData.INSTANCE.getPixelBlocks() / zoom;
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        // a middle-click that never turned into a drag must not leave the "switch to 3D" pending:
        // otherwise the next left-drag jumped into the 3D view out of nowhere
        if (e.button() == 2) pending3dOrbit = false;
        double mx = e.x();
        double my = e.y();
        if (e.button() == 2 && middlePress3d) {
            // A short middle press that did not move is a click -> switch back to 2D. A long press
            // or any movement is an orbit gesture and must NOT switch (input must never feel like
            // it "stole" a drag).
            boolean click = !middleDragged3d
                    && Math.abs(mx - middlePressX) <= MIDDLE_CLICK_SLOP_PX
                    && Math.abs(my - middlePressY) <= MIDDLE_CLICK_SLOP_PX
                    && System.currentTimeMillis() - middlePressAtMs <= MIDDLE_CLICK_MAX_MS;
            middlePress3d = false;
            middleDragged3d = false;
            if (click && view3d && exit3dT < 0f) {
                cancelCenterAnim();
                toggle3D();
                return true;
            }
        }
        if (e.button() == 2 && middlePress2d) {
            // symmetric: a short middle click in 2D enters the 3D view
            boolean click = !view3d
                    && Math.abs(mx - middlePress2dX) <= MIDDLE_CLICK_SLOP_PX
                    && Math.abs(my - middlePress2dY) <= MIDDLE_CLICK_SLOP_PX
                    && System.currentTimeMillis() - middlePress2dAtMs <= MIDDLE_CLICK_MAX_MS;
            middlePress2d = false;
            if (click && !view3d && exit3dT < 0f) {
                // same guard as the 3D button: do not switch while data is (re)loading
                MapData data = MapData.INSTANCE;
                if (!data.isLoading() && !data.isStyleChanging()) {
                    toggle3D();
                }
                return true;
            }
        }
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
        if (view3d && cam3d != null) {
            // the exit animation owns the camera until it finishes
            if (exit3dT >= 0f) return true;
            cancelCenterAnim();
            double zoomStep = 1.0 + 0.15 * VoxyMapConfig.ZOOM_SENS_3D.get();
            // Feed the target, not the visible distance: advanceZoomSmoothing() eases towards it,
            // so a burst of wheel notches becomes one continuous zoom instead of hard steps.
            float base = targetDistance3d > 0f ? targetDistance3d : cam3d.distance;
            float next = (float) (base * (scrollY > 0 ? 1.0 / zoomStep : zoomStep));
            // The enter distance can exceed MAX_DISTANCE_3D (2D zoomed further out than the 3D
            // limit); do not snap it back on the first scroll. Clamp to the configured range once
            // the current distance is inside it, and never allow zooming out past the current one.
            float upper = (float) Math.max(maxDistance3d(), base);
            targetDistance3d = Math.max((float) minDistance3d(), Math.min(upper, next));
            return true;
        }
        cancelCenterAnim();
        MapData data = MapData.INSTANCE;
        // Anchor the world point under the cursor: the ease below keeps it pinned while zooming.
        double worldX = screenToWorldX(mx);
        double worldZ = screenToWorldZ(my);
        double factor = scrollY > 0 ? 1.15 : 1.0 / 1.15;
        double base = zoomSmoothing ? targetZoom : zoom;
        targetZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, base * factor));
        zoomAnchorWorldX = worldX;
        zoomAnchorWorldZ = worldZ;
        zoomAnchorScreenX = mx;
        zoomAnchorScreenY = my;
        zoomSmoothing = true;
        return true;
    }
}

