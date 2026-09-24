package com.lowdragmc.photon.client.render;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom-shader adapter against the shapes real 26.1 content has: {@code tornado_body} reads an attribute-tail
 * channel through an explicit location, {@code shield2} declares a varying its vertex stage never writes,
 * {@code fancy_line} declares only the last two of its vertex stage's four outputs.
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
        assertTrue(result.vertex().contains("\nin float T;"), result.vertex());
        assertEquals(Map.of(9, "T"), result.tailInputs());
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
        assertTrue(result.fragment().contains("in vec3 ViewNormal;"));
        assertTrue(result.fragment().contains("in vec3 ViewPos;"));
    }

    @Test
    void outputsTheFragmentSkippedAreDeclaredSoVulkansCountingLinesUp() {
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
        var lines = result.fragment().split("\n");
        assertEquals("#version 330", lines[0]);
        assertTrue(result.fragment().contains("in float sphericalVertexDistance;"), result.fragment());
        assertTrue(result.fragment().contains("in float cylindricalVertexDistance;"), result.fragment());
    }

    @Test
    void aMatchingPairIsLeftAlone() {
        var vertex = PARTICLE_VERTEX.replace("layout(location = 9) in float T;", "in float T;");
        var result = CustomShaderAdapter.adapt(vertex, PARTICLE_FRAGMENT, 5, Set.of(), Set.of());
        assertFalse(result.changed(), String.join("; ", result.notes()));
        assertEquals(vertex.replace("\r\n", "\n"), result.vertex());
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
                void main() { gl_Position = vec4(0.0); }
                """;
        var fragment = """
                #version 330
                out vec4 fragColor;
                void main() { fragColor = vec4(1.0); }
                """;
        var instanced = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of("PARTICLE_MODEL_INSTANCE"));
        assertTrue(instanced.fragment().contains("in vec3 instanced;"), instanced.fragment());
        assertFalse(instanced.fragment().contains("plain"));
        var plain = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of());
        assertTrue(plain.fragment().contains("in vec2 plain;"), plain.fragment());
        assertFalse(plain.fragment().contains("instanced;"));
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
        assertFalse(result.changed(), String.join("; ", result.notes()));
    }

    @Test
    void lineContinuationsAreJoined() {
        var vertex = "#version 330\n#if defined(A) \\\n || defined(B)\nout vec2 uv;\n#endif\nvoid main() {}\n";
        var fragment = "#version 330\nin vec2 uv;\nout vec4 fragColor;\nvoid main() { fragColor = vec4(uv, 0.0, 1.0); }\n";
        var result = CustomShaderAdapter.adapt(vertex, fragment, 5, Set.of(), Set.of("B"));
        assertFalse(result.vertex().contains("\\"), result.vertex());
        assertTrue(result.vertex().contains("#if defined(A)   || defined(B)") || result.vertex().contains("|| defined(B)"));
        // and the joined condition still evaluates: uv is active under B, so the fragment needs nothing added
        assertFalse(result.fragment().contains("in vec2 uv;\nin vec2 uv;"));
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
