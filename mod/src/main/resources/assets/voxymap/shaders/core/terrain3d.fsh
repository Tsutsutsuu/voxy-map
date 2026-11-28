#version 150

// Advanced 3D terrain shader (1.21.1 GPU 3D compatibility layer).
// The atlas is the shared 2D coloured texture and the vertex colour carries the per-vertex AO /
// side-wall shading, so the colour math matches voxymap:terrain. Fog is applied from a horizontal
// distance only (see the vertex shader); 26.2 gets the same values from its fog UBO, 1.21.1 passes
// them as plain uniforms filled from RenderSystem.setShaderFogStart/End/Color.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec2 texCoord0;
in vec4 vertexColor;
in float horizontalFogDistance;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
    color *= ColorModulator;

    float fogSpan = max(1.0, FogEnd - FogStart);
    float fogValue = clamp((horizontalFogDistance - FogStart) / fogSpan, 0.0, 1.0);
    color.rgb = mix(color.rgb, FogColor.rgb, fogValue * FogColor.a);
    fragColor = color;
}
