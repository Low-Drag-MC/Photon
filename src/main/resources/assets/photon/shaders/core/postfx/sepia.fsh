#version 330
#extension GL_ARB_separate_shader_objects : require

// Sepia tone (three.js SepiaShader matrix), Amount = 0..1 blend.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Amount;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    vec3 sepia = vec3(
        dot(texel.rgb, vec3(0.393, 0.769, 0.189)),
        dot(texel.rgb, vec3(0.349, 0.686, 0.168)),
        dot(texel.rgb, vec3(0.272, 0.534, 0.131)));
    fragColor = vec4(mix(texel.rgb, sepia, Amount), texel.a);
}
