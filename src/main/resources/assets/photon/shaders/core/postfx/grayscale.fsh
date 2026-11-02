#version 330
#extension GL_ARB_separate_shader_objects : require

// Luminance desaturation (three.js LuminosityShader-style), Amount = 0..1 blend.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Amount;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float luma = dot(texel.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(mix(texel.rgb, vec3(luma), Amount), texel.a);
}
