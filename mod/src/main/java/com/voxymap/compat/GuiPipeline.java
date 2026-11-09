package com.voxymap.compat;

/**
 * The GUI batch "pipeline" constants the 26.2 sources pass to
 * {@link GuiGraphicsExtractor#blit}. On 1.21.1 the GUI is drawn immediately through
 * {@code GuiGraphics}, which picks its own render type, so the constant carries no
 * behaviour - it only keeps the ported call sites source-identical.
 */
public enum GuiPipeline {
    /** 26.2 {@code RenderPipelines.GUI_TEXTURED}: straight-alpha textured GUI quad. */
    TEXTURED,
    /** 26.2 {@code RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA}: map tiles, which are opaque. */
    TEXTURED_PREMULTIPLIED_ALPHA
}
