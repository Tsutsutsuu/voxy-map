package com.voxymap;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;

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

    /** Mesh builder for the fullscreen composite quad (QUADS topology differs between versions). */
    public static BufferBuilder newQuadBufferBuilder(ByteBufferBuilder alloc, VertexFormat format) {
        //? if >=26.2 {
        return new BufferBuilder(alloc, com.mojang.blaze3d.PrimitiveTopology.QUADS, format);
        //?} else {
        /*return new BufferBuilder(alloc, VertexFormat.Mode.QUADS, format);*/
        //?}
    }

    /** Non-indexed draw (argument shape differs between versions). */
    public static void drawNonIndexed(RenderPass pass, int vertexCount) {
        //? if >=26.2 {
        pass.draw(vertexCount, 1, 0, 0);
        //?} else {
        /*pass.draw(vertexCount, 1);*/
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
        drawIndexed(pass, 0, indexCount);
    }

    /** Indexed draw starting at {@code firstIndex} into the bound index buffer. */
    public static void drawIndexed(RenderPass pass, int firstIndex, int indexCount) {
        //? if >=26.2 {
        pass.drawIndexed(indexCount, 1, firstIndex, 0, 0);
        //?} else {
        /*// 26.1.2 argument order is (baseVertex, firstIndex, indexCount, instanceCount) -
        // very different from 26.2's (indexCount, instanceCount, firstIndex, vertexOffset,
        // firstInstance). Passing the 26.2 order here drew indexCount=0, i.e. nothing at all.
        pass.drawIndexed(0, firstIndex, indexCount, 1);*/
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

    /**
     * Fence for "the GPU has finished reading this buffer". 26.2's encoder creates a real
     * {@code GpuFence}; 26.1.2 has no fence type at all (its encoder executes immediately), so the
     * helpers degrade to no-ops there and the caller's 3-slot ring is the only protection.
     */
    public static Object createFence(CommandEncoder encoder) {
        //? if >=26.2 {
        return encoder.createFence();
        //?} else {
        /*return null;*/
        //?}
    }

    /** Waits for a fence created by {@link #createFence} and closes it; no-op for null. */
    public static void awaitAndCloseFence(Object fence) {
        //? if >=26.2 {
        if (fence instanceof com.mojang.blaze3d.buffers.GpuFence f) {
            try {
                f.awaitCompletion(Long.MAX_VALUE);
            } finally {
                f.close();
            }
        }
        //?} else {
        /*// 26.1.2: no fence objects exist*/
        //?}
    }

    /**
     * Opens a screen immediately (no forced frame). 26.2 moved the screen accessor onto
     * {@code Gui}; 26.1.2 still has it on {@code Minecraft}.
     */
    public static void openScreen(Screen screen) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen);
        //?} else {
        /*Minecraft.getInstance().setScreen(screen);*/
        //?}
    }

    /** True when the vanilla HUD is hidden — the same state F1 toggles. */
    public static boolean isHudHidden() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.hud.isHidden();
        //?} else {
        /*return Minecraft.getInstance().options.hideGui;*/
        //?}
    }

    /** Toggles the F1 HUD state. */
    public static void toggleHud() {
        //? if >=26.2 {
        Minecraft.getInstance().gui.hud.toggle();
        //?} else {
        /*Minecraft.getInstance().options.hideGui = !Minecraft.getInstance().options.hideGui;*/
        //?}
    }

    /**
     * Textured, depth-test-free world pipeline for the player arrow: GLOBALS + projection +
     * Sampler0, vanilla {@code core/position_tex}, POSITION_TEX vertices, translucent blending.
     *
     * <p>The two versions describe a pipeline differently: 26.2 declares bind-group layouts and a
     * per-slot vertex binding plus a primitive topology, while 26.1.2 has no bind-group or
     * PrimitiveTopology classes and declares the uniforms, a sampler and one vertex format instead.
     * Fully qualified names keep this file free of version-specific imports.</p>
     */
    public static RenderPipeline arrowPipeline() {
        //? if >=26.2 {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.MATRICES_PROJECTION)
                .withLocation("pipeline/voxymap_marker_arrow")
                .withVertexShader("core/position_tex")
                .withFragmentShader("core/position_tex")
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withCull(false)
                .build());
        //?} else {
        /*return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                .withLocation("pipeline/voxymap_marker_arrow")
                .withVertexShader("core/position_tex")
                .withFragmentShader("core/position_tex")
                .withSampler("Sampler0")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX,
                        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withCull(false)
                .build());*/
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

    /**
     * True when this version can run the experimental advanced 3D rendering path (offscreen layer,
     * custom terrain shader). 26.2 only for now; 26.1.2/NeoForge are wired up in a later task.
     */
    public static boolean advanced3dSupported() {
        //? if >=26.2 {
        return true;
        //?} else {
        /*return false;*/
        //?}
    }

    /**
     * Custom terrain pipeline for advanced 3D rendering: same BLOCK vertex format as the vanilla
     * chunk pipeline (position/color/uv0/uv2), but the mod's own shader so lighting/AO/contours can
     * run there. Registered once; the shader lives in {@code assets/voxymap/shaders/core/terrain3d}.
     */
    public static RenderPipeline advancedTerrainPipeline() {
        //? if >=26.2 {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
                .withLocation("pipeline/voxymap_terrain3d")
                .withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("voxymap", "core/terrain3d"))
                .withFragmentShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("voxymap", "core/terrain3d"))
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .build());
        //?} else {
        /*return RenderPipelines.SOLID_BLOCK;*/
        //?}
    }

    /**
     * Fullscreen composite pipeline for the advanced 3D layer: textured, blended, no depth. Uses
     * TRIANGLES with an explicit index buffer (the vanilla GUI pipeline's non-indexed QUADS draw
     * only produced half of the screen).
     */
    public static RenderPipeline advancedCompositePipeline() {
        //? if >=26.2 {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
                .withLocation("pipeline/voxymap_adv3d_composite")
                .withVertexShader("core/position_tex_color")
                .withFragmentShader("core/position_tex_color")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withCull(false)
                .build());
        //?} else {
        /*return RenderPipelines.GUI_TEXTURED;*/
        //?}
    }

    /** Colour target for the offscreen advanced 3D layer (sampled by the composite pass). */
    public static GpuTexture createOffscreenColorTexture(String label, int width, int height) {
        //? if >=26.2 {
        return RenderSystem.getDevice().createTexture(label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        //?} else {
        /*return RenderSystem.getDevice().createTexture(label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                com.mojang.blaze3d.textures.TextureFormat.RGBA8, width, height, 1, 1);*/
        //?}
    }

    /** Render pass that draws the advanced 3D terrain into its offscreen layer (opaque dark backdrop). */
    public static RenderPass beginOffscreenPass(CommandEncoder encoder, GpuTextureView colorView,
                                                GpuTextureView depthView) {
        //? if >=26.2 {
        return encoder.createRenderPass(() -> "voxymap_adv3d_layer", colorView,
                // Opaque dark backdrop: the composite discards alpha==0 fragments, so a transparent
                // clear would let the flashing main-target content show through the background.
                java.util.Optional.of(new org.joml.Vector4f(0.02f, 0.02f, 0.03f, 1.0f)),
                depthView, java.util.OptionalDouble.of(0.0));
        //?} else {
        /*return encoder.createRenderPass(() -> "voxymap_adv3d_layer", colorView,
                java.util.OptionalInt.of(0), depthView, java.util.OptionalDouble.of(1.0));*/
        //?}
    }

    /** Render pass over the main target for the fullscreen composite of the advanced 3D layer. */
    public static RenderPass beginCompositePass(CommandEncoder encoder, GpuTextureView mainColorView) {
        //? if >=26.2 {
        return encoder.createRenderPass(() -> "voxymap_adv3d_composite", mainColorView,
                java.util.Optional.empty(), null, java.util.OptionalDouble.empty());
        //?} else {
        /*return encoder.createRenderPass(() -> "voxymap_adv3d_composite", mainColorView,
                java.util.OptionalInt.empty(), null, java.util.OptionalDouble.empty());*/
        //?}
    }

    /**
     * Current screen: 26.2 moved the accessor onto {@code Gui}, 26.1.2 still exposes it directly on
     * {@code Minecraft}. Test-only code (tools/selftest) is compiled per version but is not run
     * through Stonecutter preprocessing, so it must use this helper instead of either form.
     */
    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        return mc.gui.screen();
        //?} else {
        /*return mc.screen;*/
        //?}
    }

}
