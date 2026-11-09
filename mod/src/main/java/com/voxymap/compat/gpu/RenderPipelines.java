package com.voxymap.compat.gpu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

/**
 * The two pipelines the GPU 3D map needs, replacing 26.2's {@code RenderPipelines.SOLID_BLOCK} and
 * {@code RenderPipelines.DEBUG_QUADS}.
 *
 * <ul>
 *   <li>{@link #TERRAIN}: opaque, depth-tested, depth-writing, <b>culling disabled on purpose</b>.
 *       The winding is front-facing-CCW (verified offscreen on the target driver through the real
 *       BufferBuilder/ShaderInstance/VAO path: with {@code glCullFace} = BACK the tile is fully
 *       drawn, with FRONT it disappears), so culling could be enabled. It is kept off because:
 *       (a) a height field viewed from above has every triangle front-facing, so back-face
 *       culling would remove almost nothing - the fill-rate saving is negligible; (b) the 3D
 *       orbit camera may look along a ridge and see a mountain's far side, and culling there
 *       turns it into a see-through hole (26.2, with SOLID_BLOCK's cull=true, shows that hole).
 *       For strict 26.2 parity flip this one flag to {@code true}.</li>
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
            false  // cull: deliberately double-sided, see the class comment (flip for 26.2 parity)
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
