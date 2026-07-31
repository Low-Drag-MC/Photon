#version 150

// three.js DotScreenShader: halftone dot grid, Scale = dot density, Angle in radians.

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float Scale;
    float Angle;
    vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 pixel = texCoord * DiffuseSampler_TexelSize.xy - DiffuseSampler_TexelSize.xy * 0.5;
    float s = sin(Angle);
    float c = cos(Angle);
    vec2 point = vec2(c * pixel.x - s * pixel.y, s * pixel.x + c * pixel.y) * (3.14159265 / (max(Scale, 0.01) * 10.0));
    float pattern = sin(point.x) * sin(point.y) * 4.0;
    vec4 texel = texture(DiffuseSampler, texCoord);
    float average = (texel.r + texel.g + texel.b) / 3.0;
    fragColor = vec4(clamp(vec3(average * 10.0 - 5.0 + pattern), 0.0, 1.0), texel.a);
}
