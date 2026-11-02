#version 330
#extension GL_ARB_separate_shader_objects : require

// Forward-Z scene depth (1 - reversed depth) for legacy custom shaders. See PhotonSceneCapture.legacyDepth.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(1.0 - texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).r, 0.0, 0.0, 1.0);
}
