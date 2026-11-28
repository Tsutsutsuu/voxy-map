#version 330

// Experimental advanced 3D terrain shader (Fabric 26.2).
//
// The 3D atlas is the same coloured texture the 2D map uses, including its baked hillshade, so the
// top faces already carry the map lighting. The vertex colour only carries the 3D-only AO (a grey
// value; side walls also bake a fixed face-darkening factor into it). This shader therefore just
// multiplies the texture by that grey value.

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    fragColor = vec4(tex.rgb * vertexColor.rgb, tex.a);
}
