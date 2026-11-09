package com.voxymap.compat.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * 1.21.1 stand-in for 26.2's {@code RenderPass}.
 *
 * <p>26.2 records pipeline switches, uniform writes, buffer binds and draws into an encoder and
 * submits them at the end of the frame. On 1.21.1 the same calls are executed immediately against
 * the currently bound framebuffer (the main target), using a {@link ShaderInstance} compiled from
 * the pipeline's own json and per-buffer vertex array objects.</p>
 *
 * <p>The pass deliberately does <b>not</b> touch {@code RenderSystem}'s projection / model-view
 * stack: the terrain matrices are handed straight to the shader through
 * {@code setDefaultUniforms}, so the GUI's orthographic state is untouched and needs no restoring.
 * Fixed-function state (depth, blend, cull, VAO, shader program) <i>is</i> changed and must be put
 * back by the caller after {@link #close()} - see {@code Gpu3DMap.render()}.</p>
 */
public final class RenderPass implements AutoCloseable {
    private final Matrix4f modelView;
    private final Matrix4f projection;
    private final Window window;
    private RenderPipeline pipeline;
    private ShaderInstance shader;
    private GpuBuffer vertexBuffer;
    private GpuBuffer indexBuffer;
    private int textureId = -1;
    private boolean closed;

    public RenderPass(RenderPipeline pipeline, Matrix4f modelView, Matrix4f projection) {
        this(pipeline, modelView, projection, Minecraft.getInstance().getWindow());
    }

    public RenderPass(RenderPipeline pipeline, Matrix4f modelView, Matrix4f projection, Window window) {
        RenderSystem.assertOnRenderThread();
        this.modelView = modelView;
        this.projection = projection;
        this.window = window;
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        setPipeline(pipeline);
    }

    /** Switches pipeline: re-applies the fixed-function state and the pipeline's shader program. */
    public void setPipeline(RenderPipeline next) {
        if (next == null) return;
        this.pipeline = next;
        applyFixedState(next);
        ShaderInstance compiled = PipelineShaders.get(next);
        this.shader = compiled;
        if (compiled != null) {
            PipelineShaders.apply(next, compiled, window, modelView, projection);
        }
    }

    /**
     * Binds the texture a sampler reads. 1.21.1 has no sampler objects, so {@code sampler} only
     * documents the intended filtering; the atlas pages are already NEAREST + clamp.
     */
    public void bindTexture(String samplerName, GpuTextureView view, GpuSampler sampler) {
        this.textureId = view == null || view.texture() == null ? -1 : view.texture().id();
    }

    public void setVertexBuffer(int index, GpuBuffer buffer) {
        if (index == 0) {
            this.vertexBuffer = buffer;
        }
    }

    public void setIndexBuffer(GpuBuffer buffer) {
        this.indexBuffer = buffer;
    }

    /** Draws {@code indexCount} int indices from the bound vertex/index buffers. */
    public void drawIndexed(int indexCount) {
        if (shader == null || vertexBuffer == null || indexBuffer == null || indexCount <= 0) return;
        if (textureId >= 0) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(textureId);
        }
        vertexBuffer.bindForDraw(indexBuffer);
        RenderSystem.drawElements(pipeline.mode().asGLMode, indexCount, GL11.GL_UNSIGNED_INT);
    }

    private void applyFixedState(RenderPipeline p) {
        if (p.depthTest()) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        // 1.21.1 uses standard OpenGL depth (clear 1.0, LEQUAL).
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(p.depthWrite());
        if (p.blend()) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        } else {
            RenderSystem.disableBlend();
        }
        if (p.cull()) {
            // GL's cull-face default is BACK and vanilla 1.21.1 has no setter for it, but pin it
            // explicitly so a stray raw-GL call from another mod cannot flip our terrain inside out.
            GL11.glCullFace(GL11.GL_BACK);
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (shader != null) {
            // Unbinds the program and the units its samplers use (vanilla's own end-of-batch shape).
            shader.clear();
            shader = null;
        }
        GlStateManager._glBindVertexArray(0);
        // Our per-tile VAOs displace the VAO that vanilla's BufferUploader cached for its immediate
        // draws (GUI text/fills). Without this invalidation the next GUI batch skips re-binding and
        // draws with the wrong vertex attributes - the symptom is smeared/garbled text. VertexBuffer
        // .bind() performs the same invalidation for exactly this reason.
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
    }
}
