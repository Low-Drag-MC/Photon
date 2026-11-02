#version 330
#extension GL_ARB_separate_shader_objects : require

// Barrel (>0) / pincushion (<0) distortion with a small rescale to hide the borders.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Amount;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = texCoord - 0.5;
    float r2 = dot(uv, uv);
    uv *= 1.0 + Amount * r2;
    uv = uv / (1.0 + Amount * 0.25) + 0.5;
    fragColor = texture(DiffuseSampler, clamp(uv, 0.0, 1.0));
}
