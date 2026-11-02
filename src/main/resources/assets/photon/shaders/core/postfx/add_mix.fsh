#version 330
#extension GL_ARB_separate_shader_objects : require

// Additive combine: base + AddSampler * Strength (the bloom composite).

uniform sampler2D DiffuseSampler;
uniform sampler2D AddSampler;

layout(std140) uniform PhotonPass {
    float Strength;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 base = texture(DiffuseSampler, texCoord);
    vec3 add = texture(AddSampler, texCoord).rgb;
    fragColor = vec4(base.rgb + add * Strength, base.a);
}
