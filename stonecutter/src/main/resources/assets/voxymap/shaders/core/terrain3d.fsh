#version 330

// Experimental advanced 3D terrain shader (Fabric 26.2).
// Vertex colour encodes a heightfield normal and the ambient-occlusion byte:
//   R = normal.x in 0..1, G = normal.z in 0..1, B = AO 0..1, A = 255
// (ny is reconstructed, the terrain normals always point up).
// The atlas already contains base colour, coastline shading, water depth and contour lines
// (advanced 3D renders the styled 2D colour with hillshade disabled).
//
// Light parameters ride in DynamicTransforms.ColorModulator:
//   r = directional light strength (0..1), g = ambient fraction (0..1)
// so no custom uniform block is needed on 26.2.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const vec3 LIGHT_DIR = normalize(vec3(-0.55, 0.70, -0.55));

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }

    vec2 nxz = vertexColor.rg * 2.0 - 1.0;
    float ny = sqrt(max(0.0, 1.0 - dot(nxz, nxz)));
    float ao = vertexColor.b;

    float strength = clamp(ColorModulator.r, 0.0, 1.0);
    float ambient = clamp(ColorModulator.g, 0.0, 1.0);

    float diffuse = max(0.0, dot(vec3(nxz.x, ny, nxz.y), LIGHT_DIR));
    // Same directional bias as the 2D hillshade: a flat surface stays at its base colour,
    // surfaces facing the light brighten and surfaces facing away darken.
    float shade = 1.0 + strength * 1.9 * (diffuse - LIGHT_DIR.y);
    shade = clamp(shade, 0.42, 1.38);
    shade = ambient + (1.0 - ambient) * shade;

    fragColor = vec4(tex.rgb * shade * ao, tex.a);
}
