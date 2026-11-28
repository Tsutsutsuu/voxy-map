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

    /**
     * Player marker line: 26.2's {@code DEBUG_FILLED_SNIPPET} + {@code CompareOp.ALWAYS_PASS}. It is
     * a UI annotation, not terrain, so the depth test is off entirely - the "buried half is grey"
     * rule is decided from map data, never from the depth buffer. Camera-facing ribbon, hence
     * cull off.
     */
    public static final RenderPipeline MARKER = new RenderPipeline(
            "voxymap_gpu3d_marker",
            ResourceLocation.fromNamespaceAndPath("voxymap", "overlay"),
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            true,  // translucent (the white/grey tint uses alpha 0xFF, but keep the same blend path)
            false, // NO depth test: always visible over the terrain
            false, // no depth write
            false  // camera-facing ribbon: never cull
    );

    /**
     * Player arrow: a world-space quad textured with the 2D arrow PNG, perspective-correct in
     * hardware instead of the old per-pixel CPU rasteriser. Uses vanilla's {@code position_tex}
     * (Position + UV0, Sampler0 on unit 0), no depth test so the arrow stays visible.
     */
    public static final RenderPipeline ARROW = new RenderPipeline(
            "voxymap_gpu3d_arrow",
            ResourceLocation.fromNamespaceAndPath("minecraft", "position_tex"),
            DefaultVertexFormat.POSITION_TEX,
            VertexFormat.Mode.TRIANGLES,
            true,  // translucent: the PNG has transparent pixels
            false, // NO depth test
            false, // no depth write
            false  // a downward-facing quad must not be culled
    );

    /**
     * Advanced 3D terrain: identical vertex layout and GL state to {@link #TERRAIN}, but with the
     * {@code voxymap:terrain3d} shader, which adds the horizontal (distance-only) fog. The fog
     * parameters are the vanilla {@code FogStart}/{@code FogEnd}/{@code FogColor} uniforms, filled
     * by {@code ShaderInstance.setDefaultUniforms} from the {@code RenderSystem} fog state.
     */
    public static final RenderPipeline TERRAIN3D = new RenderPipeline(
            "voxymap_gpu3d_terrain3d",
            ResourceLocation.fromNamespaceAndPath("voxymap", "terrain3d"),
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.TRIANGLES,
            false, // opaque atlas, same as TERRAIN
            true,  // depth test
            true,  // depth write
            true   // cull back faces: matches 26.2's SOLID_BLOCK
    );

    /**
     * Procedural screen-space sky: NDC positions carrying the gradient colours, no depth test and no
     * depth write, translucent. It is drawn as the first thing in the 3D pass and the terrain then
     * covers it wherever geometry exists (26.2 has the same pipeline as {@code voxymap:sky}).
     */
    public static final RenderPipeline SKY = new RenderPipeline(
            "voxymap_gpu3d_sky",
            ResourceLocation.fromNamespaceAndPath("voxymap", "sky"),
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            true,  // translucent: the bands blend over the dark 3D background
            false, // no depth test: the sky is behind everything
            false, // no depth write
            false  // screen-space geometry: never cull
    );

    private RenderPipelines() {
    }
}
