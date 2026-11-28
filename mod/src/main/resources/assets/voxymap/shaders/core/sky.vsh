#version 150

// Procedural screen-space sky (1.21.1 GPU 3D compatibility layer).
// Positions are already in NDC (the CPU builds two gradient bands from the camera pitch and the
// FOV), so no projection/model-view matrix is involved: this shader passes them straight through.
// Vertex layout: DefaultVertexFormat.POSITION_COLOR -> Position, Color.

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    vertexColor = Color;
}
