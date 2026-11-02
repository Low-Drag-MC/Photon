#version 330
#extension GL_ARB_separate_shader_objects : require

// Shared fullscreen vertex stage: Position is PhotonFullscreenPass's NDC triangle, texCoord its 0..1 uv.

layout(location = 0) in vec3 Position;

layout(location = 0) out vec2 texCoord;

void main() {
    texCoord = Position.xy * 0.5 + 0.5;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
