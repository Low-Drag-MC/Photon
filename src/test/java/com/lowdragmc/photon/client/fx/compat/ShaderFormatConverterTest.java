package com.lowdragmc.photon.client.fx.compat;

import com.lowdragmc.photon.uitest.LegacyLitShader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * A 1.21 v2.2.8 shader that imports the lit and soft-particle includes — 1.21's own hdr_particle, which users could
     * load as a custom shader. Both includes ship in 26.1, the lights' json entries are the pipeline's and stay out of
     * the block (as CustomShaderMaterial leaves them out of its layout), and the fade gets the parameters it now takes.
     */
    @Test
    public void keepsALitSoftParticleShaderWorking() {
        var json = ShaderFormatConverter.parseJsonDefaults(LegacyLitShader.JSON);
        var conversion = ShaderFormatConverter.convertFragment(LegacyLitShader.FRAGMENT, json,
                ShaderFormatConverter.parseJsonInts(LegacyLitShader.JSON));
        assertTrue(conversion.changed());
        assertEquals(List.of(), conversion.warnings(), "both includes ship in 26.1");
        var source = conversion.source();
        assertTrue(source.contains("#moj_import <photon:lit_particle.glsl>"));
        assertTrue(source.contains("#moj_import <photon:soft_particle.glsl>"));
        var block = source.substring(source.indexOf("uniform PhotonCustomMaterial {"), source.indexOf("};"));
        for (var own : List.of("DiscardThreshold", "HDR", "HDRMode", "SoftParticleParams")) {
            assertTrue(block.contains(" " + own + ";"), own + " is the shader's own value");
        }
        for (var light : List.of("LitParticleParams", "PhotonClusterInfo", "PhotonClusterView", "PhotonVisibilityInfo",
                "PhotonViewInverse")) {
            assertFalse(block.contains(light), light + " is the pipeline's in 26.1");
        }
        assertTrue(source.contains("photon_soft_particle(apply_fog(color, sphericalVertexDistance,"));
        assertTrue(source.contains("FogColor), SoftParticleParams)"), "the fade takes the parameters the shader listed");
        assertTrue(source.contains("photon_particle_light(photonViewPos)"), "the lights are called as in 1.21");
    }

    /** One that never listed the parameters read them as 0 in 1.21 — no fade — and still does. */
    @Test
    public void anUnlistedFadeStaysOff() {
        var legacy = """
                #version 150
                #moj_import <photon:soft_particle.glsl>
                uniform float Alpha;
                out vec4 fragColor;
                void main() {
                    fragColor = photon_soft_particle(vec4(1.0, 1.0, 1.0, Alpha));
                }
                """;
        var source = ShaderFormatConverter.convertFragment(legacy, Map.of("Alpha", new float[]{1f})).source();
        assertTrue(source.contains("photon_soft_particle(vec4(1.0, 1.0, 1.0, Alpha), vec4(0.0))"), source);
    }

    /** A shader with a fade of its own, not the include's, keeps it as written. */
    @Test
    public void anInlinedFadeIsLeftAlone() {
        var legacy = """
                #version 150
                uniform float Alpha;
                out vec4 fragColor;
                vec4 photon_soft_particle(vec4 c) { return c; }
                void main() {
                    fragColor = photon_soft_particle(vec4(1.0, 1.0, 1.0, Alpha));
                }
                """;
        var source = ShaderFormatConverter.convertFragment(legacy, Map.of("Alpha", new float[]{1f})).source();
        assertTrue(source.contains("vec4 photon_soft_particle(vec4 c) {"), source);
        assertTrue(source.contains("photon_soft_particle(vec4(1.0, 1.0, 1.0, Alpha));"), source);
    }

    /**
     * The engine block comes before the shader's own includes: soft_particle.glsl imports it under PHOTON_SOFT, and a
     * file is pasted only where it is first imported — so a shader without the fade would lose it.
     */
    @Test
    public void theEngineBlockPrecedesTheIncludes() {
        var legacy = """
                #version 150
                #moj_import <photon:soft_particle.glsl>
                uniform vec4 U_ViewPort;
                uniform float Alpha;
                out vec4 fragColor;
                void main() {
                    fragColor = vec4(gl_FragCoord.xy / U_ViewPort.zw, 0.0, Alpha);
                }
                """;
        var source = ShaderFormatConverter.convertFragment(legacy, Map.of("Alpha", new float[]{1f})).source();
        assertTrue(source.indexOf("#moj_import <photon:engine.glsl>") >= 0, source);
        assertTrue(source.indexOf("#moj_import <photon:engine.glsl>")
                < source.indexOf("#moj_import <photon:soft_particle.glsl>"), source);
    }

    /** A uniform the json does not list has no value in 26.1: it stays out of the block both stages share. */
    @Test
    public void aUniformTheJsonDoesNotListIsFrozen() {
        var legacy = """
                #version 150
                uniform float Alpha;
                uniform vec4 Extra;
                out vec4 fragColor;
                void main() {
                    fragColor = Extra * Alpha;
                }
                """;
        var conversion = ShaderFormatConverter.convertFragment(legacy, Map.of("Alpha", new float[]{1f}));
        var source = conversion.source();
        var block = source.substring(source.indexOf("uniform PhotonCustomMaterial {"), source.indexOf("};"));
        assertTrue(block.contains("float Alpha;"), source);
        assertFalse(block.contains("Extra"), source);
        assertTrue(source.contains("const vec4 Extra = vec4(0);"), source);
        assertEquals(1, conversion.warnings().size(), conversion.warnings().toString());
    }

    /**
     * A json {@code int} is an {@code int} in every stage's block, also where the stage never declared it: both stages
     * must declare the same block, and the material packs it as an int.
     */
    @Test
    public void aJsonIntStaysAnIntInTheOtherStage() {
        var json = """
                {"uniforms": [{"name": "Mode", "type": "int", "count": 1, "values": [0]},
                              {"name": "Scale", "type": "float", "count": 1, "values": [1.0]}]}
                """;
        var vertex = """
                #version 150
                uniform float Scale;
                in vec3 Position;
                void main() {
                    gl_Position = vec4(Position * Scale, 1.0);
                }
                """;
        var source = ShaderFormatConverter.convertVertex(vertex, ShaderFormatConverter.parseJsonDefaults(json),
                ShaderFormatConverter.parseJsonInts(json)).source();
        assertTrue(source.contains("    int Mode;"), source);
        assertTrue(source.contains("    float Scale;"), source);
    }

    /** Photon's own passes' includes, which a custom shader's pipeline does not declare the blocks of. */
    private static final Set<String> INTERNAL_INCLUDES = Set.of("kila_lib.glsl", "dynamic_light_frame.glsl",
            "dynamic_light_depth.glsl", "dynamic_light_voxel.glsl");

    private static Path includeFolder() {
        var relative = Path.of("src", "main", "resources", "assets", "photon", "shaders", "include");
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(relative))) return dir.resolve(relative);
        }
        throw new IllegalStateException("photon shader includes not found from " + Path.of("").toAbsolutePath());
    }

    /** What the converter lets a shader import is what ships, and every include that ships is either. */
    @Test
    public void everyAllowedIncludeShips() throws IOException {
        try (var files = Files.list(includeFolder())) {
            var shipped = files.map(file -> file.getFileName().toString()).toList();
            for (var include : ShaderFormatConverter.SHIPPED_PHOTON_INCLUDES) {
                assertTrue(shipped.contains(include), include + " does not ship");
            }
            for (var include : shipped) {
                assertTrue(ShaderFormatConverter.SHIPPED_PHOTON_INCLUDES.contains(include)
                        || INTERNAL_INCLUDES.contains(include), include + " is neither allowed nor internal");
            }
        }
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
