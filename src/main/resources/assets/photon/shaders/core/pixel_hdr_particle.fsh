#version 150

#moj_import <fog.glsl>
#moj_import <photon:soft_particle.glsl>
#moj_import <photon:lit_particle.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float DiscardThreshold;
uniform vec4 HDR;
uniform int HDRMode;
uniform float Bits;

in float vertexDistance;
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
    color.rgb += texel.rgb * photonBaseColor * ColorModulator.rgb * photon_particle_light(photonViewPos);
    if (HDRMode == 0) {
        color.rgb += HDR.rgb;
    } else {
        color.rgb *= HDR.rgb;
    }
    fragColor = photon_soft_particle(linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor));
}
