package com.voxymap.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * 1.21.1 stand-in for 26.2's {@code net.minecraft.client.gui.GuiGraphicsExtractor}.
 *
 * <p>26.2 splits the GUI into an extract phase ({@code extractRenderState}) that records draw
 * commands and a batched render phase. 1.21.1 draws immediately through {@link GuiGraphics}, so this
 * adapter re-implements the extractor's surface on top of it: every call forwards straight to the
 * vanilla {@link GuiGraphics}, and {@link #pose()} maps 26.2's 2D matrix stack onto the vanilla
 * {@code PoseStack}. Keeping the same method names and argument order is what lets the ported map
 * screens stay a near-verbatim copy of the 26.2 sources.</p>
 *
 * <p>Only the operations the map screens use are provided (fill / text / centeredText / blit /
 * scissor / pose). The render-pipeline argument of {@code blit} carries no behaviour on 1.21.1 and is
 * accepted only to keep the call sites identical.</p>
 */
public final class GuiGraphicsExtractor {
    private final GuiGraphics graphics;
    private final Pose2D pose;

    public GuiGraphicsExtractor(GuiGraphics graphics) {
        this.graphics = graphics;
        this.pose = new Pose2D(graphics.pose());
    }

    /** The wrapped 1.21.1 graphics, for code that needs a vanilla-only call. */
    public GuiGraphics vanilla() {
        return graphics;
    }

    /**
     * Flushes every pending GUI batch. 1.21.1 batches fills and text separately and flushes them in
     * render-type order, so a modal drawn later can otherwise end up underneath earlier text.
     */
    public void flush() {
        graphics.flush();
    }

    public Pose2D pose() {
        return pose;
    }

    // ---------------------------------------------------------------- fill

    public void fill(int x0, int y0, int x1, int y1, int col) {
        graphics.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), col);
    }

    // ---------------------------------------------------------------- text

    public void text(Font font, String str, int x, int y, int color) {
        text(font, str, x, y, color, true);
    }

    public void text(Font font, String str, int x, int y, int color, boolean dropShadow) {
        color = clampTextAlpha(color);
        if (str == null || alpha(color) == 0) return;
        graphics.drawString(font, str, x, y, color, dropShadow);
    }

    public void text(Font font, Component str, int x, int y, int color) {
        text(font, str, x, y, color, true);
    }

    public void text(Font font, Component str, int x, int y, int color, boolean dropShadow) {
        color = clampTextAlpha(color);
        if (str == null || alpha(color) == 0) return;
        graphics.drawString(font, str, x, y, color, dropShadow);
    }

    public void text(Font font, FormattedCharSequence str, int x, int y, int color) {
        text(font, str, x, y, color, true);
    }

    public void text(Font font, FormattedCharSequence str, int x, int y, int color, boolean dropShadow) {
        color = clampTextAlpha(color);
        if (str == null || alpha(color) == 0) return;
        graphics.drawString(font, str, x, y, color, dropShadow);
    }

    public void centeredText(Font font, String str, int x, int y, int color) {
        color = clampTextAlpha(color);
        if (str == null || alpha(color) == 0) return;
        graphics.drawCenteredString(font, str, x, y, color);
    }

    public void centeredText(Font font, Component text, int x, int y, int color) {
        color = clampTextAlpha(color);
        if (text == null || alpha(color) == 0) return;
        graphics.drawCenteredString(font, text, x, y, color);
    }

    public void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
        color = clampTextAlpha(color);
        if (text == null || alpha(color) == 0) return;
        graphics.drawCenteredString(font, text, x, y, color);
    }

    /**
     * 1.21.1's {@code Font} forces any text color with an alpha below 4 to fully opaque
     * ({@code adjustColor}: {@code (color & 0xFC000000) == 0 ? color | 0xFF000000 : color}). During a
     * fade-in this makes the text flash at full brightness for one frame. 26.2 has no such rewrite,
     * which is why the flicker only showed up on the NeoForge build. Clamp to the smallest alpha the
     * renderer keeps, so the fade stays smooth.
     */
    private static int clampTextAlpha(int color) {
        int a = alpha(color);
        if (a == 0 || a >= 4) return color;
        return (color & 0x00FFFFFF) | 0x04000000;
    }

    // ---------------------------------------------------------------- blit

    /** Mirrors 26.2's {@code blit(pipeline, texture, x, y, u, v, w, h, srcW, srcH, texW, texH)}. */
    public void blit(GuiPipeline pipeline, ResourceLocation texture,
                     int x, int y, float u, float v, int width, int height,
                     int srcWidth, int srcHeight, int textureWidth, int textureHeight) {
        // 1.21.1's plain GuiGraphics.blit() goes through the *untinted* innerBlit path, which - unlike
        // the tinted variant - does NOT touch the blend state; it assumes the caller already enabled
        // it. 26.2's GUI_TEXTURED pipeline always carries TRANSLUCENT blending, so not doing it here
        // renders the arrow marker's alpha=0/partial-alpha texels with their raw RGB (black edges /
        // "semi-transparent pixels missing"). Enable straight-alpha blending explicitly for every
        // textured blit; the GUI keeps it enabled afterwards like the vanilla screens do.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(texture, x, y, width, height, u, v, srcWidth, srcHeight,
                textureWidth, textureHeight);
    }

    /** Mirrors 26.2's overload without srcWidth/srcHeight. */
    public void blit(GuiPipeline pipeline, ResourceLocation texture,
                     int x, int y, float u, float v, int width, int height,
                     int textureWidth, int textureHeight) {
        blit(pipeline, texture, x, y, u, v, width, height, width, height,
                textureWidth, textureHeight);
    }

    // ---------------------------------------------------------------- scissors

    public void enableScissor(int x0, int y0, int x1, int y1) {
        graphics.enableScissor(x0, y0, x1, y1);
    }

    public void disableScissor() {
        graphics.disableScissor();
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }
}
