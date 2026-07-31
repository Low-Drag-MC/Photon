#version 150

// Per-object outline (Unreal CustomStencil-style): a Sobel edge over the "mask matches
// MaskFilter" binary image, drawn onto the scene in OutlineColor (a = strength).
// MaskFilter 0 matches ANY non-zero mask id.

uniform sampler2D DiffuseSampler;
uniform sampler2D MaskSampler;

layout(std140) uniform PhotonPass {
    vec4 OutlineColor;
    float MaskFilter;
    float Thickness;
    vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
};

in vec2 texCoord;
out vec4 fragColor;

float matches(vec2 uv) {
    float id = texture(MaskSampler, uv).r * 255.0;
    if (MaskFilter < 0.5) return step(0.5, id);
    return step(abs(id - MaskFilter), 0.5);
}

void main() {
    vec2 o = DiffuseSampler_TexelSize.zw * max(Thickness, 0.01);
    float m00 = matches(texCoord + vec2(-o.x,  o.y));
    float m01 = matches(texCoord + vec2( 0.0,  o.y));
    float m02 = matches(texCoord + vec2( o.x,  o.y));
    float m10 = matches(texCoord + vec2(-o.x,  0.0));
    float m12 = matches(texCoord + vec2( o.x,  0.0));
    float m20 = matches(texCoord + vec2(-o.x, -o.y));
    float m21 = matches(texCoord + vec2( 0.0, -o.y));
    float m22 = matches(texCoord + vec2( o.x, -o.y));

    float gx = (m00 + 2.0 * m10 + m20) - (m02 + 2.0 * m12 + m22);
    float gy = (m00 + 2.0 * m01 + m02) - (m20 + 2.0 * m21 + m22);
    float edge = clamp(length(vec2(gx, gy)), 0.0, 1.0);

    vec4 texel = texture(DiffuseSampler, texCoord);
    fragColor = vec4(mix(texel.rgb, OutlineColor.rgb, edge * clamp(OutlineColor.a, 0.0, 1.0)), texel.a);
}
