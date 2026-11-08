package com.gpuspike;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Screen-space GPU cube overlay. It deliberately runs after the complete GUI pass (a mixin on
 * Minecraft#renderFrame), so it is in front of the screen instead of behind the world/UI.
 *
 * <p>This is still a real Blaze3D path: StagedVertexBuffer + RenderPipeline + RenderPass, one
 * frame's buffer at a time. Projection is the GUI orthographic projection; only the small cube
 * vertex projection is done on the CPU.</p>
 */
public final class GpuCubeRenderer {
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "gpu_spike_cube", RenderType.SMALL_BUFFER_SIZE);
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            "gpu_spike", "pipeline/cube_overlay"))
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .build());

    private GpuCubeRenderer() {
    }

    public static void init() {
        if (PIPELINE == null) throw new IllegalStateException();
    }

    public static void renderOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.gui.screen() instanceof GpuCubeScreen)) return;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) return;
        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology topology = PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology,
                topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
        VertexConsumer b = BUFFER.getVertexBuilder(draw);

        // Small CPU-side projection only; vertices are already in GUI-screen coordinates so the
        // GPU path itself is the thing being tested.
        double cx = w / 2.0 + GpuCubeScreen.offsetX * 40.0;
        double cy = h / 2.0 - GpuCubeScreen.offsetY * 40.0;
        double scale = Math.min(w, h) * 0.18 * (7.0 / Math.max(3.0, GpuCubeScreen.distance));
        float yaw = GpuCubeScreen.yaw;
        float pitch = GpuCubeScreen.pitch;
        float cyaw = (float) Math.cos(yaw), syaw = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float[][] p = new float[8][2];
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? -1f : 1f;
            float y = (i & 2) == 0 ? -1f : 1f;
            float z = (i & 4) == 0 ? -1f : 1f;
            // yaw around Y
            float x1 = x * cyaw + z * syaw;
            float z1 = -x * syaw + z * cyaw;
            // pitch around X
            float y1 = y * cp - z1 * sp;
            float z2 = y * sp + z1 * cp;
            float zz = z2 + 5f;
            float s = (float) (scale / zz);
            p[i][0] = (float) cx + x1 * s;
            p[i][1] = (float) cy - y1 * s;
        }
        int[][] faces = {
                {4, 5, 7, 6}, {0, 1, 3, 2}, {0, 1, 5, 4},
                {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}
        };
        float[][] colors = {
                {0.95f, 0.55f, 0.25f}, {0.80f, 0.40f, 0.15f}, {0.90f, 0.50f, 0.20f},
                {0.75f, 0.35f, 0.12f}, {1.00f, 0.65f, 0.30f}, {0.70f, 0.30f, 0.10f}
        };
        for (int f = 0; f < faces.length; f++) {
            int[] q = faces[f];
            float[] c = colors[f];
            for (int k : q) {
                b.addVertex(p[k][0], p[k][1], 0f).setColor(c[0], c[1], c[2], 1f);
            }
        }

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) {
            drawToMain(mc, info);
        }
        BUFFER.endFrame();
    }

    private static void drawToMain(Minecraft mc, StagedVertexBuffer.ExecuteInfo info) {
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(new Matrix4f(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "gpu_spike_cube_overlay", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    public static void close() {
        BUFFER.close();
    }
}
