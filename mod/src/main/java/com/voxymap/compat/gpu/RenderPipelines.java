package com.voxymap.compat.gpu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

/**
 * The two pipelines the GPU 3D map needs, replacing 26.2's {@code RenderPipelines.SOLID_BLOCK} and
 * {@code RenderPipelines.DEBUG_QUADS}.
 *
 * <ul>
 *   <li>{@link #TERRAIN}: opaque, depth-tested, depth-writing, back-face culled (same as 26.2's
 *       {@code SOLID_BLOCK}, which defaults to {@code cull=true}). The tile-grid winding is
 *       front-facing-CCW; it was verified offscreen on the target driver through the real
 *       BufferBuilder/ShaderInstance/VAO path (with {@code glCullFace} = BACK the tile is fully
 *       drawn, with FRONT it disappears) and confirmed in game. The tile skirts keep both windings,
 *       so seams stay closed regardless of culling.</li>
 *   <li>{@link #OVERLAY}: translucent yellow render-distance ribbon, depth-tested but not
 *       depth-writing (same as 26.2's debug-quads pipeline).</li>
 * </ul>
 */
public final class RenderPipelines {
    public static final RenderPipeline TERRAIN = new RenderPipeline(
            "voxymap_gpu3d_terrain",
            ResourceLocation.fromNamespaceAndPath("voxymap", "terrain"),
            // 1.21.1 has no POSITION_COLOR_TEX; POSITION_TEX_COLOR is the same 24-byte layout with a
            // different element order (Position, UV0, Color), which the shader follows.
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.TRIANGLES,
            false, // no blending: the atlas is fully opaque
            true,  // depth test
            true,  // depth write
            true   // cull back faces: matches 26.2's SOLID_BLOCK (winding verified)
    );

    public static final RenderPipeline OVERLAY = new RenderPipeline(
            "voxymap_gpu3d_overlay",
            ResourceLocation.fromNamespaceAndPath("voxymap", "overlay"),
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            true,  // translucent
            true,  // depth test
            false, // no depth write
            false  // no culling
    );

    private RenderPipelines() {
    }
}
