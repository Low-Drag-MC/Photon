#version 330 core

#moj_import <fog.glsl>
#moj_import <photon:particle.glsl>

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec3 photonViewPos;
out vec3 photonBaseColor;

void main() {
    ParticleData data = getParticleData();

    vec4 viewPos = ModelViewMat * vec4(data.Position, 1.0);
    gl_Position = ProjMat * viewPos;
    photonViewPos = viewPos.xyz;
    photonBaseColor = data.Color.rgb;

    vertexDistance = fog_distance(data.Position, FogShape);
    texCoord0 = data.UV;
    vertexColor = data.Color * texelFetch(Sampler2, data.LightUV / 16, 0);
}
