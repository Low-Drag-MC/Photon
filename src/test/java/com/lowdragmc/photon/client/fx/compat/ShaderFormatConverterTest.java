package com.lowdragmc.photon.client.fx.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The 1.21→26.1 custom-shader conversion must produce the reference contract (see circle.fsh). */
public class ShaderFormatConverterTest {

    private static final String LEGACY_CIRCLE = """
            #version 150

            #moj_import <fog.glsl>

            uniform vec4 ColorModulator;
            uniform float FogStart;
            uniform float FogEnd;
            uniform vec4 FogColor;
            uniform vec4 HDR;
            uniform float DiscardThreshold;
            uniform float Radius;

            in float vertexDistance;
            in vec2 texCoord0;
            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                float dist = distance(vec2(0.5, 0.5), texCoord0);
                vec4 color = vertexColor * ColorModulator;
                color.a = smoothstep(0., 1., 1. - dist / Radius) * color.a;
                if (color.a < DiscardThreshold) {
                    discard;
                }
                color.rgb += HDR.rgb * HDR.a;
                fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
            }
            """;

    @Test
    public void convertsTheLegacyContract() {
        var conversion = ShaderFormatConverter.convertFragment(LEGACY_CIRCLE, Map.of(
                "Radius", new float[]{0.3f},
                "HDR", new float[]{1f, 1f, 1f, 0f},
                "DiscardThreshold", new float[]{0.01f}));
        assertTrue(conversion.changed());
        var source = conversion.source();

        assertTrue(source.startsWith("#version 330"));
        assertTrue(source.contains("#moj_import <minecraft:fog.glsl>"));
        assertTrue(source.contains("#moj_import <minecraft:dynamictransforms.glsl>"));
        // engine uniforms gone
        assertFalse(source.contains("uniform vec4 ColorModulator"));
        assertFalse(source.contains("uniform float FogStart"));
        // the 1.21 plain uniforms move into the fixed PhotonCustomMaterial std140 block,
        // members SORTED BY NAME; usage sites stay untouched (block members are global scope)
        assertTrue(source.contains("layout(std140) uniform PhotonCustomMaterial {"));
        assertTrue(source.indexOf("vec4 HDR;") > source.indexOf("float DiscardThreshold;"));
        assertTrue(source.indexOf("float Radius;") > source.indexOf("vec4 HDR;"));
        assertFalse(source.contains("uniform float Radius"));
        assertFalse(source.contains("#define Radius"));
        assertTrue(source.contains("color.rgb += HDR.rgb * HDR.a;"));
        // fog rewritten to the 26.1 call + distances
        assertTrue(source.contains("in float sphericalVertexDistance;"));
        assertTrue(source.contains("in float cylindricalVertexDistance;"));
        assertTrue(source.contains("apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,"));
        assertFalse(source.contains("linear_fog"));
        assertEquals(List.of(), conversion.warnings());
    }

    @Test
    public void routesDynamicUniformsToEngineBlock() {
        var legacy = """
                #version 330 core
                uniform vec3 U_CameraPosition;
                uniform mat4 U_InverseViewMatrix;
                uniform float GameTime;
                uniform sampler2D SamplerSceneDepth;
                out vec4 fragColor;
                void main() { fragColor = vec4(U_CameraPosition, GameTime); }
                """;
        var conversion = ShaderFormatConverter.convertFragment(legacy, Map.of());
        assertTrue(conversion.changed());
        var source = conversion.source();
        assertTrue(source.contains("#moj_import <minecraft:globals.glsl>")); // GameTime home
        // the known U_* dynamic uniforms live in the PhotonEngine std140 block now
        assertTrue(source.contains("#moj_import <photon:engine.glsl>"));
        assertFalse(source.contains("uniform vec3 U_CameraPosition"));
        assertFalse(source.contains("frozen"));
        assertTrue(source.contains("uniform sampler2D SamplerSceneDepth;")); // samplers stay
        // SamplerScene* need no warning anymore — they bind the live scene capture at draw
        assertEquals(0, conversion.warnings().size());
    }

    @Test
    public void alreadyConvertedSourcesPassThrough() {
        var converted = ShaderFormatConverter.convertFragment(LEGACY_CIRCLE, Map.of()).source();
        var second = ShaderFormatConverter.convertFragment(converted, Map.of());
        assertFalse(second.changed());
        assertEquals(converted, second.source());
    }

    @Test
    public void parsesJsonDefaults() {
        var defaults = ShaderFormatConverter.parseJsonDefaults("""
                { "uniforms": [
                    { "name": "Radius", "type": "float", "count": 1, "values": [ 0.3 ] },
                    { "name": "HDR", "type": "float", "count": 4, "values": [ 1.0, 0.5, 0.25, 0.0 ] }
                ]}
                """);
        assertArrayEquals(new float[]{0.3f}, defaults.get("Radius"));
        assertArrayEquals(new float[]{1f, 0.5f, 0.25f, 0f}, defaults.get("HDR"));
    }
}
