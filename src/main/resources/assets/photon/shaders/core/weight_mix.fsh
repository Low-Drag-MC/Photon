#version 330
#extension GL_ARB_separate_shader_objects : require

// The auto final mix of the post-effect chain: result = mix(chain input, effect output, Weight) —
// the universal fade every effect gets when its blended request weight < 1.

uniform sampler2D SamplerA; // chain input
uniform sampler2D SamplerB; // effect output
layout(std140) uniform PhotonPass {
    float Weight;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = mix(texture(SamplerA, texCoord), texture(SamplerB, texCoord), Weight);
}
