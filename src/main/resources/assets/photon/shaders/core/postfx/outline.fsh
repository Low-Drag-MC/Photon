#version 150

// Depth + luma Sobel edge overlay: edges tint the scene with OutlineColor (rgb, a = strength).
// DepthThreshold gates raw-depth gradients (small values pick up nearby silhouettes),
// LumaThreshold gates color edges, Thickness widens the kernel in texels.

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
uniform vec4 OutlineColor;
uniform float DepthThreshold;
uniform float LumaThreshold;
uniform float Thickness;

in vec2 texCoord;
out vec4 fragColor;

float lumaAt(vec2 uv) {
    return dot(texture(DiffuseSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
}

float depthAt(vec2 uv) {
    return texture(DepthSampler, uv).r;
}

void main() {
    vec2 o = DiffuseSampler_TexelSize.zw * max(Thickness, 0.01);
    // 3x3 Sobel over both depth and luma
    float d00 = depthAt(texCoord + vec2(-o.x,  o.y)); float l00 = lumaAt(texCoord + vec2(-o.x,  o.y));
    float d01 = depthAt(texCoord + vec2( 0.0,  o.y)); float l01 = lumaAt(texCoord + vec2( 0.0,  o.y));
    float d02 = depthAt(texCoord + vec2( o.x,  o.y)); float l02 = lumaAt(texCoord + vec2( o.x,  o.y));
    float d10 = depthAt(texCoord + vec2(-o.x,  0.0)); float l10 = lumaAt(texCoord + vec2(-o.x,  0.0));
    float d12 = depthAt(texCoord + vec2( o.x,  0.0)); float l12 = lumaAt(texCoord + vec2( o.x,  0.0));
    float d20 = depthAt(texCoord + vec2(-o.x, -o.y)); float l20 = lumaAt(texCoord + vec2(-o.x, -o.y));
    float d21 = depthAt(texCoord + vec2( 0.0, -o.y)); float l21 = lumaAt(texCoord + vec2( 0.0, -o.y));
    float d22 = depthAt(texCoord + vec2( o.x, -o.y)); float l22 = lumaAt(texCoord + vec2( o.x, -o.y));

    float dgx = (d00 + 2.0 * d10 + d20) - (d02 + 2.0 * d12 + d22);
    float dgy = (d00 + 2.0 * d01 + d02) - (d20 + 2.0 * d21 + d22);
    float depthEdge = step(DepthThreshold, length(vec2(dgx, dgy)));

    float lgx = (l00 + 2.0 * l10 + l20) - (l02 + 2.0 * l12 + l22);
    float lgy = (l00 + 2.0 * l01 + l02) - (l20 + 2.0 * l21 + l22);
    float lumaEdge = step(LumaThreshold, length(vec2(lgx, lgy)));

    float edge = max(depthEdge, lumaEdge);
    vec4 texel = texture(DiffuseSampler, texCoord);
    fragColor = vec4(mix(texel.rgb, OutlineColor.rgb, edge * clamp(OutlineColor.a, 0.0, 1.0)), texel.a);
}
