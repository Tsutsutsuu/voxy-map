#version 330

// Procedural screen-space sky: positions are already in NDC (the vertex shader passes them
// straight through), colours are interpolated across the gradient bands.

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    vertexColor = Color;
}
