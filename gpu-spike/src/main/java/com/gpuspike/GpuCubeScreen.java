package com.gpuspike;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Fullscreen UI for the standalone GPU cube. The cube itself is drawn by a GPU render pass. */
public final class GpuCubeScreen extends Screen {
    static volatile float yaw = 0.55f;
    static volatile float pitch = 0.35f;
    static volatile double distance = 7.0;
    static volatile double offsetX;
    static volatile double offsetY;
    static volatile double offsetZ;

    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    public GpuCubeScreen() {
        super(Component.literal("GPU Spike"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Deliberately do not call super: no vanilla panorama/background may cover the GPU cube.
        int x = 12;
        int y = 12;
        g.text(font, Component.literal("GPU SPIKE - Minecraft 26.2 custom RenderPipeline"), x, y, 0xFFFFFFFF);
        y += 14;
        g.text(font, Component.literal("G / Esc: close    Drag: rotate    Scroll: distance"), x, y, 0xFFB0B0B0);
        y += 14;
        g.text(font, Component.literal("WASD: move cube    Q/E: vertical"), x, y, 0xFFB0B0B0);
        y += 14;
        g.text(font, Component.literal("cube is drawn by StagedVertexBuffer + RenderPass, not CPU raster"), x, y, 0xFF80FF80);
        y += 14;
        g.text(font, Component.literal(String.format(java.util.Locale.ROOT,
                "yaw=%.2f pitch=%.2f dist=%.1f pos=(%.1f, %.1f, %.1f)",
                yaw, pitch, distance, offsetX, offsetY, offsetZ)), x, y, 0xFF80C0FF);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (key == GLFW.GLFW_KEY_G || key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        double step = 0.5;
        if (key == GLFW.GLFW_KEY_W) offsetZ -= step;
        else if (key == GLFW.GLFW_KEY_S) offsetZ += step;
        else if (key == GLFW.GLFW_KEY_A) offsetX -= step;
        else if (key == GLFW.GLFW_KEY_D) offsetX += step;
        else if (key == GLFW.GLFW_KEY_Q) offsetY -= step;
        else if (key == GLFW.GLFW_KEY_E) offsetY += step;
        else return super.keyPressed(e);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0) {
            dragging = true;
            lastMouseX = e.x();
            lastMouseY = e.y();
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!dragging || e.button() != 0) return super.mouseDragged(e, dx, dy);
        yaw += (float) (dx * 0.01);
        pitch = (float) Math.max(-1.5, Math.min(1.5, pitch + dy * 0.01));
        lastMouseX = e.x();
        lastMouseY = e.y();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (e.button() == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        distance = Math.max(3.0, Math.min(24.0, distance * (scrollY > 0 ? 0.9 : 1.1)));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(null);
    }
}
