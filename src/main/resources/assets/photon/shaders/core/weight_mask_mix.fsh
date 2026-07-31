#version 150

// The auto final mix with CustomMask culling: result = mix(scene, effect, Weight * match(mask)).
// MaskFilter 0 matches ANY non-zero mask id, otherwise the exact group; outside the mask the
// scene passes through untouched — this is what makes EVERY effect per-object capable with zero
// graph cooperation (same idea as UE post-process materials lerping SceneColor by CustomStencil).

uniform sampler2D SamplerA; // chain input (scene)
uniform sampler2D SamplerB; // effect output
uniform sampler2D MaskSampler;

layout(std140) uniform PhotonPass {
    float Weight;
    float MaskFilter;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float id = texture(MaskSampler, texCoord).r * 255.0;
    float match = MaskFilter < 0.5 ? step(0.5, id) : step(abs(id - MaskFilter), 0.5);
    fragColor = mix(texture(SamplerA, texCoord), texture(SamplerB, texCoord), Weight * match);
}
