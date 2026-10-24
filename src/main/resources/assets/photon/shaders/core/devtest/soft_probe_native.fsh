#version 330

// Dev test shader (not shipped): the soft-particle fade as grey.

#moj_import <photon:soft_particle.glsl>

layout(std140) uniform PhotonCustomMaterial {
    vec4 SoftParticleParams;
};

out vec4 fragColor;

void main() {
    fragColor = vec4(vec3(photon_soft_particle(vec4(1.0), SoftParticleParams).a), 1.0);
}
