#version 330

// Forward-Z scene depth (1 - reversed depth) for legacy custom shaders. See PhotonSceneCapture.legacyDepth.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(1.0 - texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).r, 0.0, 0.0, 1.0);
}
