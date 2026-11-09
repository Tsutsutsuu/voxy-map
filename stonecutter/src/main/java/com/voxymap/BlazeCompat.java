package com.voxymap;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;

/**
 * Version barrier for the Blaze3D render API, shared by 26.2 and 26.1.2.
 *
 * <p>The two versions differ in the texture format enum ({@code GpuFormat} vs
 * {@code TextureFormat}), index type ({@code IndexType} vs {@code VertexFormat.IndexType}),
 * topology ({@code PrimitiveTopology} vs {@code VertexFormat.Mode}), {@code RenderPass} buffer
 * argument shapes, texture upload signature, the clear-colour representation, the existence of
 * {@code CommandEncoder.submit()}, and the pipeline bind-group-layout API. Every one of those
 * differences lives here so {@link Gpu3DMap} stays platform-neutral.</p>
 *
 * <p>Version branches are Stonecutter conditional comments; this file is written in its 26.2 state,
 * so the 26.1.2 branches appear block-commented and are uncommented when the 26.1.2 sources are
 * generated.</p>
 */
public final class BlazeCompat {
    private BlazeCompat() {}

    /** RGBA8 atlas texture (usage flags are supplied by the caller). */
    public static GpuTexture createColorTexture(String label, int usage, int width, int height) {
        //? if >=26.2 {
        return RenderSystem.getDevice().createTexture(label, usage,
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        //?} else {
        /*return RenderSystem.getDevice().createTexture(label, usage,
                com.mojang.blaze3d.textures.TextureFormat.RGBA8, width, height, 1, 1);*/
        //?}
    }

    /** Depth attachment for the independent 3D pass. */
    public static GpuTexture createDepthTexture(String label, int width, int height) {
        //? if >=26.2 {
        return RenderSystem.getDevice().createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT,
                com.mojang.blaze3d.GpuFormat.D32_FLOAT, width, height, 1, 1);
        //?} else {
        /*return RenderSystem.getDevice().createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT,
                com.mojang.blaze3d.textures.TextureFormat.DEPTH32, width, height, 1, 1);*/
        //?}
    }

    /** Vertex format declared by a pipeline (26.2 exposes it per binding slot). */
    public static VertexFormat vertexFormat(RenderPipeline pipeline) {
        //? if >=26.2 {
        return pipeline.getVertexFormatBinding(0);
        //?} else {
        /*return pipeline.getVertexFormat();*/
        //?}
    }

    /** Mesh builder for the terrain tiles (mode enum differs between versions). */
    public static BufferBuilder newBufferBuilder(ByteBufferBuilder alloc, VertexFormat format) {
        //? if >=26.2 {
        return new BufferBuilder(alloc, com.mojang.blaze3d.PrimitiveTopology.TRIANGLES, format);
        //?} else {
        /*return new BufferBuilder(alloc, VertexFormat.Mode.TRIANGLES, format);*/
        //?}
    }

    /** Uploads one 256x256 atlas cell from a packed RGBA byte buffer. */
    public static void uploadAtlasCell(CommandEncoder encoder, GpuTexture texture, ByteBuffer data,
                                       int x, int y, int width, int height) {
        //? if >=26.2 {
        encoder.writeToTexture(texture, data, 0, 0, x, y, width, height);
        //?} else {
        /*encoder.writeToTexture(texture, data, com.mojang.blaze3d.platform.NativeImage.Format.RGBA,
                0, 0, x, y, width, height);*/
        //?}
    }

    /**
     * Render pass over the main target that clears it and attaches our own depth buffer.
     *
     * <p>26.2 uses reversed-Z (near=1, far=0, depth test GREATER_THAN_OR_EQUAL) and therefore
     * clears depth to 0.0; 26.1.2 still uses standard Z (near=0, far=1, test LESS_THAN_OR_EQUAL)
     * and must clear to 1.0. Clearing to the other value makes the depth test reject every
     * fragment - the terrain stayed invisible while the (depth-test-inverted) overlay ribbon
     * still showed up.</p>
     */
    public static RenderPass beginTerrainPass(CommandEncoder encoder, RenderTarget target,
                                              GpuTextureView depthView) {
        //? if >=26.2 {
        return encoder.createRenderPass(() -> "voxymap_gpu3d", target.getColorTextureView(),
                java.util.Optional.of(new org.joml.Vector4f(0.02f, 0.02f, 0.03f, 1f)),
                depthView, java.util.OptionalDouble.of(0.0));
        //?} else {
        /*return encoder.createRenderPass(() -> "voxymap_gpu3d", target.getColorTextureView(),
                java.util.OptionalInt.of(0xFF050508), depthView, java.util.OptionalDouble.of(1.0));*/
        //?}
    }

    public static void setVertexBuffer(RenderPass pass, int slot, GpuBuffer buffer) {
        //? if >=26.2 {
        pass.setVertexBuffer(slot, buffer.slice());
        //?} else {
        /*pass.setVertexBuffer(slot, buffer);*/
        //?}
    }

    /** Tile and overlay index buffers are always 32-bit indices. */
    public static void setIndexBuffer(RenderPass pass, GpuBuffer buffer) {
        //? if >=26.2 {
        pass.setIndexBuffer(buffer, com.mojang.blaze3d.IndexType.INT);
        //?} else {
        /*pass.setIndexBuffer(buffer, VertexFormat.IndexType.INT);*/
        //?}
    }

    public static void drawIndexed(RenderPass pass, int indexCount) {
        //? if >=26.2 {
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
        //?} else {
        /*// 26.1.2 argument order is (baseVertex, firstIndex, indexCount, instanceCount) -
        // very different from 26.2's (indexCount, instanceCount, firstIndex, vertexOffset,
        // firstInstance). Passing the 26.2 order here drew indexCount=0, i.e. nothing at all.
        pass.drawIndexed(0, 0, indexCount, 1);*/
        //?}
    }

    /**
     * Flushes the recorded commands. 26.1.2's encoder has no explicit submit: every command is
     * executed as it is recorded, so this is intentionally a no-op there.
     */
    public static void submit(CommandEncoder encoder) {
        //? if >=26.2 {
        encoder.submit();
        //?} else {
        /*// no explicit submit on 26.1.2*/
        //?}
    }

    /** Main framebuffer access: 26.2 moved this from Minecraft onto GameRenderer. */
    public static RenderTarget mainRenderTarget(Minecraft mc) {
        //? if >=26.2 {
        return mc.gameRenderer.mainRenderTarget();
        //?} else {
        /*return mc.getMainRenderTarget();*/
        //?}
    }

}
