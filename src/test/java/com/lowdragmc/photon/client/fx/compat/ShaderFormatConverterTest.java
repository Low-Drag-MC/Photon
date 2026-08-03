package com.lowdragmc.photon.client.fx.compat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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

    /**
     * The shipped 1.21 crystal.vsh, verbatim. Every assertion below pins a delta that really broke a
     * shipped shader: a loose {@code ModelViewMat}/{@code ProjMat} is a LINK error in 26.1 ("already
     * declared in the interface block DynamicTransforms"), and a failed pipeline makes
     * {@code getRenderType} return null — so the material renders NOTHING, with no error at the draw.
     */
    private static final String LEGACY_VERTEX = """
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

            void main() {
                ParticleData data = getParticleData();

                vec4  viewPos4  = ModelViewMat * vec4(data.Position, 1.0);

                vertexDistance  = fog_distance(viewPos4.xyz, FogShape);
                texCoord0 = data.UV;
                vertexColor = data.Color * texelFetch(Sampler2, data.LightUV / 16, 0);

                gl_Position     = ProjMat * viewPos4;
            }
            """;

    @Test
    public void vertexDropsLooseTransformUniformsForTheirBlocks() {
        var source = ShaderFormatConverter.convertVertex(LEGACY_VERTEX, Map.of()).source();
        assertFalse(source.contains("uniform mat4 ModelViewMat;"));
        assertFalse(source.contains("uniform mat4 ProjMat;"));
        assertFalse(source.contains("uniform int FogShape;")); // no such uniform in 26.1
        assertTrue(source.contains("#moj_import <minecraft:dynamictransforms.glsl>"));
        assertTrue(source.contains("#moj_import <minecraft:projection.glsl>"));
        // the uses stay verbatim — the std140 blocks declare the very same names
        assertTrue(source.contains("ModelViewMat * vec4(data.Position, 1.0)"));
        assertTrue(source.contains("gl_Position     = ProjMat * viewPos4;"));
    }

    @Test
    public void vertexSplitsFogDistanceAndLightmapFetch() {
        var source = ShaderFormatConverter.convertVertex(LEGACY_VERTEX, Map.of()).source();
        // one varying out in 1.21, two in 26.1 — and the fragment stage reads both
        assertTrue(source.contains("out float sphericalVertexDistance;"));
        assertTrue(source.contains("out float cylindricalVertexDistance;"));
        assertFalse(source.contains("out float vertexDistance;"));
        assertTrue(source.contains("sphericalVertexDistance = fog_spherical_distance(viewPos4.xyz);"));
        assertTrue(source.contains("cylindricalVertexDistance = fog_cylindrical_distance(viewPos4.xyz);"));
        assertFalse(source.contains("fog_distance("));
        assertTrue(source.contains("sample_lightmap(Sampler2, data.LightUV)"));
        assertFalse(source.contains("texelFetch(Sampler2"));
        assertTrue(source.contains("#moj_import <minecraft:sample_lightmap.glsl>"));
        assertEquals(List.of(), ShaderFormatConverter.convertVertex(LEGACY_VERTEX, Map.of()).warnings());
    }

    @Test
    public void customBlockIsTheJsonSetNotTheStageSubset() {
        // shield2's real shape: the vertex stage declares one custom uniform, the JSON lists nine (plus
        // engine ones). Emitting only the subset would give this stage different std140 offsets than the
        // runtime packs from the JSON, and a block the fragment stage disagrees with — which GLSL rejects.
        var legacy = """
                #version 330 core
                uniform mat4 ModelViewMat;
                uniform float GameTime;
                uniform float shieldPulse;
                void main() { gl_Position = ModelViewMat * vec4(shieldPulse, 0.0, 0.0, 1.0); }
                """;
        var defaults = new LinkedHashMap<String, float[]>();
        for (var engine : List.of("ModelViewMat", "ProjMat", "FogShape", "GameTime")) {
            defaults.put(engine, new float[1]);
        }
        defaults.put("ScreenSize", new float[2]);
        defaults.put("U_InverseProjectionMatrix", new float[16]);
        defaults.put("power", new float[1]);
        defaults.put("Distance", new float[1]);
        defaults.put("HDRColor", new float[4]);
        defaults.put("alphaMultiplier", new float[1]);
        defaults.put("windSpeed", new float[1]);
        defaults.put("windIntensity", new float[1]);
        defaults.put("spiralDensity", new float[1]);
        defaults.put("useTexture", new float[1]);
        defaults.put("shieldPulse", new float[1]);

        var block = blockOf(ShaderFormatConverter.convertVertex(legacy, defaults).source());
        assertEquals("""
                layout(std140) uniform PhotonCustomMaterial {
                    float Distance;
                    vec4 HDRColor;
                    float alphaMultiplier;
                    float power;
                    float shieldPulse;
                    float spiralDensity;
                    float useTexture;
                    float windIntensity;
                    float windSpeed;
                """, block);
        // engine-owned names never become members — they arrive through their own std140 blocks
        assertFalse(block.contains("ModelViewMat"));
        assertFalse(block.contains("GameTime"));
        assertFalse(block.contains("ScreenSize"));
        assertFalse(block.contains("U_InverseProjectionMatrix"));
    }

    @Test
    public void bothStagesEmitTheSameCustomBlock() {
        var defaults = new LinkedHashMap<String, float[]>();
        defaults.put("power", new float[1]);
        defaults.put("HDRColor", new float[4]);
        defaults.put("alphaMultiplier", new float[1]);
        // each stage uses ONE member and a different one — the emitted block must still be the full set
        var vertex = ShaderFormatConverter.convertVertex("""
                #version 330 core
                uniform mat4 ModelViewMat;
                uniform float alphaMultiplier;
                void main() { gl_Position = ModelViewMat * vec4(alphaMultiplier); }
                """, defaults).source();
        var fragment = ShaderFormatConverter.convertFragment("""
                #version 330 core
                uniform float power;
                out vec4 fragColor;
                void main() { fragColor = vec4(power); }
                """, defaults).source();
        assertEquals(blockOf(vertex), blockOf(fragment),
                "GLSL rejects a uniform block whose members differ between stages");
        assertTrue(blockOf(vertex).contains("float power;"), "the full JSON set, not this stage's subset");
    }

    @Test
    public void stageThatUsesNoCustomUniformDeclaresNoBlock() {
        // a block is only required to agree where BOTH stages declare one — a vertex stage that just
        // transforms positions should not carry a block it never reads
        var defaults = new LinkedHashMap<String, float[]>();
        defaults.put("power", new float[1]);
        var vertex = ShaderFormatConverter.convertVertex("""
                #version 330 core
                uniform mat4 ModelViewMat;
                void main() { gl_Position = ModelViewMat * vec4(0.0); }
                """, defaults).source();
        assertFalse(vertex.contains("layout(std140) uniform PhotonCustomMaterial {"));
        assertEquals("", blockOf(vertex));
    }

    @Test
    public void alreadyConvertedVertexPassesThrough() {
        var converted = ShaderFormatConverter.convertVertex(LEGACY_VERTEX, Map.of()).source();
        var second = ShaderFormatConverter.convertVertex(converted, Map.of());
        assertFalse(second.changed());
        assertEquals(converted, second.source());
    }

    private static String blockOf(String source) {
        var start = source.indexOf("layout(std140) uniform PhotonCustomMaterial {");
        return start < 0 ? "" : source.substring(start, source.indexOf("};", start));
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
