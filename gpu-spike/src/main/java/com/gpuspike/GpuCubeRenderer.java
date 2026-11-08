package com.gpuspike;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Minimal, isolated GPU cube path. Every frame follows the 26.2 documented sequence:
 * appendDraw -> write vertices -> upload -> getExecuteInfo -> one RenderPass -> endFrame.
 * No buffer is reused across frames, so a stale mapped buffer cannot turn into colour blocks.
 */
public final class GpuCubeRenderer {
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "gpu_spike_cube", RenderType.SMALL_BUFFER_SIZE);

    private GpuCubeRenderer() {
    }

    public static void render(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.gui.screen() instanceof GpuCubeScreen)) return;

        RenderPipeline pipeline = RenderPipelines.DEBUG_FILLED_BOX;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology topology = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology);
        PoseStack matrices = context.poseStack();
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 cam = camera.position();

        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        // Put the cube a few blocks in front of the player; screen WASD offsets it.
        Vec3 p = mc.player.position();
        float yaw = GpuCubeScreen.yaw;
        float pitch = GpuCubeScreen.pitch;
        double dist = GpuCubeScreen.distance;
        float dirX = (float) (-Math.sin(yaw) * Math.cos(pitch) * dist);
        float dirY = (float) (Math.sin(pitch) * dist);
        float dirZ = (float) (Math.cos(yaw) * Math.cos(pitch) * dist);
        matrices.translate(p.x + dirX + GpuCubeScreen.offsetX,
                p.y + 2.0 + dirY + GpuCubeScreen.offsetY,
                p.z + dirZ + GpuCubeScreen.offsetZ);
        matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Math.toDegrees(yaw)));
        matrices.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Math.toDegrees(pitch)));

        VertexConsumer builder = BUFFER.getVertexBuilder(draw);
        Matrix4fc pose = matrices.last().pose();
        box(pose, builder, -1f, -1f, -1f, 1f, 1f, 1f,
                0.95f, 0.35f, 0.15f, 1f);
        matrices.popPose();

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) {
            drawToMain(mc, info, pipeline);
        }
        BUFFER.endFrame();
    }

    private static void drawToMain(Minecraft mc, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "gpu_spike_cube", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private static void box(Matrix4fc m, VertexConsumer b, float x0, float y0, float z0,
                            float x1, float y1, float z1, float r, float g, float bl, float a) {
        quad(m, b, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, bl, a);
        quad(m, b, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, bl, a);
        quad(m, b, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, bl, a);
        quad(m, b, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, bl, a);
        quad(m, b, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, bl, a);
        quad(m, b, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl, a);
    }

    private static void quad(Matrix4fc m, VertexConsumer b,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float bl, float a) {
        b.addVertex(m, ax, ay, az).setColor(r, g, bl, a);
        b.addVertex(m, bx, by, bz).setColor(r, g, bl, a);
        b.addVertex(m, cx, cy, cz).setColor(r, g, bl, a);
        b.addVertex(m, dx, dy, dz).setColor(r, g, bl, a);
    }

    public static void close() {
        BUFFER.close();
    }
}
