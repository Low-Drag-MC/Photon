#version 330
#extension GL_ARB_separate_shader_objects : require

// Texel-for-texel copy between same-sized targets of different formats (PhotonFullscreenPass.copy).

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
