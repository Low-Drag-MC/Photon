#version 330

// Texel-for-texel copy between same-sized targets of different formats (PhotonFullscreenPass.copy).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
