#version 150

// Bakes "mask id ∈ {Ids}" into a binary mask (R=1 where any listed group wrote) so a merged
// invocation covering several groups culls EXACTLY their union.

uniform sampler2D MaskSampler;

layout(std140) uniform PhotonPass {
    vec4 IdsA;
    vec4 IdsB;
    float IdCount;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float id = texture(MaskSampler, texCoord).r * 255.0;
    float ids[8] = float[8](IdsA.x, IdsA.y, IdsA.z, IdsA.w, IdsB.x, IdsB.y, IdsB.z, IdsB.w);
    float match = 0.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) < IdCount && abs(id - ids[i]) < 0.5) {
            match = 1.0;
        }
    }
    fragColor = vec4(match, 0.0, 0.0, 1.0);
}
