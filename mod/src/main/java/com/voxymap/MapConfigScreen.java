package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-drawn config screen (NeoForge 1.21.1 build). Mirrors the Fabric screen 1:1: grouped tabs,
 * localized names/descriptions, sliders with typed input, draft + Cancel/Apply/Done/Reset and the
 * same open/close animation.
 */
public class MapConfigScreen extends Screen {
    private enum Group {
        REFRESH("voxymap.configuration.refresh"),
        DATA("voxymap.configuration.data"),
        RENDERING("voxymap.configuration.rendering"),
        UI("voxymap.configuration.ui");

        final String labelKey;

        Group(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private record Row(Group group, String name, String descKey, ModConfigSpec.ConfigValue<?> value,
                       int min, int max, String[] options, boolean sep) {
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
    private final List<Row> rows = new ArrayList<>();
    private final Map<Row, Object> draft = new HashMap<>();
    private Group group = Group.REFRESH;
    private int scroll;
    private Row dragging;
    private Row editing;
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
        add(Group.REFRESH, "autoRefresh", VoxyMapConfig.AUTO_REFRESH, 0, 0, null, true);
        add(Group.REFRESH, "refreshDistanceChunks", VoxyMapConfig.REFRESH_DISTANCE_CHUNKS, 16, 1024, null);
        add(Group.REFRESH, "initialRefreshDelayMinutes", VoxyMapConfig.INITIAL_REFRESH_DELAY_MINUTES, 0, 60, null);

        add(Group.DATA, "mapScale", VoxyMapConfig.MAP_SCALE, 1, 8, null, true);
        add(Group.DATA, "maxLodLevel", VoxyMapConfig.MAX_LOD_LEVEL, 0, 4, null);
        add(Group.DATA, "cacheKeepCount", VoxyMapConfig.CACHE_KEEP_COUNT, 1, 100, null);
        add(Group.DATA, "autoSeaLevel", VoxyMapConfig.AUTO_SEA_LEVEL, 0, 0, null, true);
        add(Group.DATA, "seaLevelOverride", VoxyMapConfig.SEA_LEVEL_OVERRIDE, -64, 384, null);

        add(Group.RENDERING, "defaultStyle", VoxyMapConfig.DEFAULT_STYLE, 0, 0, MapStyles.NAMES, true);
        add(Group.RENDERING, "defaultZoom", VoxyMapConfig.DEFAULT_ZOOM, 0, 0, null);
        add(Group.RENDERING, "rememberZoom", VoxyMapConfig.REMEMBER_ZOOM, 0, 0, null);

        add(Group.RENDERING, "drawContours", VoxyMapConfig.DRAW_CONTOURS, 0, 0, null, true);
        add(Group.RENDERING, "contourDensity", VoxyMapConfig.CONTOUR_DENSITY, 1, 24, null);
        add(Group.RENDERING, "minContourArea", VoxyMapConfig.MIN_CONTOUR_AREA, 0, 1000, null);
        add(Group.RENDERING, "drawHillshade", VoxyMapConfig.DRAW_HILLSHADE, 0, 0, null);
        add(Group.RENDERING, "drawWaterDepth", VoxyMapConfig.DRAW_WATER_DEPTH, 0, 0, null);
        add(Group.RENDERING, "adaptiveColorRamp", VoxyMapConfig.ADAPTIVE_COLOR_RAMP, 0, 0, null);
        add(Group.RENDERING, "landMaxRamp", VoxyMapConfig.MIN_MAX_RAMP, 0, 0, null);

        add(Group.RENDERING, "waterBlur", VoxyMapConfig.WATER_BLUR, 0, 0, null, true);
        add(Group.RENDERING, "waterBlurRadius", VoxyMapConfig.WATER_BLUR_RADIUS, 1, 16, null);
        add(Group.RENDERING, "waterBlurPasses", VoxyMapConfig.WATER_BLUR_PASSES, 1, 5, null);
        add(Group.RENDERING, "denoise", VoxyMapConfig.DENOISE, 0, 0, null);
        add(Group.RENDERING, "fillHoles", VoxyMapConfig.FILL_HOLES, 0, 0, null);

        add(Group.RENDERING, "showBiomes", VoxyMapConfig.SHOW_BIOMES, 0, 0, null, true);
        add(Group.RENDERING, "biomeLabelMinArea", VoxyMapConfig.BIOME_LABEL_MIN_AREA, 1, 5000, null);
        add(Group.RENDERING, "biomeLabelBackground", VoxyMapConfig.BIOME_LABEL_BACKGROUND, 0, 0, null);
        add(Group.RENDERING, "arrowStyle", VoxyMapConfig.ARROW_STYLE, 0, 0, new String[]{"clean", "classic"});
        add(Group.RENDERING, "showRenderDistance", VoxyMapConfig.SHOW_RENDER_DISTANCE, 0, 0, null);
        add(Group.RENDERING, "releaseTexturesOnClose", VoxyMapConfig.RELEASE_TEXTURES_ON_CLOSE, 0, 0, null);
        add(Group.RENDERING, "skipWorldRender", VoxyMapConfig.SKIP_WORLD_RENDER, 0, 0, null);

        add(Group.UI, "showCoordinates", VoxyMapConfig.SHOW_COORDINATES, 0, 0, null, true);
        add(Group.UI, "showHint", VoxyMapConfig.SHOW_HINT, 0, 0, null);
        add(Group.UI, "teleportEnabled", VoxyMapConfig.TELEPORT_ENABLED, 0, 0, null);
        add(Group.UI, "debugEnabled", VoxyMapConfig.DEBUG_ENABLED, 0, 0, null);

        for (Row r : rows) draft.put(r, r.value().get());
    }

    private void add(Group g, String name, ModConfigSpec.ConfigValue<?> value, int min, int max,
                     String[] options) {
        add(g, name, value, min, max, options, false);
    }

    private void add(Group g, String name, ModConfigSpec.ConfigValue<?> value, int min, int max,
                     String[] options, boolean sep) {
        rows.add(new Row(g, name, "voxymap.config.desc." + name, value, min, max, options, sep));
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

    private Object dv(Row row) {
        return draft.getOrDefault(row, row.value().get());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void set(Row row, Object value) {
        Object def = row.value().getDefault();
        Object coerced;
        if (def instanceof Boolean) coerced = Boolean.parseBoolean(String.valueOf(value));
        else if (def instanceof Integer) coerced = Integer.parseInt(String.valueOf(value));
        else if (def instanceof Double) coerced = Double.parseDouble(String.valueOf(value));
        else coerced = String.valueOf(value);
        draft.put(row, coerced);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyDraft() {
        for (Row row : rows) {
            ((ModConfigSpec.ConfigValue) row.value()).set(draft.get(row));
        }
    }

    private void resetDraft() {
        for (Row row : rows) draft.put(row, row.value().getDefault());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (!closing) closing = true;
    }

    private void finishClose() {
        // back to the screen that opened us (the map), not straight to the world
        Minecraft.getInstance().setScreen(parent);
    }

    private List<Row> visible() {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r.group() == group) out.add(r);
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

    private boolean isNumber(Row row) {
        Object v = dv(row);
        return (v instanceof Integer || v instanceof Double) && row.options() == null && !(v instanceof Boolean);
    }

    private int buttonY() {
        return height - BTN_H - 5;
    }

    private int tabWidth(Group g) {
        return font.width(Component.translatable(g.labelKey)) + 20;
    }

    private int btnWidth(Component label) {
        return font.width(label) + 12;
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
    public boolean mouseClicked(double mx, double my, int button) {
        if (closing) return true;
        int shiftY = (int) Math.round((1f - appearT) * 8f);

        int by = buttonY();
        if (my >= by && my <= by + BTN_H) {
            Component resetLabel = Component.translatable("voxymap.config.button.reset");
            Component cancelLabel = Component.translatable("voxymap.config.button.cancel");
            Component applyLabel = Component.translatable("voxymap.config.button.apply");
            Component doneLabel = Component.translatable("voxymap.config.button.done");
            int resetX = PAD;
            int doneX = width - PAD - btnWidth(doneLabel);
            int applyX = doneX - BTN_GAP - btnWidth(applyLabel);
            int cancelX = applyX - BTN_GAP - btnWidth(cancelLabel);
            if (mx >= resetX && mx <= resetX + btnWidth(resetLabel)) {
                resetDraft();
                return true;
            }
            if (mx >= doneX && mx <= doneX + btnWidth(doneLabel)) {
                applyDraft();
                closing = true;
                return true;
            }
            if (mx >= applyX && mx <= applyX + btnWidth(applyLabel)) {
                applyDraft();
                return true;
            }
            if (mx >= cancelX && mx <= cancelX + btnWidth(cancelLabel)) {
                closing = true;
                return true;
            }
        }

        int tx = PAD;
        for (Group g : Group.values()) {
            int tw = tabWidth(g);
            if (my >= 26 + shiftY && my < 26 + shiftY + TAB_H && mx >= tx && mx < tx + tw) {
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
        List<Row> vis = visible();
        int y = listTop() + shiftY;
        for (int i = scroll; i < Math.min(vis.size(), scroll + visibleRows()); i++) {
            Row row = vis.get(i);
            if (my >= y && my < y + ROW_H) {
                Object v = dv(row);
                if (v instanceof Boolean b) {
                    set(row, !b);
                } else if (row.options() != null) {
                    cycle(row);
                } else if (isNumber(row)) {
                    if (mx >= controlX() && mx <= controlX() + SLIDER_W) {
                        dragging = row;
                        applySlider(row, mx);
                    } else if (mx > controlX() + SLIDER_W && mx <= rowRight()) {
                        editing = row;
                        editText = String.valueOf(v);
                    }
                }
                return true;
            }
            y += ROW_H;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            applySlider(dragging, mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (editing == null) return super.charTyped(c, modifiers);
        boolean allowed = (c >= '0' && c <= '9')
                || (c == '.' && dv(editing) instanceof Double)
                || (c == '-' && editing.min() < 0 && editText.isEmpty());
        if (!allowed) return true;
        if (editText.length() < 12) editText += c;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editing != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editing = null;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void commitEdit() {
        Row row = editing;
        editing = null;
        if (row == null) return;
        Object v = dv(row);
        try {
            if (v instanceof Integer) {
                int parsed = Integer.parseInt(editText.trim());
                set(row, Math.max(row.min(), Math.min(row.max(), parsed)));
            } else if (v instanceof Double) {
                double parsed = Double.parseDouble(editText.trim());
                set(row, Math.max(0.04, Math.min(8.0, Math.round(parsed * 100.0) / 100.0)));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void cycle(Row row) {
        String[] opts = row.options();
        String cur = String.valueOf(dv(row));
        int idx = 0;
        for (int i = 0; i < opts.length; i++) if (opts[i].equals(cur)) idx = i;
        set(row, opts[(idx + 1) % opts.length]);
    }

    private void applySlider(Row row, double mx) {
        double ratio = Math.max(0, Math.min(1, (mx - controlX()) / (double) SLIDER_W));
        Object v = dv(row);
        if (v instanceof Integer) {
            int val = row.min() + (int) Math.round(ratio * (row.max() - row.min()));
            set(row, val);
        } else if (v instanceof Double) {
            double val = 0.04 + ratio * (8.0 - 0.04);
            set(row, Math.round(val * 100.0) / 100.0);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long nowNs = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : (nowNs - lastNs) / 1_000_000_000f;
        lastNs = nowNs;
        dt = Math.min(0.1f, Math.max(0.001f, dt));
        lastDt = dt;
        if (closing) {
            appearT = Math.max(0f, appearT - dt / 0.18f);
            if (appearT <= 0f) {
                finishClose();
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
        g.drawString(font, getTitle().copy().append(Component.literal("  ·  Voxy Map")),
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
            g.drawString(font, label, tx + 10, 26 + dy + (TAB_H - font.lineHeight) / 2 + 1,
                    withAlpha(active ? COL_ACCENT : lerpColor(COL_TEXT, COL_ACCENT, th * 0.6f), fade));
            tx += tw + 4;
        }

        if (tabSwitchNs < 0L) tabSwitchNs = nowNs;
        float tabAge = (nowNs - tabSwitchNs) / 1_000_000_000f;
        List<Row> vis = visible();
        int y = listTop() + dy;
        for (int i = scroll; i < vis.size() && i < scroll + visibleRows(); i++) {
            Row row = vis.get(i);
            // staggered fade-in on real time: no flicker on tab switches, frame-rate independent
            float rowDelay = (i - scroll) * 0.03f;
            float rowT = fade * Math.min(1f, Math.max(0f, (tabAge - rowDelay) / 0.16f));
            boolean hover = mouseY >= y && mouseY < y + ROW_H;
            float rh = hover("row:" + i, hover, lastDt);
            g.fill(PAD, y, rowRight(), y + ROW_H, withAlpha(0x30101014, rowT));
            if (rh > 0.01f) {
                g.fill(PAD, y, rowRight(), y + ROW_H, withAlpha(COL_ROW_HOVER, rowT * rh * 0.8f));
            }
            if (row.sep()) {
                g.fill(PAD, y, rowRight(), y + 2, withAlpha(0x20FFFFFF, rowT));
            } else if (i > scroll) {
                g.fill(PAD, y, rowRight(), y + 1, withAlpha(0x20FFFFFF, rowT));
            }

            Component name = Component.translatable("voxymap.config." + row.name());
            g.drawString(font, name, PAD + 6, y + 5, withAlpha(COL_TEXT, rowT));
            Component desc = Component.translatable(row.descKey());
            g.drawString(font, Component.literal(desc.getString()), PAD + 6, y + 17,
                    withAlpha(COL_DIM, rowT));

            drawControl(g, row, y, rowT);
            y += ROW_H;
        }

        drawScrollbar(g, fade);
        drawBottomBar(g, mouseX, mouseY, fade);

        float veil = closing ? closeT() : 1f - appearT;
        if (veil > 0.002f) {
            int a = (int) (Math.min(1f, veil) * 255f);
            g.fill(0, 0, width, height, a << 24);
        }
    }

    private float closeT() {
        return Math.min(1f, 1f - appearT);
    }

    private void drawScrollbar(GuiGraphics g, float fade) {
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

    private void drawBottomBar(GuiGraphics g, int mouseX, int mouseY, float fade) {
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

    private void drawBottomButton(GuiGraphics g, int x, int y, Component label,
                                  int mouseX, int mouseY, float fade) {
        int w = btnWidth(label);
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + BTN_H;
        float bh = hover("btn:" + label.getString(), hover, lastDt);
        g.fill(x, y, x + w, y + BTN_H, withAlpha(COL_PANEL, fade));
        if (bh > 0.01f) {
            g.fill(x, y, x + w, y + BTN_H, withAlpha(COL_ROW_HOVER, fade * bh));
        }
        g.fill(x, y, x + w, y + 1, withAlpha(lerpColor(COL_BORDER, COL_ACCENT, bh), fade));
        g.drawString(font, label, x + 6, y + 6, withAlpha(lerpColor(COL_TEXT, 0xFFFFFFFF, bh), fade));
    }

    private void drawControl(GuiGraphics g, Row row, int rowY, float alpha) {
        int cx = controlX();
        int cy = rowY + ROW_H / 2;
        Object v = dv(row);

        if (v instanceof Boolean b) {
            int w = 46, h = 14;
            int x = cx + CONTROL_W - w;
            int y = cy - h / 2;
            g.fill(x, y, x + w, y + h, withAlpha(b ? COL_SELECTED : COL_PANEL_OFF, alpha));
            if (b) g.fill(x, y, x + w, y + 1, withAlpha(COL_ACCENT, alpha));
            Component label = Component.translatable(b ? "voxymap.config.on" : "voxymap.config.off");
            g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3,
                    withAlpha(b ? COL_ACCENT : COL_TEXT, alpha));
            return;
        }

        if (row.options() != null) {
            String cur = String.valueOf(v);
            int w = CONTROL_W, h = 14;
            int x = cx, y = cy - h / 2;
            g.fill(x, y, x + w, y + h, withAlpha(COL_PANEL_OFF, alpha));
            g.fill(x, y, x + w, y + 1, withAlpha(COL_BORDER, alpha));
            g.drawString(font, Component.literal(cur), x + (w - font.width(cur)) / 2, y + 3,
                    withAlpha(COL_ACCENT, alpha));
            return;
        }

        if (v instanceof Integer n) {
            int range = Math.max(1, row.max() - row.min());
            double t = (n - row.min()) / (double) range;
            int x = cx;
            g.fill(x, cy - 1, x + SLIDER_W, cy + 1, withAlpha(0x60FFFFFF, alpha));
            int knobX = x + (int) Math.round(t * SLIDER_W);
            g.fill(knobX - 3, cy - 5, knobX + 3, cy + 5, withAlpha(0xFFFFFFFF, alpha));
            g.fill(x, cy - 1, knobX, cy + 1, withAlpha(COL_ACCENT, alpha));
            drawValue(g, row, String.valueOf(n), cx + SLIDER_W + 6, cy - 4, alpha);
            return;
        }

        if (v instanceof Double d) {
            double t = Math.max(0, Math.min(1, (d - 0.04) / (8.0 - 0.04)));
            int x = cx;
            g.fill(x, cy - 1, x + SLIDER_W, cy + 1, withAlpha(0x60FFFFFF, alpha));
            int knobX = x + (int) Math.round(t * SLIDER_W);
            g.fill(knobX - 3, cy - 5, knobX + 3, cy + 5, withAlpha(0xFFFFFFFF, alpha));
            g.fill(x, cy - 1, knobX, cy + 1, withAlpha(COL_ACCENT, alpha));
            drawValue(g, row, String.format(java.util.Locale.ROOT, "%.2f", d),
                    cx + SLIDER_W + 6, cy - 4, alpha);
        }
    }

    private void drawValue(GuiGraphics g, Row row, String text, int x, int y, float alpha) {
        if (editing == row) {
            String shown = editText + "_";
            g.fill(x - 2, y - 1, x + Math.max(font.width(shown), 24) + 2, y + 10,
                    withAlpha(COL_SELECTED, alpha));
            g.drawString(font, Component.literal(shown), x, y, withAlpha(COL_ACCENT, alpha));
        } else {
            g.drawString(font, Component.literal(text), x, y, withAlpha(COL_TEXT, alpha));
        }
    }
}
