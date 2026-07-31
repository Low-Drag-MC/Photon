#version 150

// Depth + luma Sobel edge overlay: edges tint the scene with OutlineColor (rgb, a = strength).
// LumaThreshold gates color edges, Thickness widens the kernel in texels.
//
// The depth Sobel runs on RECIPROCAL distance (1/blocks). Its gradient is d(1/z) = dz/z^2, so
// sensitivity falls off quadratically with distance — which is the same attenuation the raw depth
// buffer already had (z_window ~= 1 - n/z, hence dz_window ~= n * d(1/z)); this just makes the
// hidden near-plane factor explicit so the threshold no longer moves when the projection does.
//
// Metric (linearised) depth was tried here and is WORSE: a silhouette against a distant background
// produces a gradient three orders of magnitude larger than a nearby step, so the threshold either
// catches everything or nothing. The hyperbolic squash is a feature for an outline, not a defect.
//
// DepthThreshold therefore reads as "the smallest depth step resolved at 1 block away", in blocks,
// and the step it takes grows with the square of the distance: at threshold T a discontinuity is an
// edge once it exceeds T blocks at 1 block away, 100*T at 10 blocks, 10000*T at 100. The default 1.0
// is deliberately conservative — only pronounced, close silhouettes outline; lower it to reach
// further. ZNear/ZFar are the DRAWING view's planes, supplied by the executor (an editor scene
// reports its own, not the world's).

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform PhotonPass {
    vec4 OutlineColor;
    float DepthThreshold;
    float LumaThreshold;
    float Thickness;
    vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
    float ZNear;
    float ZFar;
};

in vec2 texCoord;
out vec4 fragColor;

float lumaAt(vec2 uv) {
    return dot(texture(DiffuseSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
}

// Reciprocal distance TIMES the constant 2*ZNear*ZFar. The scale is deliberately left out here and
// applied to the threshold instead: a Sobel is linear and the factor is positive, so the comparison is
// identical — but this way the per-tap divide disappears entirely (x/k -> x*(1/k) is not an
// IEEE-preserving rewrite, so a driver is under no obligation to hoist it).
float invDepthScaledAt(vec2 uv) {
    float ndc = texture(DepthSampler, uv).r * 2.0 - 1.0;
    return ZFar + ZNear - ndc * (ZFar - ZNear);
}

void main() {
    vec2 o = DiffuseSampler_TexelSize.zw * max(Thickness, 0.01);
    // 3x3 Sobel over both depth and luma
    float d00 = invDepthScaledAt(texCoord + vec2(-o.x,  o.y)); float l00 = lumaAt(texCoord + vec2(-o.x,  o.y));
    float d01 = invDepthScaledAt(texCoord + vec2( 0.0,  o.y)); float l01 = lumaAt(texCoord + vec2( 0.0,  o.y));
    float d02 = invDepthScaledAt(texCoord + vec2( o.x,  o.y)); float l02 = lumaAt(texCoord + vec2( o.x,  o.y));
    float d10 = invDepthScaledAt(texCoord + vec2(-o.x,  0.0)); float l10 = lumaAt(texCoord + vec2(-o.x,  0.0));
    float d12 = invDepthScaledAt(texCoord + vec2( o.x,  0.0)); float l12 = lumaAt(texCoord + vec2( o.x,  0.0));
    float d20 = invDepthScaledAt(texCoord + vec2(-o.x, -o.y)); float l20 = lumaAt(texCoord + vec2(-o.x, -o.y));
    float d21 = invDepthScaledAt(texCoord + vec2( 0.0, -o.y)); float l21 = lumaAt(texCoord + vec2( 0.0, -o.y));
    float d22 = invDepthScaledAt(texCoord + vec2( o.x, -o.y)); float l22 = lumaAt(texCoord + vec2( o.x, -o.y));

    float dgx = (d00 + 2.0 * d10 + d20) - (d02 + 2.0 * d12 + d22);
    float dgy = (d00 + 2.0 * d01 + d02) - (d20 + 2.0 * d21 + d22);
    // threshold scaled to match invDepthScaledAt's units; a pure uniform expression, folded once
    float depthEdge = step(DepthThreshold * (2.0 * ZNear * ZFar), length(vec2(dgx, dgy)));

    float lgx = (l00 + 2.0 * l10 + l20) - (l02 + 2.0 * l12 + l22);
    float lgy = (l00 + 2.0 * l01 + l02) - (l20 + 2.0 * l21 + l22);
    float lumaEdge = step(LumaThreshold, length(vec2(lgx, lgy)));

    float edge = max(depthEdge, lumaEdge);
    vec4 texel = texture(DiffuseSampler, texCoord);
    fragColor = vec4(mix(texel.rgb, OutlineColor.rgb, edge * clamp(OutlineColor.a, 0.0, 1.0)), texel.a);
}
