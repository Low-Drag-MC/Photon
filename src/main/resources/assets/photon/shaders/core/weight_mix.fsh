#version 150

// The auto final mix of the post-effect chain: result = mix(chain input, effect output, Weight) —
// the universal fade every effect gets when its blended request weight < 1.

uniform sampler2D SamplerA; // chain input
uniform sampler2D SamplerB; // effect output
layout(std140) uniform PhotonPass {
    float Weight;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = mix(texture(SamplerA, texCoord), texture(SamplerB, texCoord), Weight);
}
