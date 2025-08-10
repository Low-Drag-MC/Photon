#version 330 core

#moj_import <fog.glsl>
#moj_import <photon:particle.glsl>

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

void main() {
    ParticleData data = getParticleData();

    gl_Position = ProjMat * ModelViewMat * vec4(data.Position, 1.0);
}
