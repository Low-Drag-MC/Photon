#version 150

#moj_import <fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <photon:soft_particle.glsl>
#moj_import <photon:lit_particle.glsl>

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

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec3 photonViewPos;
in vec3 photonBaseColor;

out vec4 fragColor;

void main() {
    float bits = max(Bits, 1.0);
    vec2 uv = (floor(texCoord0 * bits) + 0.5) / bits;
    vec4 texel = texture(Sampler0, uv);
    vec4 color = texel * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) {
        discard;
    }
    color.rgb += texel.rgb * photonBaseColor * ColorModulator.rgb * (LitIntensity * photon_particle_light(photonViewPos));
    if (HDRMode == 0) {
        color.rgb += HDR.rgb;
    } else {
        color.rgb *= HDR.rgb;
    }
    fragColor = photon_soft_particle(apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor), SoftParticleParams);
}
