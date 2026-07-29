#version 150

#moj_import <fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(std140) uniform PhotonMaterial {
    vec4 HDR;
    vec4 U_SpriteUV; // uo vo u1 v1
    float DiscardThreshold;
    int HDRMode;
    float Bits;
};


in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float bits = max(Bits, 1.0);
    vec2 uv = (floor(texCoord0 * bits) + 0.5) / bits;
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) {
        discard;
    }
    if (HDRMode == 0) {
        color.rgb += HDR.a * HDR.rgb;
    } else {
        color.rgb *= HDR.a * HDR.rgb;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
