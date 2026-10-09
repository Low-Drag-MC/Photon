package com.lowdragmc.photon.uitest;

/**
 * Photon 1.21 (v2.2.8)'s {@code hdr_particle}, verbatim: a shader a 1.21 user could load as a custom shader, which
 * imports {@code photon:lit_particle.glsl} and {@code photon:soft_particle.glsl} and lists their entries in its json.
 * 1.21's {@code dynamic_light_materials} lit it as a custom shader; 26.1's ships a different {@code hdr_particle} (the
 * texture material's own), so this is the 1.21 one, put through {@code ShaderFormatConverter} as a user's would be.
 */
public final class LegacyLitShader {
    public static final String JSON = """
            {
                "vertex": "photon:particle",
                "fragment": "photon:hdr_particle",
                "samplers": [
                    { "name": "Sampler0" },
                    { "name": "Sampler2" },
                    { "name": "SamplerSceneDepth" },
                    { "name": "PhotonLightData" },
                    { "name": "PhotonClusterGrid" },
                    { "name": "PhotonClusterIndices" },
                    { "name": "PhotonVisibilityAtlas" }
                ],
                "uniforms": [
                    { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                    { "name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                    { "name": "ColorModulator", "type": "float", "count": 4, "values": [ 1.0, 1.0, 1.0, 1.0 ] },
                    { "name": "FogStart", "type": "float", "count": 1, "values": [ 0.0 ] },
                    { "name": "FogEnd", "type": "float", "count": 1, "values": [ 1.0 ] },
                    { "name": "FogColor", "type": "float", "count": 4, "values": [ 0.0, 0.0, 0.0, 0.0 ] },
                    { "name": "FogShape", "type": "int", "count": 1, "values": [ 0 ] },
                    { "name": "DiscardThreshold", "type": "float", "count": 1, "values": [ 0.01 ] },
                    { "name": "HDR", "type": "float", "count": 4, "values": [ 0.0, 0.0, 0.0, 1.0 ] },
                    { "name": "HDRMode", "type": "int", "count": 1, "values": [ 0 ] },
                    { "name": "U_InverseProjectionMatrix", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                    { "name": "SoftParticleParams", "type": "float", "count": 4, "values": [ 1.0, 1.0, 0.0, 0.0 ] },
                    { "name": "PhotonClusterInfo", "type": "int", "count": 4, "values": [ 64, 1, 1, 1 ] },
                    { "name": "PhotonClusterView", "type": "float", "count": 4, "values": [ 0.5, 1.0, 0.0, 0.0 ] },
                    { "name": "LitParticleParams", "type": "float", "count": 4, "values": [ 0.0, 0.0, 0.0, 0.0 ] },
                    { "name": "PhotonVisibilityInfo", "type": "int", "count": 4, "values": [ 128, 8, 0, 0 ] },
                    { "name": "PhotonViewInverse", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] }
                ]
            }
            """;

    public static final String FRAGMENT = """
            #version 150

            #moj_import <fog.glsl>
            #moj_import <photon:soft_particle.glsl>
            #moj_import <photon:lit_particle.glsl>

            uniform sampler2D Sampler0;

            uniform vec4 ColorModulator;
            uniform float FogStart;
            uniform float FogEnd;
            uniform vec4 FogColor;
            uniform float DiscardThreshold;
            uniform vec4 HDR;
            uniform int HDRMode;

            in float vertexDistance;
            in vec2 texCoord0;
            in vec4 vertexColor;
            in vec3 photonViewPos;
            in vec3 photonBaseColor;

            out vec4 fragColor;

            void main() {
                vec4 texel = texture(Sampler0, texCoord0);
                vec4 color = texel * vertexColor * ColorModulator;
                if (color.a < DiscardThreshold) {
                    discard;
                }
                color.rgb += texel.rgb * photonBaseColor * ColorModulator.rgb * photon_particle_light(photonViewPos);
                if (HDRMode == 0) {
                    color.rgb += HDR.rgb;
                } else {
                    color.rgb *= HDR.rgb;
                }
                fragColor = photon_soft_particle(linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor));
            }
            """;

    private LegacyLitShader() {
    }
}
