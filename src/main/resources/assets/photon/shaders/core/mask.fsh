#version 150

// CustomMask flat output: R = the pass's mask value (maskValue / 255). Post effects read this
// through the Custom Mask input node and match groups by round(R * 255).
// AlphaCutoff > 0 enables alpha clip: fragments where the pass's texture alpha falls below the
// cutoff are discarded, so the mask hugs the sprite's shape instead of the whole quad.

uniform sampler2D Sampler0;

layout(std140) uniform PhotonMask {
    float MaskValue;
    float AlphaCutoff;
};

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    if (AlphaCutoff > 0.0 && texture(Sampler0, texCoord0).a < AlphaCutoff) {
        discard;
    }
    fragColor = vec4(MaskValue, 0.0, 0.0, 1.0);
}
