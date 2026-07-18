#version 150

// CustomMask debug view: mask ids (round(R*255)) hue-colored on black, so distinct groups are
// obvious at a glance. Empty mask = black screen.

uniform sampler2D MaskSampler;

in vec2 texCoord;
out vec4 fragColor;

vec3 hue(float h) {
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    float id = texture(MaskSampler, texCoord).r * 255.0;
    if (id < 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    fragColor = vec4(hue(fract(id * 0.618034)), 1.0);
}
