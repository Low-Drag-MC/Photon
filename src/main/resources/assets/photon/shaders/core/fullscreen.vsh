#version 330

// Shared fullscreen vertex stage: Position is PhotonFullscreenPass's NDC triangle, texCoord its 0..1 uv.

in vec3 Position;

out vec2 texCoord;

void main() {
    texCoord = Position.xy * 0.5 + 0.5;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
