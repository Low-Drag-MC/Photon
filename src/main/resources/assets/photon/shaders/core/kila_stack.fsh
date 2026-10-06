#version 150

// Bends the finished frame by what KilaMaterial's stacked screen distortions added up (StackedDistortion).

uniform sampler2D DiffuseSampler;
// rg = the summed push in screen uv, b = how far their tints darken (negative brightens), a = the nearest distortion's eye depth
uniform sampler2D OffsetSampler;
uniform sampler2D DepthSampler;
uniform mat4 U_InverseProjectionMatrix;
// x = 1 while the scene depth is bound
uniform vec4 KilaStackParams;

out vec4 fragColor;

float eye_from_ndcz(float ndcZ) {
    vec4 view = U_InverseProjectionMatrix * vec4(0.0, 0.0, ndcZ, 1.0);
    return -(view.z / view.w);
}

void main() {
    // texelFetch: the offsets are frame-sized, whatever viewport the editor's scene left set
    vec4 offset = texelFetch(OffsetSampler, ivec2(gl_FragCoord.xy), 0);
    vec2 size = vec2(textureSize(DiffuseSampler, 0));
    vec2 here = gl_FragCoord.xy / size;
    vec2 push = offset.rg;
    if (KilaStackParams.x > 0.5 && offset.a < 60000.0) {
        float there = eye_from_ndcz(texture(DepthSampler, here + push).r * 2.0 - 1.0);
        push *= clamp((there - offset.a) / 0.25, 0.0, 1.0);
    }
    fragColor = vec4(texture(DiffuseSampler, here + push).rgb * max(1.0 - offset.b, 0.0), 1.0);
}
