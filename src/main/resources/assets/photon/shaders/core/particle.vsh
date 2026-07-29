#version 330 core

#moj_import <fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <photon:particle.glsl>

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    ParticleData data = getParticleData();

    vec3 pos = data.Position;
#if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE) || defined(TRAIL_INSTANCE) \
 || defined(ARA_TRAIL_INSTANCE) || defined(ARA_TRAIL_TUBE_INSTANCE) || defined(BEAM_INSTANCE)
    // 26.1 editor scenes extract eye-relative (SceneCamera position() is zero by design);
    // ModelOffset carries the facing-eye -> render-origin delta back (zero in-world)
    pos += ModelOffset;
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    texCoord0 = data.UV;
    // 26.1: the lightmap is GPU-generated and texelFetch(LightUV/16) no longer lands on valid
    // texels — vanilla particle.vsh switched to sample_lightmap(); same forced delta here
    vertexColor = data.Color * sample_lightmap(Sampler2, data.LightUV);
}
