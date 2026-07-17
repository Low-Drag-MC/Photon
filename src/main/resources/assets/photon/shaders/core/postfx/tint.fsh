#version 150

// Color multiply: TintColor.rgb scales the scene, .a blends the effect (gradient-friendly).

uniform sampler2D DiffuseSampler;
uniform vec4 TintColor;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    fragColor = vec4(mix(texel.rgb, texel.rgb * TintColor.rgb, TintColor.a), texel.a);
}
