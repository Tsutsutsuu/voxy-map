#version 150

// Atlas page of the tile being drawn (NEAREST + clamp-to-edge, no mipmaps).
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    // Unused atlas slots are never uploaded, so they stay transparent and are dropped here.
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color * ColorModulator;
}
