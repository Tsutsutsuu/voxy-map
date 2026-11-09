package com.voxymap.compat;

/** 1.21.1 stand-in for the 26.2 {@code RenderPipelines} constants used by the map GUI. */
public final class GuiPipelines {
    public static final GuiPipeline GUI = GuiPipeline.TEXTURED;
    public static final GuiPipeline GUI_TEXTURED = GuiPipeline.TEXTURED;
    public static final GuiPipeline GUI_TEXTURED_PREMULTIPLIED_ALPHA =
            GuiPipeline.TEXTURED_PREMULTIPLIED_ALPHA;

    private GuiPipelines() {
    }
}
