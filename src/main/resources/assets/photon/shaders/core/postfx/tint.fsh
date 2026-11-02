#version 330
#extension GL_ARB_separate_shader_objects : require

// Color multiply: TintColor.rgb scales the scene, .a blends the effect (gradient-friendly).

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    vec4 TintColor;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    fragColor = vec4(mix(texel.rgb, texel.rgb * TintColor.rgb, TintColor.a), texel.a);
}
