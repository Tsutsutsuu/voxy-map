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

    private double centerX;
    private double centerZ;
    private double zoom = 0.5;
    private boolean dragging;
    private int menuX, menuY, menuWorldX, menuWorldZ, menuWorldY;
    private boolean menuOpen;
    /** 3D height-field view toggle. */
    private boolean view3d;
    private Map3D.Camera cam3d;
    private Map3D.Mesh mesh3d;
    private boolean building3d;
    private int builtStepA = -1;
    private int builtStepB = -1;
    private int builtStepC = -1;
    private int builtColorEpoch = -1;
    private int builtPixelBlocks = -1;
    private double builtVScale3d = -1.0;
    private double builtCenterX = Double.NaN;
    private boolean builtLodDebug;
    private double builtCamDist = -1.0;
    private double builtCenterZ = Double.NaN;
    private double builtNearRadius = -1.0;
    private double builtMidRadius = -1.0;
    /** look-at target the mesh was built for: orbiting the camera no longer forces a rebuild */
    private double builtTargetX = Double.NaN;
    private double builtTargetZ = Double.NaN;
    /** back-off after an empty/failed mesh build so the background pool is not spun every frame */
    private long meshRetryAtNs;
    private float fov3d = 55f;
    private Map3DSoft.Framebuffer fb3d;
    private NativeImage img3d;
    private DynamicTexture tex3dDyn;
    private Identifier tex3dId;
    private boolean cam3dDirty = true;
    private long dbg3dNanos;
    private long dbg3dFrames;
    private long lastCamChangeNs;
    private int labels3dDrawn;
    private int labels3dTotal;
    private int[] lastBiomeEdges;
    private boolean lastRdOn;
    /** bumped whenever the 3D framebuffer/texture pair is rebuilt; stale frames are discarded */
    private int soft3dGen;
    /** set once the screen is closing so late background frames never touch released resources */
    private boolean screenClosed;
    /** middle-drag started in 2D: switch to 3D on the first move and keep orbiting */
    private boolean pending3dOrbit;
    private boolean pendingFxaa;
    private volatile boolean soft3dPending;
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

    @Override
    protected void init() {
        // init() also runs when this screen is resumed from the config screen, so clear the flag
        // that invalidates frames from a temporarily released 3D framebuffer
        screenClosed = false;
        openT = 0f;
        closing = false;
        closeT = 0f;
        // load this world's per-world UI state before deciding whether to reopen in 3D. It used to be
        // loaded from the async map build only, so a new/slow world could inherit the previous
        // world's 3D mode and camera (and then write those values into the previous world's state
        // file while its storage path was still the current one).
        MapData data0 = MapData.INSTANCE;
        data0.loadCurrentWorldUiState();
        // restore this world's last 3D mode if the player asked to remember it
        if (!view3d && VoxyMapConfig.REMEMBER_3D_VIEW.get() && data0.getView3d()) {
            toggle3D(true);
        }
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
        // show the cached grid immediately; the fast lane below decides whether a refresh is needed
        data.ensureMainTextures();
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

        // 3D -> 2D exit animation: linearly rotate to straight down, then really switch
        if (exit3dT >= 0f && cam3d != null) {
            exit3dT = Math.min(1f, exit3dT + frameDt / 0.22f);
            float et = exit3dT;
            cam3d.yaw = exitFromYaw * (1f - et);
            float targetPitch = (float) Math.toRadians(-89.0);
            cam3d.pitch = exitFromPitch + (targetPitch - exitFromPitch) * et;
            cam3dDirty = true;
            if (exit3dT >= 1f) {
                finish3dExit();
            }
        }
        // draw order is the stacking order, bottom to top:
        //   1 map, 2 map overlays, 3 biome labels, 4 readouts/status text, 5 buttons, 6 dialog
        if (view3d) {
            // rebuild the LOD mesh when the camera moved far enough, then refresh the CPU image
            // overlay toggles must refresh the image even while the camera is idle
            // the box is computed analytically in the rasteriser; just refresh when it is toggled
            boolean rdOnNow = data.isRenderDistanceOverlay();
            if (rdOnNow != lastRdOn) {
                lastRdOn = rdOnNow;
                cam3dDirty = true;
            }
            if (data.isBiomeOverlay()) {
                data.ensureBiomeTexture();
            }
            int[] edgesNow = data.isBiomeOverlay() ? data.getBiomeEdgeSnapshot() : null;
            if (edgesNow != lastBiomeEdges) {
                lastBiomeEdges = edgesNow;
                cam3dDirty = true;
            }
            ensureMesh3d();
            updateSoft3d();
            if (tex3dId != null && fb3d != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, tex3dId, 0, 0, 0f, 0f, width, height,
                        fb3d.width, fb3d.height, fb3d.width, fb3d.height);
            }
            drawPlayerMarker3d(g);
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
                // preparation progress: map colour rebuild first, then the 3D mesh build
                if (data.isLoading()) {
                    drawStatusLine(g, Component.translatable("voxymap.ui.view3dBuildingMap",
                            data.getProgress()), 24, COL_WARN);
                } else if (data.isStyleChanging()) {
                    drawStatusLine(g, Component.translatable("voxymap.ui.view3dBuildingColors"), 24, COL_WARN);
                } else if (data.getMapWidth() <= 0) {
                    // no data / build error: show the same message the 2D view uses instead of a
                    // black screen with no explanation
                    drawStatusText(g, data);
                } else if (building3d || mesh3d == null) {
                    drawStatusLine(g, Component.translatable("voxymap.ui.view3dBuildingMesh"), 24, COL_WARN);
                }
            } else {
                drawStatusText(g, data);
            }
            drawReadouts(g, mouseX, mouseY);
            drawButtons(g, data, mouseX, mouseY);
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

    /** Layer 1: the map texture (nearest neighbour when zoomed in, the pre-filtered copy far out). */
    private void drawMap(GuiGraphicsExtractor g, MapData data, double uiScale) {
        if (data.isTile2DMode()) {
            drawUnifiedTiles(g, data, uiScale);
            return;
        }
        TileStore tiles = data.getTileStore();
        if (VoxyMapConfig.TILE_RENDER.get() && tiles != null) {
            // Show the pre-filtered whole-map copy first, then let tiles replace it. Without this
            // the per-frame tile budget makes a freshly opened map visibly fill from top to bottom.
            drawTilePreview(g, data);
            drawMapTiles(g, data, tiles, uiScale);
            drawLiveTiles(g, data, tiles);
            return;
        }
        boolean mainReady = data.getTexture() != null;
        // zoomed far out (or while the big texture is rebuilt) use the pre-filtered 1/4 copy
        boolean useSmall = !mainReady || zoom * uiScale < 0.5;
        Identifier tex = useSmall ? data.getTextureSmall() : data.getTexture();
        if (tex == null) tex = data.getTexture();
        if (tex == null) return;
        if (data.getMapWidth() <= 0 || data.getMapHeight() <= 0) return;
        // real screen-space ratio: 1 texture pixel per physical pixel or larger uses
        // nearest neighbour; smaller uses linear (pre-filtered copy when far out)
        int texW = data.getTextureWidth();
        int texH = data.getTextureHeight();
        if (texW <= 0 || texH <= 0) {
            // the big texture was released on close (releaseTexturesOnClose) while the 1/4 copy is
            // still registered: keep drawing it at the map's size instead of returning early, which
            // used to leave the reopened map black until the big texture finished uploading
            texW = data.getMapWidth();
            texH = data.getMapHeight();
        }
        if (texW <= 0 || texH <= 0) return;
        double x0 = width / 2.0 - centerPx() * zoom;
        double y0 = height / 2.0 - centerPz() * zoom;
        double w = texW * zoom;
        double h = texH * zoom;
        // sample the ACTUAL texture size: while a new map scale is still uploading the geometry and
        // the texture can briefly disagree, and using the map size then tiles the image
        int srcW = useSmall ? Math.max(1, texW / 4) : texW;
        int srcH = useSmall ? Math.max(1, texH / 4) : texH;
        // GUI_TEXTURED is opaque; use the premultiplied-alpha pipeline so unexplored (alpha 0)
        // pixels let the background through instead of drawing black
        g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, tex, (int) Math.round(x0), (int) Math.round(y0),
                0f, 0f, Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)),
                srcW, srcH, srcW, srcH);
    }

    /** Migration path: one store renders both the seeded base tiles and live-updated tiles. */
    private void drawUnifiedTiles(GuiGraphicsExtractor g, MapData data, double uiScale) {
        LiveTileStore live = data.getLiveTiles();
        if (live == null) return;
        live.beginFrame();
        int pb = Math.max(1, data.getPixelBlocks());
        double worldPerScreenPx = pb / Math.max(1.0e-6, zoom);
        double left = centerX - (width / 2.0) * worldPerScreenPx;
        double right = centerX + (width / 2.0) * worldPerScreenPx;
        double top = centerZ - (height / 2.0) * worldPerScreenPx;
        double bottom = centerZ + (height / 2.0) * worldPerScreenPx;
        int tx0 = live.tileIndexX(left), tx1 = live.tileIndexX(right);
        int tz0 = live.tileIndexZ(top), tz1 = live.tileIndexZ(bottom);
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
        live.evictFar(live.tileIndexX(centerX), live.tileIndexZ(centerZ), viewRadius, 512);
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

    /** Event-fed tiles outside the base snapshot; the base tile always wins where it has data. */
    private void drawLiveTiles(GuiGraphicsExtractor g, MapData data, TileStore base) {
        if (!VoxyMapConfig.LIVE_TILES.get()) return;
        LiveTileStore live = data.getLiveTiles();
        if (live == null) return;
        live.beginFrame();
        int pb = Math.max(1, data.getPixelBlocks());
        double worldPerScreenPx = pb / Math.max(1.0e-6, zoom);
        double left = centerX - (width / 2.0) * worldPerScreenPx;
        double right = centerX + (width / 2.0) * worldPerScreenPx;
        double top = centerZ - (height / 2.0) * worldPerScreenPx;
        double bottom = centerZ + (height / 2.0) * worldPerScreenPx;
        int tx0 = live.tileIndexX(left), tx1 = live.tileIndexX(right);
        int tz0 = live.tileIndexZ(top), tz1 = live.tileIndexZ(bottom);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (base != null && base.hasData(tx, tz)) continue;
                LiveTileStore.LiveTile tile = live.get(tx, tz);
                if (tile == null) continue;
                Identifier tex = live.textureFor(tile, data.getStyle(), data.seaLevel(), data.getColorEpoch());
                if (tex == null) continue;
                double sx = width / 2.0 + (tile.minWX - centerX) * zoom / pb;
                double sy = height / 2.0 + (tile.minWZ - centerZ) * zoom / pb;
                double sw = LiveTileStore.TILE_PIXELS * zoom;
                double sh = LiveTileStore.TILE_PIXELS * zoom;
                if (sx + sw < 0 || sy + sh < 0 || sx > width || sy > height) continue;
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
        }
    }

    /** Cheap whole-map backdrop used until the visible tile textures have been created. */
    private void drawTilePreview(GuiGraphicsExtractor g, MapData data) {
        int mapW = data.getMapWidth(), mapH = data.getMapHeight();
        if (mapW <= 0 || mapH <= 0) return;
        Identifier tex = data.getTextureSmall();
        boolean small = tex != null;
        if (tex == null) tex = data.getTexture();
        if (tex == null) return;
        int srcW = small ? Math.max(1, mapW / 4) : mapW;
        int srcH = small ? Math.max(1, mapH / 4) : mapH;
        double x0 = width / 2.0 - centerPx() * zoom;
        double y0 = height / 2.0 - centerPz() * zoom;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x0, (float) y0);
        pose.scale((float) zoom, (float) zoom);
        g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, tex,
                0, 0, 0f, 0f, mapW, mapH, srcW, srcH, srcW, srcH);
        pose.popMatrix();
    }

    /** Draws only the tiles intersecting the viewport, one independent texture per tile. */
    private void drawMapTiles(GuiGraphicsExtractor g, MapData data, TileStore tiles, double uiScale) {
        tiles.beginFrame();
        boolean useSmall = zoom * uiScale < 0.5;
        int pb = Math.max(1, data.getPixelBlocks());
        double worldPerScreenPx = pb / Math.max(1.0e-6, zoom);
        double left = centerX - (width / 2.0) * worldPerScreenPx;
        double right = centerX + (width / 2.0) * worldPerScreenPx;
        double top = centerZ - (height / 2.0) * worldPerScreenPx;
        double bottom = centerZ + (height / 2.0) * worldPerScreenPx;
        int tx0 = Math.max(0, tiles.tileIndexFromWorldX(left));
        int tx1 = Math.min(tiles.tilesX() - 1, tiles.tileIndexFromWorldX(right));
        int tz0 = Math.max(0, tiles.tileIndexFromWorldZ(top));
        int tz1 = Math.min(tiles.tilesY() - 1, tiles.tileIndexFromWorldZ(bottom));
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                TileStore.MapTile tile = tiles.getTile(tx, tz);
                if (tile == null) continue;
                // Keep the tile's exact double position and scale through the pose matrix instead
                // of rounding every tile independently: independent rounding leaves 1px seams and
                // makes panning jump in GUI-pixel steps (very visible at GUI scale 4).
                double sx = width / 2.0 + (tile.minWX - centerX) * zoom / pb;
                double sy = height / 2.0 + (tile.minWZ - centerZ) * zoom / pb;
                double sw = tile.width * zoom;
                double sh = tile.height * zoom;
                if (sx + sw < 0 || sy + sh < 0 || sx > width || sy > height) continue;
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate((float) sx, (float) sy);
                if (useSmall) {
                    // 1/4 box-filtered copy plus a fractional scale so the destination still spans
                    // exactly this tile (no seams when the small path is selected).
                    pose.scale((float) (zoom * tile.width / tile.smallWidth),
                            (float) (zoom * tile.height / tile.smallHeight));
                    g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, tile.textureSmall,
                            0, 0, 0f, 0f, tile.smallWidth, tile.smallHeight,
                            tile.smallWidth, tile.smallHeight, tile.smallWidth, tile.smallHeight);
                } else {
                    pose.scale((float) zoom, (float) zoom);
                    g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, tile.texture,
                            0, 0, 0f, 0f, tile.width, tile.height,
                            tile.width, tile.height, tile.width, tile.height);
                }
                pose.popMatrix();
            }
        }
    }

    /** Layer 2: things drawn on top of the map - biome boundaries, player marker, render-distance box. */
    private void drawMapOverlays(GuiGraphicsExtractor g, MapData data) {
        if (data.isBiomeOverlay()) {
            if (data.isTile2DMode()) {
                drawTileBiomeOverlay(g, data);
            } else if (data.getMapWidth() > 0) {
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
        }
        if (data.isTile2DMode()) {
            drawTileLoadingLabels(g, data);
        }
        drawPlayerArrow(g);
        if (data.isRenderDistanceOverlay()) {
            drawRenderDistanceBox(g);
        }
    }

    private void drawTileBiomeOverlay(GuiGraphicsExtractor g, MapData data) {
        data.ensureBiomeTexture();
        Identifier tex = data.getBiomeTexture();
        int w = data.getBiomeTextureWidth();
        int h = data.getBiomeTextureHeight();
        if (tex == null || w <= 0 || h <= 0) return;
        double x0 = width / 2.0 + (data.getBiomeTextureMinWX() - centerPx()) * zoom;
        double y0 = height / 2.0 + (data.getBiomeTextureMinWZ() - centerPz()) * zoom;
        // Same exact double-pose path as the map tiles: rounding the blit position independently
        // makes the overlay slide by one GUI pixel while the tiles move continuously.
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x0, (float) y0);
        pose.scale((float) zoom, (float) zoom);
        g.blit(RenderPipelines.GUI_TEXTURED, tex,
                0, 0, 0f, 0f, w, h, w, h, w, h);
        pose.popMatrix();
    }

    private void drawTileLoadingLabels(GuiGraphicsExtractor g, MapData data) {
        if (!VoxyMapConfig.TILE_LOADING_LABELS.get()) return;
        LiveTileStore live = data.getLiveTiles();
        if (live == null) return;
        double tileScreen = LiveTileStore.TILE_PIXELS * zoom;
        if (tileScreen < 24) return; // too dense to be readable
        int pb = Math.max(1, data.getPixelBlocks());
        double worldPerScreenPx = pb / Math.max(1.0e-6, zoom);
        double left = centerX - (width / 2.0) * worldPerScreenPx;
        double right = centerX + (width / 2.0) * worldPerScreenPx;
        double top = centerZ - (height / 2.0) * worldPerScreenPx;
        double bottom = centerZ + (height / 2.0) * worldPerScreenPx;
        int tx0 = live.tileIndexX(left), tx1 = live.tileIndexX(right);
        int tz0 = live.tileIndexZ(top), tz1 = live.tileIndexZ(bottom);
        float labelScale = Minecraft.getInstance().getWindow().getGuiScale() >= 4.0 ? 0.5f : 1.0f;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                LiveTileStore.LiveTile tile = live.get(tx, tz);
                int state = tile == null ? LiveTileStore.LOAD_PENDING : live.loadingState(tile);
                if (state == LiveTileStore.LOAD_READY) continue;
                long tileWX = tile != null ? tile.minWX : live.tileMinWX(tx);
                long tileWZ = tile != null ? tile.minWZ : live.tileMinWZ(tz);
                double sx = width / 2.0 + (tileWX - centerX) * zoom / pb + tileScreen / 2.0;
                double sy = height / 2.0 + (tileWZ - centerZ) * zoom / pb + tileScreen / 2.0;
                if (sx < 0 || sx > width || sy < 0 || sy > height) continue;
                Component text = Component.translatable(state == LiveTileStore.LOAD_PENDING
                        ? "voxymap.tile.pending" : "voxymap.tile.loading");
                int color = state == LiveTileStore.LOAD_LOADING ? 0xFFFF55 : 0xD0D0D0;
                g.pose().pushMatrix();
                g.pose().translate((float) sx, (float) sy);
                g.pose().scale(labelScale, labelScale);
                int tw = font.width(text);
                g.fill(-tw / 2 - 2, -6, tw / 2 + 2, 7, 0x99000000);
                g.text(font, text, -tw / 2, -4, color, true);
                g.pose().popMatrix();
            }
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
        if (view3d) {
            lines.add(String.format(Locale.ROOT, "3d: mesh=%s building=%s frames=%d render=%.1fms",
                    mesh3d != null, building3d, dbg3dFrames, dbg3dNanos / 1_000_000.0));
            int[] dbgEdges = data.isBiomeOverlay() ? data.getBiomeEdgeSnapshot() : null;
            lines.add("labels3d: drawn=" + labels3dDrawn + " / " + labels3dTotal);
            lines.add(String.format(Locale.ROOT, "overlay: biome=%s rd=%s",
                    dbgEdges == null ? "off" : (dbgEdges.length + "px"),
                    data.isRenderDistanceOverlay() ? "on (analytic)" : "off"));
            if (mesh3d != null) {
                lines.add(String.format(Locale.ROOT, "lod: verts=%d tris=%d baseStep=%dpx near=%d mid=%d far=%d",
                        mesh3d.vertices.length / 7, mesh3d.indices.length / 3, mesh3d.step,
                        VoxyMapConfig.LOD_STEP_NEAR.get(), VoxyMapConfig.LOD_STEP_MID.get(),
                        VoxyMapConfig.LOD_STEP_FAR.get()));
            }
            if (cam3d != null) {
                lines.add(String.format(Locale.ROOT, "cam: t=(%.0f,%.0f,%.0f) yaw=%.2f pitch=%.2f dist=%.0f",
                        cam3d.targetX, cam3d.targetY, cam3d.targetZ, cam3d.yaw, cam3d.pitch, cam3d.distance));
            }
        }
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

    // ---- 2D/3D switching: always seamless, with a short linear rotation on the way back ----
    private float exit3dT = -1f;
    private float exitFromYaw;
    private float exitFromPitch;

    private void toggle3D() {
        toggle3D(false);
    }

    private void toggle3D(boolean restoreCamera) {
        MapData data = MapData.INSTANCE;
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
        } else {
            // seamless switch: keep the 2D focus, match the 2D scale, look straight down
            focusX = centerX;
            focusZ = centerZ;
            cam3d.setTarget(focusX, groundMeshY(focusX, focusZ), focusZ);
            double pb = data.getPixelBlocks();
            double tanHalf = Math.tan(Math.toRadians(fov3d * 0.5));
            double dist = pb * height / (2.0 * Math.max(0.05, zoom) * Math.max(1e-4, tanHalf));
            dist = Math.max(minDistance3d(), Math.min(maxDistance3d(), dist));
            cam3d.distance = (float) dist;
            cam3d.yaw = 0f;
            cam3d.pitch = PITCH_STRAIGHT_DOWN;
        }
        cam3dDirty = true;
        data.skipBiomeLabels = true;
        if (VoxyMapConfig.AUTO_HIDE_CONTOURS_3D.get()
                && VoxyMapConfig.DRAW_CONTOURS.get() && MapData.contourOverride == null) {
            // contours are a 2D decoration: hide them in 3D (cheap colour-only re-render)
            MapData.contourOverride = Boolean.FALSE;
            data.requestColorRerender();
        }
        ensureMesh3d();
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
            // invert the enter formula so the 2D scale matches the 3D view
            double pb = data.getPixelBlocks();
            double tanHalf = Math.tan(Math.toRadians(fov3d * 0.5));
            double z = pb * height / (2.0 * Math.max(1.0, cam3d.distance) * Math.max(1e-4, tanHalf));
            zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
        }
        restore3dOverrides();
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
        fov3d = VoxyMapConfig.FOV_3D.get();
    }

    private void ensureMesh3d() {
        MapData data = MapData.INSTANCE;
        if (!data.isReady() || data.getMapWidth() <= 0) return;
        int stepNear = Math.max(1, VoxyMapConfig.LOD_STEP_NEAR.get());
        int stepMid = Math.max(stepNear, VoxyMapConfig.LOD_STEP_MID.get());
        int stepFar = Math.max(stepMid, VoxyMapConfig.LOD_STEP_FAR.get());
        double wantVScale = VoxyMapConfig.VERTICAL_SCALE_3D.get();
        boolean wantLodDebug = VoxyMapConfig.LOD_DEBUG_COLORS_3D.get();
        double nearRadius = Math.max(200.0, VoxyMapConfig.LOD_NEAR_RADIUS.get());
        double midRadius = Math.max(nearRadius + 100.0, VoxyMapConfig.LOD_MID_RADIUS.get());
        var player = Minecraft.getInstance().player;
        double cx;
        double cz;
        if (cam3d != null && !Double.isNaN(cam3d.targetX)) {
            // LOD is centred on the camera itself, not on the look-at point: detail should be high
            // where the viewer is, which is what determines the visible pixel density
            cx = cam3d.eyeX();
            cz = cam3d.eyeZ();
        } else if (player != null) {
            cx = player.getX();
            cz = player.getZ();
        } else {
            cx = data.getMinWX() + data.getMapWidth() * (double) data.getPixelBlocks() / 2.0;
            cz = data.getMinWZ() + data.getMapHeight() * (double) data.getPixelBlocks() / 2.0;
        }
        double targetX = cam3d != null ? cam3d.targetX : cx;
        double targetZ = cam3d != null ? cam3d.targetZ : cz;
        // the LOD mesh is built once and only rebuilt when its settings change or the camera travels
        // far away (rebuilding on every small movement made LOD switching feel sluggish)
        double camDist0 = cam3d != null ? cam3d.distance : 1500.0;
        boolean zoomChanged = builtCamDist > 0.0
                && Math.abs(camDist0 - builtCamDist) / builtCamDist
                > VoxyMapConfig.LOD_ZOOM_REBUILD_PCT.get() / 100.0;
        boolean upToDate = mesh3d != null
                && builtPixelBlocks == data.getPixelBlocks()
                && builtColorEpoch == data.getColorEpoch()
                && builtStepA == stepNear && builtStepB == stepMid && builtStepC == stepFar
                && builtVScale3d == wantVScale
                && builtLodDebug == wantLodDebug
                && builtNearRadius == nearRadius
                && builtMidRadius == midRadius
                && !zoomChanged
                // compare the look-at target, not the orbiting eye: rotating the camera used to
                // rebuild the whole mesh every ~300 blocks of eye travel
                && Math.abs(targetX - builtTargetX) < VoxyMapConfig.LOD_REBUILD_DISTANCE.get()
                && Math.abs(targetZ - builtTargetZ) < VoxyMapConfig.LOD_REBUILD_DISTANCE.get();
        if (upToDate || building3d) return;
        if (System.nanoTime() < meshRetryAtNs) return;
        building3d = true;
        // detail is expressed as "every N x N map pixels get one vertex"
        int[] stepsPx = {stepNear, stepMid, stepFar};
        double camDist = cam3d != null ? cam3d.distance : 1500.0;
        // fixed layer radii (spherical): the detail size does not change with zoom
        double[] radii = {nearRadius, midRadius, 1.0e9};
        final int colorEpoch0 = data.getColorEpoch();
        final int pixelBlocks0 = data.getPixelBlocks();
        final double ftargetX = targetX;
        final double ftargetZ = targetZ;
        final double fcx = cx;
        final double fcz = cz;
        final double fey = cam3d != null ? cam3d.eyeY() : 120.0;
        final double fv = wantVScale;
        final int fStepA = stepNear;
        final int fStepB = stepMid;
        final int fStepC = stepFar;
        final int[] fSteps = stepsPx;
        final boolean fLodDebug = wantLodDebug;
        final double fCamDist = camDist;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            Map3D.Mesh mesh = null;
            try {
                mesh = Map3D.buildLod(data, fcx, fey, fcz, fv, fSteps, radii, fLodDebug);
            } catch (Throwable t) {
                // never leave building3d stuck: a thrown background build would otherwise disable
                // the 3D mesh for the rest of the session
                t.printStackTrace();
            }
            final Map3D.Mesh built = mesh;
            Minecraft.getInstance().execute(() -> {
                try {
                    if (built != null) {
                        mesh3d = built;
                        builtStepA = fStepA;
                        builtStepB = fStepB;
                        builtStepC = fStepC;
                        builtColorEpoch = colorEpoch0;
                        builtPixelBlocks = pixelBlocks0;
                        builtVScale3d = fv;
                        builtLodDebug = fLodDebug;
                        builtCamDist = fCamDist;
                        builtNearRadius = nearRadius;
                        builtMidRadius = midRadius;
                        builtCenterX = fcx;
                        builtCenterZ = fcz;
                        builtTargetX = ftargetX;
                        builtTargetZ = ftargetZ;
                        meshRetryAtNs = 0L;
                        cam3dDirty = true;
                    } else {
                        // empty mesh or a failed build: back off instead of retrying every frame
                        meshRetryAtNs = System.nanoTime() + 3_000_000_000L;
                    }
                } finally {
                    building3d = false;
                }
            });
        });
    }

    /** Lazily creates the CPU framebuffer + the dynamic texture that displays it. */
    private void ensureSoft3d() {
        Minecraft mc = Minecraft.getInstance();
        int pct = Math.max(10, Math.min(150, VoxyMapConfig.RENDER_SCALE_3D.get()));
        // GUI logical size * gui scale = physical window resolution
        float guiScale = (float) mc.getWindow().getGuiScale();
        int w = Math.max(2, (int) (width * guiScale * pct / 100f));
        int wh = Math.max(2, (int) (height * guiScale * pct / 100f));
        if (fb3d != null && fb3d.width == w && fb3d.height == wh) return;
        // stale background frames are identified by this generation: bump it before replacing the
        // framebuffer/texture pair so their callbacks are discarded instead of writing a closed image
        soft3dGen++;
        if (tex3dId != null) {
            // TextureManager.release closes the old DynamicTexture (and its NativeImage)
            mc.getTextureManager().release(tex3dId);
            tex3dId = null;
        }
        fb3d = new Map3DSoft.Framebuffer(w, wh);
        img3d = new NativeImage(w, wh, false);
        tex3dDyn = new DynamicTexture(() -> "voxymap_3d_soft", img3d);
        tex3dId = Identifier.fromNamespaceAndPath("voxymap", "3d_soft");
        mc.getTextureManager().register(tex3dId, tex3dDyn);
        cam3dDirty = true;
    }

    /**
     * Re-renders the CPU view when the camera changed. Rendering runs off-thread so dragging never
     * blocks the client; the finished image is copied into the dynamic texture on the main thread.
     */
    private void updateSoft3d() {
        if (screenClosed || !view3d || mesh3d == null || cam3d == null) return;
        // the FOV setting is live: applying it here avoids forcing the player to reopen the map
        int wantFov = Math.max(30, Math.min(110, VoxyMapConfig.FOV_3D.get()));
        if (wantFov != (int) fov3d) {
            fov3d = wantFov;
            cam3dDirty = true;
        }
        ensureSoft3d();
        if (soft3dPending) return;
        boolean dirty = cam3dDirty;
        long now = System.nanoTime();
        boolean settled = now - lastCamChangeNs > 300_000_000L;
        if (dirty) {
            // camera moved: render immediately without antialiasing (keeps dragging responsive)
            cam3dDirty = false;
            lastCamChangeNs = now;
            pendingFxaa = VoxyMapConfig.ANTIALIAS_3D.get();
            soft3dPending = true;
            final int gen = soft3dGen;
            final Map3D.Camera cam = new Map3D.Camera();
            cam.targetX = cam3d.targetX;
            cam.targetY = cam3d.targetY;
            cam.targetZ = cam3d.targetZ;
            cam.yaw = cam3d.yaw;
            cam.pitch = cam3d.pitch;
            cam.distance = cam3d.distance;
            final Map3D.Mesh mesh = mesh3d;
            final Map3DSoft.Framebuffer fb = fb3d;
            final float fov = fov3d;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                long t0 = System.nanoTime();
                MapData md = MapData.INSTANCE;
                int[] edges = md.isBiomeOverlay() ? md.getBiomeEdgeSnapshot() : null;
                boolean rdOn = md.isRenderDistanceOverlay();
                var pl = Minecraft.getInstance().player;
                double rdCx = pl != null ? pl.getX() : 0;
                double rdCz = pl != null ? pl.getZ() : 0;
                double rdDist = Minecraft.getInstance().options.renderDistance().get() * 16.0;
                final int[] abgr;
                try {
                    Map3DSoft.render(mesh, cam, fov, fb, 0xFF0D0D14, md,
                            VoxyMapConfig.LOD_DEBUG_COLORS_3D.get(), edges,
                            rdCx, rdCz, rdDist, rdOn);
                    // ARGB -> ABGR conversion runs here, on the background thread; the main thread
                    // only does one bulk write into the NativeImage
                    abgr = Map3DSoft.toAbgr(fb);
                } catch (Throwable t) {
                    // a failed background render must not leave the 3D view stuck on "render pending"
                    t.printStackTrace();
                    Minecraft.getInstance().execute(() -> soft3dPending = false);
                    return;
                }
                long nanos = System.nanoTime() - t0;
                Minecraft.getInstance().execute(() -> {
                    try {
                        // discard frames for a framebuffer/texture pair that is gone or for a
                        // screen that already closed: writing them would touch a closed NativeImage
                        if (screenClosed || gen != soft3dGen || img3d == null) return;
                        dbg3dNanos = nanos;
                        uploadAbgr(abgr, fb.width, fb.height);
                        dbg3dFrames++;
                    } finally {
                        soft3dPending = false;
                    }
                });
            });
            return;
        }
        if (pendingFxaa && settled) {
            // camera stopped: run one antialiasing pass over the finished image, off the main thread
            pendingFxaa = false;
            if (screenClosed || img3d == null || tex3dDyn == null || fb3d == null) return;
            soft3dPending = true;
            final int gen = soft3dGen;
            final Map3DSoft.Framebuffer fb = fb3d;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                long t0 = System.nanoTime();
                final int[] abgr;
                try {
                    Map3DSoft.applyFxaa(fb);
                    abgr = Map3DSoft.toAbgr(fb);
                } catch (Throwable t) {
                    t.printStackTrace();
                    Minecraft.getInstance().execute(() -> soft3dPending = false);
                    return;
                }
                long nanos = System.nanoTime() - t0;
                Minecraft.getInstance().execute(() -> {
                    try {
                        if (screenClosed || gen != soft3dGen || img3d == null) return;
                        dbg3dNanos = nanos;
                        uploadAbgr(abgr, fb.width, fb.height);
                    } finally {
                        soft3dPending = false;
                    }
                });
            });
        }
    }

    /**
     * Writes a prepared ABGR buffer into the dynamic texture. 26.2 exposes the native memory through
     * {@code NativeImage.getPixelBytes()}, so this can be one bulk copy instead of one call per pixel
     * (which used to cost 10-60 ms per camera change on the main thread). The method is looked up
     * reflectively because the 26.1.2 target has no such API; there it falls back to setPixelABGR.
     */
    private void uploadAbgr(int[] abgr, int w, int h) {
        if (img3d == null || img3d.isClosed() || tex3dDyn == null) return;
        if (img3d.getWidth() != w || img3d.getHeight() != h) return;
        if (writePixelsBulk(img3d, abgr, w, h)) {
            tex3dDyn.upload();
            return;
        }
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                img3d.setPixelABGR(x, y, abgr[row + x]);
            }
        }
        tex3dDyn.upload();
    }

    private static java.lang.reflect.Method pixelBytesMethod;
    private static boolean pixelBytesResolved;

    /** @return true when the bulk path exists and was used */
    private static boolean writePixelsBulk(NativeImage image, int[] abgr, int w, int h) {
        try {
            if (!pixelBytesResolved) {
                pixelBytesResolved = true;
                pixelBytesMethod = NativeImage.class.getMethod("getPixelBytes");
            }
            if (pixelBytesMethod == null) return false;
            java.nio.ByteBuffer buf = (java.nio.ByteBuffer) pixelBytesMethod.invoke(image);
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(abgr, 0, w * h);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            pixelBytesMethod = null;
            return false;
        }
    }

    private void releaseSoft3d() {
        // bump the generation first so any in-flight frame is discarded before resources go away
        soft3dGen++;
        if (tex3dId != null) {
            // TextureManager.release closes the DynamicTexture, which also closes its NativeImage
            Minecraft.getInstance().getTextureManager().release(tex3dId);
            tex3dId = null;
        }
        tex3dDyn = null;
        img3d = null;
        fb3d = null;
    }

    private void drawStatusText(GuiGraphicsExtractor g, MapData data) {
        if (data.isTile2DMode()) {
            if (!data.hasLiveStore()) {
                drawStatusLine(g, Component.translatable("voxymap.ui.no_data"), 24, COL_TEXT);
            }
            if (data.isStyleChanging()) {
                drawStatusLine(g, Component.translatable("voxymap.ui.styleChanging"), 24, COL_ACCENT);
            }
            return;
        }
        if (data.getMapWidth() <= 0) {
            // first build: use the same top line and warning colour as a refresh, so both states match
            Component msg = data.isLoading()
                    ? Component.translatable("voxymap.ui.updating", data.getProgress())
                    : (data.outOfMemory
                        ? Component.translatable("voxymap.ui.outOfMemory")
                        : (data.getError().isEmpty()
                            ? Component.translatable("voxymap.ui.no_data")
                            : Component.literal(data.getError())));
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
        double maxT = Math.max(4000.0, cam3d.distance * 10.0);
        double step = Math.max(1.0, cam3d.distance * 0.003);
        double prevT = 0.0;
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
                // display the picked point in world blocks, not in the vertically stretched space
                return new double[]{hx, groundYAt(hx, hz), hz};
            }
            prevT = t;
        }
        return null;
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
        drawButton(g, d3x, selY, d3w, d3, d3hov, !busy && !data.isTile2DMode());
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
        if (!data.isReady() || data.getMapWidth() <= 0) return;
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
            double wy = groundMeshY(wx, wz);
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
        // reached both when the map is closed and when another screen temporarily replaces it
        // (e.g. the config screen); init() clears screenClosed again on return
        screenClosed = true;
        MapData.INSTANCE.onMapClosed();
        releaseSoft3d();
        releaseArrowTexture();
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
                MapData data = MapData.INSTANCE;
                if (view3d && cam3d != null) {
                    cam3d.setTarget(p.getX(), groundMeshY(p.getX(), p.getZ()), p.getZ());
                    // straight down on the player arrow with the rotation reset
                    cam3d.yaw = 0f;
                    cam3d.pitch = PITCH_STRAIGHT_DOWN;
                    cam3dDirty = true;
                } else {
                    centerX = p.getX();
                    centerZ = p.getZ();
                }
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
            if (!data.isLoading() && !data.isStyleChanging() && !data.isTile2DMode()) {
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
            // left = pan, middle = orbit (handled by mouseDragged); right = the same context menu,
            // positioned at the ray-picked terrain point
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
            // middle-drag in 2D: switch to the 3D view on the first movement and keep orbiting
            pending3dOrbit = true;
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
        if (pending3dOrbit && !view3d) {
            // continue the same drag as a 3D orbit (seamless: scale matched, looking straight down)
            toggle3D();
            pending3dOrbit = false;
        }
        if (sliderDragging) {
            sliderValue = scaleSliderValueAt(mx);
            return true;
        }
        if (view3d && cam3d != null) {
            if (e.button() == 0) {
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
            cam3dDirty = true;
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
        // a middle-click that never turned into a drag must not leave the "switch to 3D" pending:
        // otherwise the next left-drag jumped into the 3D view out of nowhere
        if (e.button() == 2) pending3dOrbit = false;
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
        if (view3d && cam3d != null) {
            double zoomStep = 1.0 + 0.15 * VoxyMapConfig.ZOOM_SENS_3D.get();
            // a horizontal-only scroll (scrollY == 0) must not zoom the 3D view
            if (scrollY == 0) return false;
            cam3d.distance = (float) Math.max(minDistance3d(),
                    Math.min(maxDistance3d(),
                            cam3d.distance * (scrollY > 0 ? 1.0 / zoomStep : zoomStep)));
            cam3dDirty = true;
            return true;
        }
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

