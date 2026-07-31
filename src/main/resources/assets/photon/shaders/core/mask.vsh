#version 330 core

// The CustomMask sub-pass vertex stage: the same geometry core/particle.vsh transforms (shared
// getParticleData(), so every instanced variant works under its own define), minus lighting and fog
// — the mask only needs a position and the uv the alpha clip samples.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <photon:particle.glsl>

out vec2 texCoord0;

void main() {
    ParticleData data = getParticleData();

    vec3 pos = data.Position;
#if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE) || defined(TRAIL_INSTANCE) \
 || defined(ARA_TRAIL_INSTANCE) || defined(ARA_TRAIL_TUBE_INSTANCE) || defined(BEAM_INSTANCE)
    // editor scenes extract eye-relative; ModelOffset carries the delta back (zero in-world)
    pos += ModelOffset;
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    texCoord0 = data.UV;
}
