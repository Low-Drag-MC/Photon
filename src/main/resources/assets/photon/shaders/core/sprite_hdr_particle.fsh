#version 330
#extension GL_ARB_separate_shader_objects : require

#include <fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <photon:soft_particle.glsl>
#include <photon:lit_particle.glsl>

uniform sampler2D Sampler0;

layout(std140) uniform PhotonMaterial {
    vec4 HDR;
    vec4 U_SpriteUV; // uo vo u1 v1
    float DiscardThreshold;
    int HDRMode;
    float Bits;
    float LitIntensity; // LitParticles: 0 = unlit, and then PHOTON_LIT is not compiled in
    vec4 SoftParticleParams; // distance, power, alphaOnly, enabled
};

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;
layout(location = 4) in vec3 photonViewPos;
layout(location = 5) in vec3 photonBaseColor;

#include <photon:oit.glsl>

void main() {
    vec2 uv = vec2(
        U_SpriteUV.x + texCoord0.x * (U_SpriteUV.z - U_SpriteUV.x),
        U_SpriteUV.y + texCoord0.y * (U_SpriteUV.w - U_SpriteUV.y)
    );
    vec4 texel = texture(Sampler0, uv);
    vec4 color = texel * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) {
        discard;
    }
#ifndef OIT_ALPHA_ONLY
    // colour only: the alpha-only OIT phases skip the lights' cluster walk
    color.rgb += texel.rgb * photonBaseColor * ColorModulator.rgb * (LitIntensity * photon_particle_light(photonViewPos));
#endif
    if (HDRMode == 0) {
        color.rgb += HDR.rgb;
    } else {
        color.rgb *= HDR.rgb;
    }
    photon_write(photon_soft_particle(apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor), SoftParticleParams));
}
