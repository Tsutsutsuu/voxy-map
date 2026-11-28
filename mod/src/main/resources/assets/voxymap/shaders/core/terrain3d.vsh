#version 150

// Advanced 3D terrain shader (1.21.1 GPU 3D compatibility layer).
// Same colour math as voxymap:terrain, but the fog distance is horizontal (XZ in view space) so
// height and pitch never add fog. This mirrors 26.2's voxymap:core/terrain3d.
// Vertex layout: DefaultVertexFormat.POSITION_TEX_COLOR -> Position, UV0, Color.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 vertexColor;
out float horizontalFogDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    texCoord0 = UV0;
    vertexColor = Color;
    // Camera-relative horizontal distance only: no vertical fog.
    horizontalFogDistance = length(viewPos.xz);
}
