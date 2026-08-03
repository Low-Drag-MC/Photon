#version 150

// Merges Photon's standalone premultiplied FX layer onto whatever holds the picture — MC's main
// target once the clouds are already on it (FXCompositeMode.LATE), or a shader pack's translucent
// target. The pipeline blends ONE / ONE_MINUS_SRC_ALPHA, so RGB must already be premultiplied by
// coverage; PremultipliedBlendPlan is what guarantees every draw into the layer left it that way.
//
// Colour and coverage both come from the layer: bloom's passes declare WRITE_COLOR, so running it
// over the layer first leaves the alpha channel — the coverage — exactly as the FX draws left it.

uniform sampler2D colorSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(colorSampler, texCoord);
}
