#version 330

// Shared fullscreen-quad vertex stage for the bloom chain: Position is a [0,1]² quad,
// passed through as texCoord and expanded to NDC.

in vec3 Position;

out vec2 texCoord;

void main() {
    texCoord = Position.xy;
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
}
