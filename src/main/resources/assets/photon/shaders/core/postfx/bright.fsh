#version 150

// Bloom prefilter: keeps energy above Threshold, proportionally per pixel.

uniform sampler2D DiffuseSampler;
uniform float Threshold;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float brightness = max(texel.r, max(texel.g, texel.b));
    float contribution = max(brightness - Threshold, 0.0) / max(brightness, 0.0001);
    fragColor = vec4(texel.rgb * contribution, texel.a);
}
