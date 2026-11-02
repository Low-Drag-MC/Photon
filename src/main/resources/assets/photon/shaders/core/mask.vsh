#version 330
#extension GL_ARB_separate_shader_objects : require

// The CustomMask sub-pass vertex stage: the same geometry core/particle.vsh transforms (shared
// getParticleData(), so every instanced variant works under its own define), minus lighting and fog
// — the mask only needs a position and the uv the alpha clip samples.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <photon:particle.glsl>

layout(location = 0) out vec2 texCoord0;

void main() {
    ParticleData data = getParticleData();

    vec3 pos = data.Position;
#ifdef PHOTON_INSTANCED
    // editor scenes extract eye-relative; ModelOffset carries the delta back (zero in-world)
    pos += ModelOffset;
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    texCoord0 = data.UV;
}
