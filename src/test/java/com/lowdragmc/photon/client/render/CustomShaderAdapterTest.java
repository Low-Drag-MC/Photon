package com.lowdragmc.photon.client.render;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom-shader adapter against the shapes real 26.1/26.2 content has: {@code tornado_body} reads an
 * attribute-tail channel through an explicit location, {@code shield2} declares a varying its vertex stage never
 * writes, {@code fancy_line} declares only the last two of its vertex stage's four outputs. 26.3 pairs the stages by
 * location, so all of them come out with every interface declaration numbered.
 */
class CustomShaderAdapterTest {

    private static final String PARTICLE_VERTEX = """
            #version 330 core
            in vec3 Position;
            in vec4 Color;
            layout(location = 9) in float T;

            out float sphericalVertexDistance;
            out float cylindricalVertexDistance;
            out vec2 texCoord0;
            out vec4 vertexColor;
            out float particleT;

            void main() {
                particleT = T;
                gl_Position = vec4(Position, 1.0);
            }
            """;

    private static final String PARTICLE_FRAGMENT = """
            #version 330
            in float sphericalVertexDistance;
            in float cylindricalVertexDistance;
            in vec2 texCoord0;
            in vec4 vertexColor;
            in float particleT;
            out vec4 fragColor;
            void main() { fragColor = vertexColor * particleT; }
            """;

    @Test
    void aTailInputThePipelineDoesNotProvideReadsOpenGlsDefault() {
        var result = CustomShaderAdapter.adapt(PARTICLE_VERTEX, PARTICLE_FRAGMENT, 5, Set.of(), Set.of());
        assertTrue(result.vertex().contains("float T = 0.0;"), result.vertex());
        assertFalse(result.vertex().contains("location = 9"));
        assertTrue(result.tailInputs().isEmpty());
        assertTrue(result.changed());
    }

    @Test
    void aProvidedTailInputIsFedByName() {
        var result = CustomShaderAdapter.adapt(PARTICLE_VERTEX, PARTICLE_FRAGMENT, 9, Set.of(9, 10), Set.of());
        // paired with the vertex format by name: any distinct location does, so it follows Position and Color
        assertTrue(result.vertex().contains("layout(location = 2) in float T;"), result.vertex());
        assertEquals(Map.of(9, "T"), result.tailInputs());
    }

    @Test
    void everyInputGetsADistinctLocation() {
        var result = CustomShaderAdapter.adapt(PARTICLE_VERTEX, PARTICLE_FRAGMENT, 5, Set.of(), Set.of());
        assertTrue(result.vertex().contains("layout(location = 0) in vec3 Position;"), result.vertex());
        assertTrue(result.vertex().contains("layout(location = 1) in vec4 Color;"), result.vertex());
    }

    @Test
    void inputsAfterTheParticleIncludeStartPastItsLocations() {
        var vertex = """
                #version 330
                #moj_import <photon:particle.glsl>
                layout(location = 9) in float T;
                out float particleT;
                void main() { particleT = T; gl_Position = vec4(0.0); }
                """;
        var result = CustomShaderAdapter.adapt(vertex, PARTICLE_FRAGMENT, 9, Set.of(9), Set.of());
        assertTrue(result.vertex().contains("#include <photon:particle.glsl>"), result.vertex());
        assertTrue(result.vertex().contains("layout(location = " + CustomShaderAdapter.PARTICLE_INPUT_LOCATIONS
                + ") in float T;"), result.vertex());
    }

    @Test
    void aVec4InputDefaultsToOpenGlsZeroZeroZeroOne() {
        var vertex = """
                #version 330
                layout(location = 12) in vec4 Extra;
                out vec4 color;
                void main() { color = Extra; gl_Position = vec4(0.0); }
                """;
        var fragment = """
                #version 330
                in vec4 color;
                out vec4 fragColor;
                void main() { fragColor = color; }
                """;
        var result = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of());
        assertTrue(result.vertex().contains("vec4 Extra = vec4(0.0, 0.0, 0.0, 1.0);"), result.vertex());
    }

    @Test
    void aVaryingNoVertexOutputWritesBecomesAConstant() {
        // shield2: the fragment reads ViewDir, the vertex stage writes ViewPos and ViewNormal only (in another order)
        var vertex = """
                #version 330
                in vec3 Position;
                out vec2 texCoord0;
                out vec3 ViewPos;
                out vec3 ViewNormal;
                void main() { gl_Position = vec4(Position, 1.0); }
                """;
        var fragment = """
                #version 330
                in vec2 texCoord0;
                in vec3 ViewDir;
                in vec3 ViewNormal;
                in vec3 ViewPos;
                out vec4 fragColor;
                void main() { fragColor = vec4(ViewDir + ViewNormal + ViewPos, 1.0); }
                """;
        var result = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of());
        assertTrue(result.fragment().contains("vec3 ViewDir = vec3(0.0);"), result.fragment());
        // paired by location, whatever the order the fragment declares them in
        assertTrue(result.fragment().contains("layout(location = 2) in vec3 ViewNormal;"), result.fragment());
        assertTrue(result.fragment().contains("layout(location = 1) in vec3 ViewPos;"), result.fragment());
        assertTrue(result.vertex().contains("layout(location = 1) out vec3 ViewPos;"), result.vertex());
    }

    @Test
    void theFragmentReadsTheOutputsOfItsNamesWhereverTheyAre() {
        // fancy_line: photon:particle writes four outputs, the fragment declares the last two
        var fragment = """
                #version 330
                in vec2 texCoord0;
                in vec4 vertexColor;
                in float particleT;
                out vec4 fragColor;
                void main() { fragColor = vertexColor; }
                """;
        var result = CustomShaderAdapter.adapt(PARTICLE_VERTEX, fragment, 5, Set.of(), Set.of());
        assertTrue(result.fragment().contains("layout(location = 2) in vec2 texCoord0;"), result.fragment());
        assertTrue(result.fragment().contains("layout(location = 3) in vec4 vertexColor;"), result.fragment());
        assertTrue(result.fragment().contains("layout(location = 4) in float particleT;"), result.fragment());
        assertTrue(result.fragment().contains("layout(location = 0) out vec4 fragColor;"), result.fragment());
        // an output nobody reads needs no declaration on the other side any more
        assertFalse(result.fragment().contains("sphericalVertexDistance"), result.fragment());
    }

    @Test
    void theColourOutputGoesThroughTheOitTail() {
        var fragment = """
                #version 330
                in vec4 vertexColor;
                out vec4 fragColor;
                vec4 tint(vec4 c) { return c * 0.5; }
                void main() {
                    if (vertexColor.a < 0.1) discard;
                    fragColor = tint(vertexColor); // fragColor written
                }
                """;
        var result = CustomShaderAdapter.adapt(PARTICLE_VERTEX, fragment, 5, Set.of(), Set.of());
        // the classic stage is not touched by it
        assertFalse(result.fragment().contains("photon:oit.glsl"), result.fragment());
        var twin = result.oitFragment();
        assertNotNull(twin);
        var lines = twin.split("\n");
        assertEquals("#version 330", lines[0]);
        assertEquals("#extension GL_ARB_separate_shader_objects : require", lines[1]);
        assertEquals("#include <photon:oit.glsl>", lines[2]);
        // renamed everywhere: vanilla's depth-bounds phase declares a fragColor output of its own
        assertFalse(twin.matches("(?s).*\\bfragColor\\b.*"), twin);
        assertTrue(twin.contains("vec4 photon_custom_fragColor;"), twin);
        assertTrue(twin.contains("void photon_custom_main() {"), twin);
        assertTrue(twin.contains("    photon_custom_fragColor = tint(vertexColor);"), twin);
        assertTrue(twin.endsWith("void main() {\n    photon_custom_main();\n"
                + "    photon_write(photon_custom_fragColor);\n}"), twin);
        assertNull(CustomShaderAdapter.adapt(PARTICLE_VERTEX, fragment, 5, Set.of(), Set.of(), false).oitFragment());
    }

    @Test
    void aMainBehindACommentIsRenamedOnce() {
        var fragment = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                #extension GL_EXT_samplerless_texture_functions : enable
                layout(location = 0) out vec4 fragColor;
                /* void main() */ void main() { fragColor = vec4(1.0); }
                """;
        var twin = CustomShaderAdapter.adapt(PARTICLE_VERTEX, fragment, 5, Set.of(), Set.of()).oitFragment();
        assertNotNull(twin);
        assertEquals(1, twin.split("void main\\(\\)", -1).length - 1, twin);
        assertTrue(twin.contains("void photon_custom_main()"), twin);
        // after every #extension at the top, blank line or not
        var lines = twin.split("\n");
        assertEquals("#extension GL_EXT_samplerless_texture_functions : enable", lines[3]);
        assertEquals("#include <photon:oit.glsl>", lines[4]);
    }

    @Test
    void shadersTheOitTailCannotWrapAreLeftOnTheClassicPath() {
        var depthWrite = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = vec4(1.0); gl_FragDepth = 0.5; }
                """;
        var twoOutputs = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location = 0) out vec4 fragColor;
                layout(location = 1) out vec4 extra;
                void main() { fragColor = vec4(1.0); extra = vec4(0.0); }
                """;
        var ownOit = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                #include <minecraft:oit.glsl>
                #ifndef OIT_ALPHA_ONLY
                layout(location = 0) out vec4 fragColor;
                #endif
                void main() {
                #ifdef OIT_ALPHA_ONLY
                    executeAlphaOnlyPhase(gl_FragCoord.z, 1.0);
                #else
                    fragColor = vec4(1.0);
                #endif
                }
                """;
        for (var fragment : new String[]{depthWrite, twoOutputs, ownOit}) {
            var result = CustomShaderAdapter.adapt(PARTICLE_VERTEX, fragment, 5, Set.of(), Set.of());
            assertNull(result.oitFragment(), result.oitFragment());
            assertFalse(result.fragment().contains("photon_custom_main"), result.fragment());
        }
    }

    @Test
    void theHeaderIsRaisedForLocationsOnVaryings() {
        var vertex = "#version 150\n#moj_import <fog.glsl>\nin vec3 Position;\nvoid main() {}\n";
        var fragment = "#version 150\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n";
        var result = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of());
        var lines = result.vertex().split("\n");
        assertEquals("#version 330", lines[0]);
        assertEquals("#extension GL_ARB_separate_shader_objects : require", lines[1]);
        assertEquals("#include <fog.glsl>", lines[2]);
    }

    @Test
    void anAdaptedPairIsLeftAlone() {
        var once = CustomShaderAdapter.adapt(PARTICLE_VERTEX, PARTICLE_FRAGMENT, 9, Set.of(9), Set.of());
        // the tail input now sits at location 2, which is not a tail location: adapt as a pipeline without a tail would
        var twice = CustomShaderAdapter.adapt(once.vertex(), once.fragment(), 9, Set.of(), Set.of());
        assertFalse(twice.changed(), String.join("; ", twice.notes()));
        assertEquals(once.vertex(), twice.vertex());
        assertEquals(once.fragment(), twice.fragment());
    }

    @Test
    void onlyTheActiveBranchCounts() {
        var vertex = """
                #version 330
                #if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE)
                #define PHOTON_INSTANCED
                #endif
                #ifdef PHOTON_INSTANCED
                out vec3 instanced;
                #else
                out vec2 plain;
                #endif
                out vec4 shared;
                void main() { gl_Position = vec4(0.0); }
                """;
        var fragment = """
                #version 330
                in vec4 shared;
                out vec4 fragColor;
                void main() { fragColor = shared; }
                """;
        var instanced = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of("PARTICLE_MODEL_INSTANCE"));
        assertTrue(instanced.vertex().contains("layout(location = 0) out vec3 instanced;"), instanced.vertex());
        assertTrue(instanced.vertex().contains("\nout vec2 plain;"), instanced.vertex());
        assertTrue(instanced.fragment().contains("layout(location = 1) in vec4 shared;"), instanced.fragment());
        var plain = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of());
        assertTrue(plain.vertex().contains("layout(location = 0) out vec2 plain;"), plain.vertex());
        assertTrue(plain.vertex().contains("\nout vec3 instanced;"), plain.vertex());
    }

    @Test
    void declarationsInsideFunctionsAndCommentsAreIgnored() {
        var vertex = """
                #version 330
                // out vec3 commented;
                /* out vec3 blockCommented; */
                out vec2 uv;
                vec3 helper(in vec3 x) {
                    return x;
                }
                void main() { gl_Position = vec4(helper(vec3(0.0)), 1.0); }
                """;
        var fragment = """
                #version 330
                in vec2 uv;
                out vec4 fragColor;
                void main() { fragColor = vec4(uv, 0.0, 1.0); }
                """;
        var result = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of());
        assertTrue(result.vertex().contains("// out vec3 commented;"), result.vertex());
        assertTrue(result.vertex().contains("/* out vec3 blockCommented; */"), result.vertex());
        assertTrue(result.vertex().contains("vec3 helper(in vec3 x) {"), result.vertex());
        assertTrue(result.vertex().contains("layout(location = 0) out vec2 uv;"), result.vertex());
    }

    @Test
    void lineContinuationsAreJoined() {
        var vertex = "#version 330\n#if defined(A) \\\n || defined(B)\nout vec2 uv;\n#endif\nvoid main() {}\n";
        var fragment = "#version 330\nin vec2 uv;\nout vec4 fragColor;\nvoid main() { fragColor = vec4(uv, 0.0, 1.0); }\n";
        var result = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of("B"));
        assertFalse(result.vertex().contains("\\"), result.vertex());
        assertTrue(result.vertex().contains("#if defined(A)   || defined(B)") || result.vertex().contains("|| defined(B)"));
        // and the joined condition still evaluates: uv is active under B, so it is numbered on both sides
        assertTrue(result.vertex().contains("layout(location = 0) out vec2 uv;"), result.vertex());
        assertTrue(result.fragment().contains("layout(location = 0) in vec2 uv;"), result.fragment());
    }

    @Test
    void theConditionEvaluatorHandlesTheUsualForms() {
        var defines = Map.of("A", "1", "N", "3", "EMPTY", "");
        assertEquals(1, CustomShaderAdapter.evaluate("defined(A) && !defined(B)", defines));
        assertEquals(0, CustomShaderAdapter.evaluate("defined B", defines));
        assertEquals(1, CustomShaderAdapter.evaluate("N >= 2 && (A || B)", defines));
        assertEquals(1, CustomShaderAdapter.evaluate("EMPTY", defines));
        assertEquals(0, CustomShaderAdapter.evaluate("UNKNOWN", defines));
    }
}
