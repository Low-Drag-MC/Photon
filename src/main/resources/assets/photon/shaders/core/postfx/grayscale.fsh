#version 150

// Luminance desaturation (three.js LuminosityShader-style), Amount = 0..1 blend.

uniform sampler2D DiffuseSampler;
uniform float Amount;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float luma = dot(texel.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(mix(texel.rgb, vec3(luma), Amount), texel.a);
}
