#version 150

// Mosaic: snaps sampling to PixelSize-sized blocks (RenderPixelatedPass-style).

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float PixelSize;
    vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 blockUv = DiffuseSampler_TexelSize.zw * max(PixelSize, 1.0);
    vec2 uv = (floor(texCoord / blockUv) + 0.5) * blockUv;
    fragColor = texture(DiffuseSampler, uv);
}
