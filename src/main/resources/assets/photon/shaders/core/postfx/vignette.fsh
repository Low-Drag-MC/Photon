#version 150

// three.js VignetteShader: Offset scales the falloff start, Darkness the edge strength.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Offset;
    float Darkness;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    vec2 uv = (texCoord - 0.5) * Offset;
    fragColor = vec4(mix(texel.rgb, vec3(1.0 - Darkness), dot(uv, uv)), texel.a);
}
