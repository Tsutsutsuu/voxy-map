#version 150

// 1.21.1 GPU 3D terrain shader (Blaze3D compatibility layer).
// Vertex layout: DefaultVertexFormat.POSITION_TEX_COLOR -> Position, UV0, Color.
// The 26.2 SOLID_BLOCK pipeline also had UV2 + Normal; 1.21.1 does not need them because the
// terrain is deliberately full-bright (the atlas colours are the unlit 2D map colours).

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
}
