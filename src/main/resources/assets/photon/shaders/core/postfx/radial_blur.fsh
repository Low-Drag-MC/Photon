#version 150

// Zoom blur toward Center; Strength = how far the ray samples reach (0..1).

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    vec2 Center;
    float Strength;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 dir = texCoord - Center;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 12; i++) {
        float scale = 1.0 - Strength * (float(i) / 11.0);
        sum += texture(DiffuseSampler, Center + dir * scale);
    }
    fragColor = sum / 12.0;
}
