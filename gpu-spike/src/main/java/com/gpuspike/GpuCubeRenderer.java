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
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;

/**
 * Screen-space GPU cube overlay. The world is skipped while the test screen is open; this pass
 * runs after the GUI and before the frame submit, so the cube is in front of the UI.
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
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .build());
    private static ProjectionMatrixBuffer projection;
    private static long lastLog;

    private GpuCubeRenderer() {
    }

    public static void init() {
        if (PIPELINE == null) throw new IllegalStateException();
    }

    public static void renderOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof GpuCubeScreen)) return;
        if (mc.player == null || mc.level == null) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) return;
        if (projection == null) projection = new ProjectionMatrixBuffer("gpu_spike_projection");
        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        if (format == null) return;
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, PIPELINE.getPrimitiveTopology(), null);
        VertexConsumer b = BUFFER.getVertexBuilder(draw);
        screenCube(b, w, h);
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) {
            Matrix4f ortho = new Matrix4f().setOrtho(0f, w, h, 0f, -1f, 1f);
            RenderSystem.backupProjectionMatrix();
            try {
                RenderSystem.setProjectionMatrix(projection.getBuffer(ortho),
                        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
                drawToMain(mc, info);
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
        }
        BUFFER.endFrame();
        long now = System.currentTimeMillis();
        if (now - lastLog > 1000L) {
            lastLog = now;
            System.out.println("[gpu-spike] overlay info=" + (info != null)
                    + " indices=" + (info != null ? info.indexCount() : -1));
        }
    }

    private static void drawToMain(Minecraft mc, StagedVertexBuffer.ExecuteInfo info) {
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target.getColorTextureView() == null) return;
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(new Matrix4f(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
                () -> "gpu_spike_overlay", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
        encoder.submit();
    }

    private static void screenCube(VertexConsumer b, int w, int h) {
        double cx = w / 2.0 + GpuCubeScreen.offsetX * 18.0;
        double cy = h / 2.0 - GpuCubeScreen.offsetY * 18.0;
        double dist = Math.max(2.0, GpuCubeScreen.distance - GpuCubeScreen.offsetZ);
        double scale = Math.min(w, h) * 0.22 * (8.0 / dist);
        float yaw = GpuCubeScreen.yaw;
        float pitch = GpuCubeScreen.pitch;
        float cyaw = (float) Math.cos(yaw), syaw = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float[][] p = new float[8][2];
        float[] depth = new float[8];
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? -1f : 1f;
            float y = (i & 2) == 0 ? -1f : 1f;
            float z = (i & 4) == 0 ? -1f : 1f;
            float x1 = x * cyaw + z * syaw;
            float z1 = -x * syaw + z * cyaw;
            float y1 = y * cp - z1 * sp;
            float z2 = y * sp + z1 * cp;
            float zz = z2 + 5f;
            float s = (float) (scale / zz);
            p[i][0] = (float) cx + x1 * s;
            p[i][1] = (float) cy - y1 * s;
            depth[i] = zz;
        }
        int[][] faces = {
                {4, 5, 7, 6}, {0, 1, 3, 2}, {0, 1, 5, 4},
                {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}
        };
        float[][] colors = {
                {0.95f, 0.55f, 0.25f}, {0.80f, 0.40f, 0.15f}, {0.90f, 0.50f, 0.20f},
                {0.75f, 0.35f, 0.12f}, {1.00f, 0.65f, 0.30f}, {0.70f, 0.30f, 0.10f}
        };
        // No depth buffer in this screen-space spike: paint far faces first, near faces last.
        Integer[] order = {0, 1, 2, 3, 4, 5};
        java.util.Arrays.sort(order, (a, bb) -> {
            float da = (depth[faces[a][0]] + depth[faces[a][1]]
                    + depth[faces[a][2]] + depth[faces[a][3]]) * 0.25f;
            float db = (depth[faces[bb][0]] + depth[faces[bb][1]]
                    + depth[faces[bb][2]] + depth[faces[bb][3]]) * 0.25f;
            return Float.compare(db, da);
        });
        for (int fi : order) {
            int[] q = faces[fi];
            float[] c = colors[fi];
            tri(b, p[q[0]][0], p[q[0]][1], p[q[1]][0], p[q[1]][1], p[q[2]][0], p[q[2]][1],
                    c[0], c[1], c[2], 1f);
            tri(b, p[q[0]][0], p[q[0]][1], p[q[2]][0], p[q[2]][1], p[q[3]][0], p[q[3]][1],
                    c[0], c[1], c[2], 1f);
        }
    }

    private static void tri(VertexConsumer b, float ax, float ay, float bx, float by,
                            float cx, float cy, float r, float g, float bl, float a) {
        b.addVertex(ax, ay, 0f).setColor(r, g, bl, a);
        b.addVertex(bx, by, 0f).setColor(r, g, bl, a);
        b.addVertex(cx, cy, 0f).setColor(r, g, bl, a);
    }

    public static void close() {
        BUFFER.close();
        if (projection != null) projection.close();
        projection = null;
    }
}
