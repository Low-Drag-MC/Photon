#version 330
#extension GL_ARB_separate_shader_objects : require

// converted to the Photon 26.1 custom-shader contract: the 1.21 plain uniforms live in
// the fixed PhotonCustomMaterial std140 block (dynamic values, no recompiles); the shader
// JSON stays as the material configurator's uniform metadata

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(std140) uniform PhotonCustomMaterial {
    float DiscardThreshold;
    vec4 HDR;
    float Radius;
};


layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    float dist = distance(vec2(0.5, 0.5), texCoord0);
    vec4 color = vertexColor * ColorModulator;
    color.a = smoothstep(0., 1., 1. - dist / Radius) * color.a;
    if (color.a < DiscardThreshold) {
        discard;
    }
    color.rgb += HDR.rgb * HDR.a;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
