#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom prefilter: keeps energy above Threshold, proportionally per pixel.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Threshold;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float brightness = max(texel.r, max(texel.g, texel.b));
    float contribution = max(brightness - Threshold, 0.0) / max(brightness, 0.0001);
    fragColor = vec4(texel.rgb * contribution, texel.a);
}
