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
    vec2 uv = vec2(
        U_SpriteUV.x + texCoord0.x * (U_SpriteUV.z - U_SpriteUV.x),
        U_SpriteUV.y + texCoord0.y * (U_SpriteUV.w - U_SpriteUV.y)
    );
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) {
        discard;
    }
    if (HDRMode == 0) {
        color.rgb += HDR.rgb;
    } else {
        color.rgb *= HDR.rgb;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
