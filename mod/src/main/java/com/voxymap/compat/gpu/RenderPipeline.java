package com.voxymap.compat.gpu;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.1 stand-in for 26.2's {@code RenderPipeline}: the shader + vertex format + GL state a
 * {@link RenderPass} draws with.
 *
 * <p>26.2 pipelines are declared with builders and validated by the GPU device; on 1.21.1 the
 * equivalent information is "which {@code ShaderInstance} json to compile" plus the handful of
 * fixed-function states. Only the combinations the 3D map needs exist (see
 * {@link RenderPipelines}).</p>
 */
public final class RenderPipeline {
    private final String name;
    private final ResourceLocation shaderLocation;
    private final VertexFormat vertexFormat;
    private final VertexFormat.Mode mode;
    private final boolean blend;
    private final boolean depthTest;
    private final boolean depthWrite;
    private final boolean cull;

    public RenderPipeline(String name, ResourceLocation shaderLocation, VertexFormat vertexFormat,
                          VertexFormat.Mode mode, boolean blend, boolean depthTest,
                          boolean depthWrite, boolean cull) {
        this.name = name;
        this.shaderLocation = shaderLocation;
        this.vertexFormat = vertexFormat;
        this.mode = mode;
        this.blend = blend;
        this.depthTest = depthTest;
        this.depthWrite = depthWrite;
        this.cull = cull;
    }

    /** Resource id of the {@code assets/<ns>/shaders/core/<path>.json} triple. */
    public ResourceLocation shaderLocation() {
        return shaderLocation;
    }

    public VertexFormat vertexFormat() {
        return vertexFormat;
    }

    public VertexFormat.Mode mode() {
        return mode;
    }

    public boolean blend() {
        return blend;
    }

    public boolean depthTest() {
        return depthTest;
    }

    public boolean depthWrite() {
        return depthWrite;
    }

    public boolean cull() {
        return cull;
    }

    @Override
    public String toString() {
        return "RenderPipeline[" + name + "]";
    }
}
