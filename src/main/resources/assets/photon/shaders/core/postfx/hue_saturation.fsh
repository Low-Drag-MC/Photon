#version 150

// three.js HueSaturationShader: Hue -1..1 (full rotation), Saturation -1..1, 0 = no change.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Hue;
    float Saturation;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float angle = Hue * 3.14159265;
    float s = sin(angle);
    float c = cos(angle);
    vec3 weights = (vec3(2.0 * c, -sqrt(3.0) * s - c, sqrt(3.0) * s - c) + 1.0) / 3.0;
    texel.rgb = vec3(
        dot(texel.rgb, weights.xyz),
        dot(texel.rgb, weights.zxy),
        dot(texel.rgb, weights.yzx));
    float average = (texel.r + texel.g + texel.b) / 3.0;
    if (Saturation > 0.0) {
        texel.rgb += (average - texel.rgb) * (1.0 - 1.0 / (1.001 - Saturation));
    } else {
        texel.rgb += (average - texel.rgb) * (-Saturation);
    }
    fragColor = texel;
}
