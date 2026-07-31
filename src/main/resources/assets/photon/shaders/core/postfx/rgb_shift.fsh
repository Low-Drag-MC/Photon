#version 150

// three.js RGBShiftShader (chromatic aberration): Amount = UV offset, Angle in radians.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Amount;
    float Angle;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 offset = Amount * vec2(cos(Angle), sin(Angle));
    vec4 cr = texture(DiffuseSampler, texCoord + offset);
    vec4 cga = texture(DiffuseSampler, texCoord);
    vec4 cb = texture(DiffuseSampler, texCoord - offset);
    fragColor = vec4(cr.r, cga.g, cb.b, cga.a);
}
