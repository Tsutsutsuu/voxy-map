#version 330

// Small terrain shader for the advanced 3D view (26.2). The 3D atlas is the shared 2D coloured
// texture and the vertex colour carries the per-vertex AO, so the colour math matches the vanilla
// block shader. Fog is applied from a horizontal distance only (see the vertex shader).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;
in float horizontalFogDistance;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }

    float fogSpan = max(1.0, FogEnvironmentalEnd - FogEnvironmentalStart);
    float fogValue = clamp((horizontalFogDistance - FogEnvironmentalStart) / fogSpan, 0.0, 1.0);
    color.rgb = mix(color.rgb, FogColor.rgb, fogValue * FogColor.a);
    fragColor = color;
}
