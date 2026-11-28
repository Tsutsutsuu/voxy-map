#version 150

// Procedural screen-space sky (1.21.1 GPU 3D compatibility layer).
// The vertex colour already carries the gradient band colour, so the fragment stage only applies
// the shader colour modulator (always 1,1,1,1 for the 3D pass).

uniform vec4 ColorModulator;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    fragColor = vertexColor * ColorModulator;
}
