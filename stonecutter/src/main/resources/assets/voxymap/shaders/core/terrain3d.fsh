#version 330

// Experimental advanced 3D terrain shader (Fabric 26.2): base colour from the atlas, contour lines
// derived from world Y in screen space (fwidth gives an anti-aliased, zoom-independent line width).

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;
in float worldY;
flat in ivec2 contourParams;

out vec4 fragColor;

void main() {
    vec4 base = texture(Sampler0, texCoord0) * vertexColor;
    // Unused atlas slots stay transparent; the mesher never emits empty cells either.
    if (base.a < 0.1) {
        discard;
    }

    // Contours: UV2 encodes interval/width from the mesher (already multiplied by verticalScale3d).
    if ((contourParams.y & 1) != 0 && contourParams.x > 0) {
        float interval = float(contourParams.x) / 16.0;
        float width = float(contourParams.y >> 1) / 16.0;
        float d = abs(fract(worldY / interval + 0.5) - 0.5) * interval;
        float aa = max(fwidth(worldY), 1.0e-4);
        float line = 1.0 - smoothstep(width * 0.5, width * 0.5 + aa, d);
        base.rgb *= 1.0 - 0.45 * line;
    }

    fragColor = base * ColorModulator;
}
