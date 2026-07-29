#version 150

uniform sampler2D inputSampler;

layout(std140) uniform PhotonBloom {
    float Knee;     // [0,1]：soft-knee
    float Threshold;      // luma threhold
};

// 26.1 deltas: the chain runs in ENCODED space when the backend has no float color formats
// (RGBA8 targets store values / PHOTON_HDR_SCALE); OUTPUT_SCALE lets the composite's
// highlight-subtract draw reuse this shader. Both default to 1.0 = the verbatim 1.21 math.
#ifndef PHOTON_HDR_SCALE
#define PHOTON_HDR_SCALE 1.0
#endif
#ifndef OUTPUT_SCALE
#define OUTPUT_SCALE 1.0
#endif

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main(){
    vec3 c = texture(inputSampler, texCoord).rgb * PHOTON_HDR_SCALE;
    float br = dot(c, LUMA);
    // soft-knee
    float knee = Threshold * Knee;
    float soft = clamp((br - Threshold + knee) / (2.0 * knee), 0.0, 1.0);
    float bright = max(br - Threshold, 0.0) + soft * soft * 2.0 * knee;
    fragColor = vec4(c * (bright / max(br, 1e-5)) * (OUTPUT_SCALE / PHOTON_HDR_SCALE), 1.0);  // 仅保留亮区
}
