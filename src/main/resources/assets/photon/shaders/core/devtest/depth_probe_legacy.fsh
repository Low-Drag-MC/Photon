#version 330
#extension GL_ARB_separate_shader_objects : require

// Dev test shader (not shipped): reads depth the forward-Z 1.21 way. Grey = distance / 32, magenta = sky.

#include <photon:engine.glsl>

uniform sampler2D SamplerSceneDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 screenUV = gl_FragCoord.xy / U_ViewPort.zw;
    float depth = texture(SamplerSceneDepth, screenUV).r;
    if (depth >= 1.0) {
        fragColor = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    }
    vec4 viewPos = U_InverseProjectionMatrix * vec4(screenUV * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    viewPos /= viewPos.w;
    fragColor = vec4(vec3(clamp(length(viewPos.xyz) / 32.0, 0.0, 1.0)), 1.0);
}
