#version 150

// three.js BrightnessContrastShader: Brightness/Contrast both -1..1, 0 = no change.

uniform sampler2D DiffuseSampler;
uniform float Brightness;
uniform float Contrast;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    vec3 color = texel.rgb + Brightness;
    if (Contrast > 0.0) {
        color = (color - 0.5) / max(1.0 - Contrast, 0.01) + 0.5;
    } else {
        color = (color - 0.5) * (1.0 + Contrast) + 0.5;
    }
    fragColor = vec4(color, texel.a);
}
