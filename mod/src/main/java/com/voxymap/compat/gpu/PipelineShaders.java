package com.voxymap.compat.gpu;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Lazily compiles (and caches) one {@link ShaderInstance} per {@link RenderPipeline}.
 *
 * <p>1.21.1 loads core shaders by resource id: a {@code ShaderInstance} reads
 * {@code assets/<ns>/shaders/core/<path>.json}, and the json's {@code vertex}/{@code fragment}
 * entries name the {@code .vsh}/{@code .fsh} of the same namespace. We compile straight from the
 * client resource manager because a resource-reload hook would need registrations outside this
 * port's file scope; the compiled GL program stays valid across F3+T, only a shader edit inside the
 * same session needs a restart.</p>
 *
 * <p>A missing/broken resource makes the terrain pass render nothing (the GUI stays usable). The
 * failure is retried at most every couple of seconds - the first 3D frame can land while the
 * resource manager is still being (re)built, and permanently giving up there would disable the
 * whole session's 3D view.</p>
 */
final class PipelineShaders {
    private static final long RETRY_DELAY_NANOS = 2_000_000_000L;
    private static final Map<RenderPipeline, ShaderInstance> CACHE = new IdentityHashMap<>();
    private static long lastFailureNanos;
    private static boolean loggedFailure;

    private PipelineShaders() {
    }

    /** Returns the compiled shader, or {@code null} when it is not available (yet). */
    static ShaderInstance get(RenderPipeline pipeline) {
        ShaderInstance cached = CACHE.get(pipeline);
        if (cached != null) return cached;
        long now = System.nanoTime();
        if (now - lastFailureNanos < RETRY_DELAY_NANOS) return null;
        try {
            ResourceProvider provider = Minecraft.getInstance().getResourceManager();
            ShaderInstance shader = new ShaderInstance(provider, pipeline.shaderLocation(),
                    pipeline.vertexFormat());
            CACHE.put(pipeline, shader);
            loggedFailure = false;
            return shader;
        } catch (Throwable t) {
            lastFailureNanos = now;
            if (!loggedFailure) {
                loggedFailure = true;
                System.out.println("[voxymap] gpu3d shader " + pipeline.shaderLocation()
                        + " failed to load, 3D terrain hidden until it succeeds: " + t);
            }
            return null;
        }
    }

    /** Runs {@code setDefaultUniforms} + {@code apply} for the shader a pass is about to draw with. */
    static void apply(RenderPipeline pipeline, ShaderInstance shader, Window window,
                      Matrix4f modelView, Matrix4f projection) {
        shader.setDefaultUniforms(pipeline.mode(), modelView, projection, window);
        shader.apply();
    }

    static void assertOnRenderThread() {
        RenderSystem.assertOnRenderThread();
    }
}
