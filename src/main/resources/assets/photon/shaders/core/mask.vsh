#version 330 core

#moj_import <photon:particle.glsl>

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;

void main() {
    ParticleData data = getParticleData();

    gl_Position = ProjMat * ModelViewMat * vec4(data.Position, 1.0);
    texCoord0 = data.UV;
}
