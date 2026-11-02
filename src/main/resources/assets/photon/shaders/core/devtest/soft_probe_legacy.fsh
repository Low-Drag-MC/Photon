#version 330
#extension GL_ARB_separate_shader_objects : require

// Dev test shader (not shipped): the forward-Z twin of soft_probe_native, the same fade in a legacy shader.

#include <photon:soft_particle.glsl>

layout(std140) uniform PhotonCustomMaterial {
    vec4 SoftParticleParams;
};

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(vec3(photon_soft_particle(vec4(1.0), SoftParticleParams).a), 1.0);
}
