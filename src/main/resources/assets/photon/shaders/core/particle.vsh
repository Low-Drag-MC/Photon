#version 330
#extension GL_ARB_separate_shader_objects : require

#include <fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <photon:particle.glsl>

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;
layout(location = 4) out vec3 photonViewPos;
layout(location = 5) out vec3 photonBaseColor;

void main() {
    ParticleData data = getParticleData();

    vec3 pos = data.Position;
#ifdef PHOTON_INSTANCED
    // 26.1 editor scenes extract eye-relative (SceneCamera position() is zero by design);
    // ModelOffset carries the facing-eye -> render-origin delta back (zero in-world)
    pos += ModelOffset;
#endif

    vec4 viewPos = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * viewPos;
    // what the dynamic lights are gathered at, and the unlit colour they multiply (photon:lit_particle.glsl)
    photonViewPos = viewPos.xyz;
    photonBaseColor = data.Color.rgb;

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    texCoord0 = data.UV;
    // 26.1: the lightmap is GPU-generated and texelFetch(LightUV/16) no longer lands on valid
    // texels — vanilla particle.vsh switched to sample_lightmap(); same forced delta here
    vertexColor = data.Color * sample_lightmap(Sampler2, data.LightUV);
}
