#version 330

// Bends the finished frame by what KilaMaterial's stacked screen distortions added up (StackedDistortion).

#moj_import <photon:engine.glsl>

uniform sampler2D DiffuseSampler;
// rg = the summed push in screen uv, b = how far their tints darken (negative brightens),
// a = 1 / (1 + the nearest distortion's eye depth), 0 where none drew — ⚠️ MIRRORED FROM kila.fsh (KILA_SCREEN_STACK)
uniform sampler2D OffsetSampler;
uniform sampler2D DepthSampler;

out vec4 fragColor;

float eye_from_ndcz(float ndcZ) {
    vec4 view = U_InverseProjectionMatrix * vec4(0.0, 0.0, ndcZ, 1.0);
    return -(view.z / view.w);
}

void main() {
    // texelFetch: offsets and frame match texel for texel
    vec4 offset = texelFetch(OffsetSampler, ivec2(gl_FragCoord.xy), 0);
    vec2 size = vec2(textureSize(DiffuseSampler, 0));
    vec2 here = gl_FragCoord.xy / size;
    vec2 push = offset.rg;
    if (offset.a > 0.0) {
        // what stands in front of the nearest distortion is not pulled into it
        float nearest = 1.0 / offset.a - 1.0;
        float there = eye_from_ndcz(texture(DepthSampler, here + push).r * 2.0 - 1.0);
        push *= clamp((there - nearest) / 0.25, 0.0, 1.0);
    }
    fragColor = vec4(texture(DiffuseSampler, here + push).rgb * max(1.0 - offset.b, 0.0), 1.0);
}
