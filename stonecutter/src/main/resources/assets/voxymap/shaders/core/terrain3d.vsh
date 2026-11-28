#version 330

// Experimental advanced 3D terrain shader (Fabric 26.2).
// The atlas holds base colours only (no hillshade/contours baked in); contours come from the
// fragment stage below using the interpolated world Y. UV2 carries the contour parameters
// (x = interval * verticalScale * 16, y = enabled | widthQuarters << 1) from the mesher, so no
// custom uniform block is needed for the experimental first pass.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec2 texCoord0;
out vec4 vertexColor;
out float worldY;
flat out ivec2 contourParams;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    worldY = Position.y;
    contourParams = UV2;
}
