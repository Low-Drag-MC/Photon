#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// every input up to the last one read, in PARTICLE_FORMAT's order: Vulkan numbers them by counting
in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
}
