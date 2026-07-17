#version 150

// Additive combine: base + AddSampler * Strength (the bloom composite).

uniform sampler2D DiffuseSampler;
uniform sampler2D AddSampler;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 base = texture(DiffuseSampler, texCoord);
    vec3 add = texture(AddSampler, texCoord).rgb;
    fragColor = vec4(base.rgb + add * Strength, base.a);
}
