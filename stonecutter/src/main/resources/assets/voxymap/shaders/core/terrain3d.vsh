#version 330

// Experimental advanced 3D terrain shader (Fabric 26.2).
// The atlas already contains base colour, coastline shading, water depth and contour lines (the
// 3D view disables hillshade while colouring); the shader only applies per-vertex tint for now and
// is the place where lighting / ambient occlusion will be added in the next task.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
