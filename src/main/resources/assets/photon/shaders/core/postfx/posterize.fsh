#version 150

// Color quantization to Levels steps per channel.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Levels;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float levels = max(Levels, 2.0);
    vec3 quantized = min(floor(texel.rgb * levels), levels - 1.0) / (levels - 1.0);
    fragColor = vec4(quantized, texel.a);
}
