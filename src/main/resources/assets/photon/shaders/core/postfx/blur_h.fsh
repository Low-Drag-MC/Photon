#version 150

// Horizontal 9-tap gaussian (linear-sampling optimized, 5 fetches). Radius scales the kernel.

uniform sampler2D DiffuseSampler;
uniform vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
uniform float Radius;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 dir = vec2(DiffuseSampler_TexelSize.z, 0.0) * Radius;
    vec4 center = texture(DiffuseSampler, texCoord);
    vec3 result = center.rgb * 0.2270270;
    result += (texture(DiffuseSampler, texCoord + dir * 1.3846154).rgb
             + texture(DiffuseSampler, texCoord - dir * 1.3846154).rgb) * 0.3162162;
    result += (texture(DiffuseSampler, texCoord + dir * 3.2307692).rgb
             + texture(DiffuseSampler, texCoord - dir * 3.2307692).rgb) * 0.0702703;
    fragColor = vec4(result, center.a);
}
