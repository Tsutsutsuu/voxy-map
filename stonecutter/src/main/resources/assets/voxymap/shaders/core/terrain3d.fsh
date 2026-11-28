#version 330

// Experimental advanced 3D terrain shader (Fabric 26.2).
// The atlas is the styled 2D colour with hillshade disabled: base colour + coastline shading +
// water depth + contour lines. Lighting/AO will be resolved here in the next task.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color;
}
