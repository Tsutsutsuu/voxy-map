#version 330

// Small terrain shader for the advanced 3D view (26.2): identical colour math to the vanilla block
// shader, but the fog distance is horizontal (XZ in view space) so height/pitch never adds fog.

#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out vec4 vertexColor;
out vec2 texCoord0;
out float horizontalFogDistance;

void main() {
    vec3 pos = Position + ModelOffset;
    vec4 viewPos = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * viewPos;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    // Camera-relative horizontal distance only: no vertical fog.
    horizontalFogDistance = length(viewPos.xz);
}
