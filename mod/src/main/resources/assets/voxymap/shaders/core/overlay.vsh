#version 150

// 1.21.1 render-distance ribbon shader (Blaze3D compatibility layer).
// Vertex layout: DefaultVertexFormat.POSITION_COLOR -> Position, Color.
// Equivalent to the 26.2 DEBUG_QUADS pipeline the overlay used.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
}
