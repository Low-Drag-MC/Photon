package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * glTF fixtures are built here rather than checked in as binaries: the interesting cases are one
 * attribute or one node transform apart, and a hand-written JSON + base64 buffer keeps that difference
 * readable in the diff.
 */
class GltfMeshParserTest {

    /** One triangle: positions, then optionally normals / uvs / tangents, all tightly packed floats. */
    private static String buffer(float... values) {
        var bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) bytes.putFloat(v);
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes.array());
    }

    private static float vertex(PhotonMesh mesh, int quad, int corner, int component) {
        return mesh.vertices()[PhotonMesh.vertexOffset(quad, corner) + component];
    }

    /** Every fixture here is a single triangle, so the quad index is always 0. */
    private static float tangent(PhotonMesh mesh, int corner, int component) {
        return mesh.tangents()[PhotonMesh.tangentOffset(0, corner) + component];
    }

    /**
     * A triangle in the XY plane facing +Z with u along +X, v along +Y — the same geometry
     * {@code quad.obj} uses, so the expected tangent is +X.
     *
     * @param extraAttributes e.g. {@code ,"TANGENT":3}
     * @param extraAccessors  the matching accessor objects
     * @param data            the whole buffer's floats
     */
    private static String gltf(String extraAttributes, String extraAccessors, String nodeExtra, float... data) {
        return """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0%s}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2%s}}]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 72, "componentType": 5126, "count": 3, "type": "VEC2"}%s
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": %d}],
                  "buffers": [{"byteLength": %d, "uri": "%s"}]
                }
                """.formatted(nodeExtra, extraAttributes, extraAccessors,
                data.length * 4, data.length * 4, buffer(data));
    }

    /** positions (3x vec3), normals (3x vec3), uvs (3x vec2) = 24 floats. */
    private static final float[] BASE = {
            0, 0, 0, 1, 0, 0, 0, 1, 0,      // positions
            0, 0, 1, 0, 0, 1, 0, 0, 1,      // normals (+Z)
            0, 0, 1, 0, 0, 1,               // uvs
    };

    private static PhotonMesh parse(String json) throws IOException {
        return GltfMeshParser.parse(json.getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    void readsPositionsNormalsAndUvs() throws IOException {
        var mesh = parse(gltf("", "", "", BASE));
        assertEquals(1, mesh.quadCount());
        assertTrue(mesh.isTriangle(0), "a glTF triangle is stored as a degenerate quad");
        assertEquals(1f, vertex(mesh, 0, 1, 0), 1e-5f, "second corner x");
        assertEquals(1f, vertex(mesh, 0, 1, 3), 1e-5f, "second corner u");
        assertEquals(1f, vertex(mesh, 0, 0, 7), 1e-5f, "normal z");
    }

    /** No TANGENT in the file: fall back to generating from the UVs, exactly like OBJ/JSON. */
    @Test
    void generatesTangentsWhenTheFileHasNone() throws IOException {
        var mesh = parse(gltf("", "", "", BASE));
        assertEquals(1f, tangent(mesh, 0, 0), 1e-5f, "generated tangent should be +X");
        assertEquals(1f, tangent(mesh, 0, 3), "generated handedness");
    }

    /**
     * TANGENT present: use it verbatim. The fixture deliberately supplies -X with w = -1, which UV-based
     * generation would never produce, so the assertion can only pass if the file's data was used.
     */
    @Test
    void usesTheFilesTangentsWhenPresent() throws IOException {
        float[] data = new float[BASE.length + 12];
        System.arraycopy(BASE, 0, data, 0, BASE.length);
        for (int i = 0; i < 3; i++) {
            data[BASE.length + i * 4] = -1f;      // x
            data[BASE.length + i * 4 + 3] = -1f;  // w (handedness)
        }
        var mesh = parse(gltf(", \"TANGENT\": 3",
                ", {\"bufferView\": 0, \"byteOffset\": 96, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC4\"}",
                "", data));
        for (int corner = 0; corner < 4; corner++) {
            assertEquals(-1f, tangent(mesh, corner, 0), 1e-5f, "corner " + corner + " tangent.x");
            assertEquals(-1f, tangent(mesh, corner, 3), "corner " + corner + " handedness");
        }
    }

    /** A node's TRS must be baked into the vertices, or every model lands at the origin unscaled. */
    @Test
    void appliesNodeTransforms() throws IOException {
        var mesh = parse(gltf("", "", ", \"translation\": [10, 0, 0], \"scale\": [2, 2, 2]", BASE));
        assertEquals(10f, vertex(mesh, 0, 0, 0), 1e-5f, "translated origin");
        assertEquals(12f, vertex(mesh, 0, 1, 0), 1e-5f, "scaled then translated");
        assertEquals(1f, vertex(mesh, 0, 0, 7), 1e-5f, "uniform scale leaves the normal alone");
    }

    /** A mirroring transform flips which way the bitangent points, so w has to flip with it. */
    @Test
    void mirroringFlipsSuppliedHandedness() throws IOException {
        float[] data = new float[BASE.length + 12];
        System.arraycopy(BASE, 0, data, 0, BASE.length);
        for (int i = 0; i < 3; i++) {
            data[BASE.length + i * 4] = 1f;
            data[BASE.length + i * 4 + 3] = 1f;
        }
        var mesh = parse(gltf(", \"TANGENT\": 3",
                ", {\"bufferView\": 0, \"byteOffset\": 96, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC4\"}",
                ", \"scale\": [-1, 1, 1]", data));
        assertEquals(-1f, tangent(mesh, 0, 3), "negative-determinant scale must flip handedness");
    }

    @Test
    void readsIndexedPrimitives() throws IOException {
        // 3 positions + 3 normals + 3 uvs, then 3 unsigned-short indices in reverse order
        var bytes = ByteBuffer.allocate(BASE.length * 4 + 6).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : BASE) bytes.putFloat(v);
        bytes.putShort((short) 2).putShort((short) 1).putShort((short) 0);
        var json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"indices": 3,
                     "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}}]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 72, "componentType": 5126, "count": 3, "type": "VEC2"},
                    {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
                  ],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 96},
                    {"buffer": 0, "byteOffset": 96, "byteLength": 6}
                  ],
                  "buffers": [{"byteLength": 102, "uri": "%s"}]
                }
                """.formatted("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bytes.array()));
        var mesh = parse(json);
        assertEquals(1, mesh.quadCount());
        // indices reverse the winding, so corner 0 is the vertex that was last
        assertEquals(0f, vertex(mesh, 0, 0, 0), 1e-5f);
        assertEquals(1f, vertex(mesh, 0, 0, 1), 1e-5f);
    }

    @Test
    void readsGlbContainer() throws IOException {
        var json = gltf("", "", "", BASE).getBytes(StandardCharsets.UTF_8);
        int padded = (json.length + 3) & ~3;
        var glb = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(12 + 8 + padded);
        glb.putInt(padded).putInt(0x4E4F534A).put(json);
        while (glb.position() < glb.capacity()) glb.put((byte) ' ');
        var mesh = GltfMeshParser.parse(glb.array(), false);
        assertEquals(1, mesh.quadCount());
        assertEquals(1f, vertex(mesh, 0, 1, 0), 1e-5f);
    }

    /** An external .bin is the one thing we deliberately refuse; the message has to say why. */
    @Test
    void rejectsExternalBuffersWithAnActionableMessage() {
        var json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
                  "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
                  "buffers": [{"byteLength": 36, "uri": "rocket.bin"}]
                }
                """;
        var e = assertThrows(IOException.class, () -> parse(json));
        assertTrue(e.getMessage().contains("rocket.bin"), "names the file: " + e.getMessage());
        assertTrue(e.getMessage().contains(".glb"), "suggests the fix: " + e.getMessage());
    }

    @Test
    void flipVMirrorsUVs() throws IOException {
        var mesh = GltfMeshParser.parse(gltf("", "", "", BASE).getBytes(StandardCharsets.UTF_8), true);
        // the third corner's v is 1 in the fixture
        assertEquals(0f, vertex(mesh, 0, 2, 4), 1e-5f);
    }

    /**
     * The other half of the transform contract: a node can give a full 4x4 instead of TRS, and glTF
     * stores it <b>column-major</b> — so the translation lives in elements 12..14. Reading it row-major
     * would put the model somewhere else entirely, which TRS-only coverage never notices.
     */
    @Test
    void appliesColumnMajorMatrixNodeTransforms() throws IOException {
        // column-major: [ 2 0 0 0 | 0 2 0 0 | 0 0 2 0 | 5 6 7 1 ]  =  scale 2 then translate (5,6,7)
        var matrix = ", \"matrix\": [2,0,0,0, 0,2,0,0, 0,0,2,0, 5,6,7,1]";
        var mesh = parse(gltf("", "", matrix, BASE));
        assertEquals(5f, vertex(mesh, 0, 0, 0), 1e-5f, "translation column read as x");
        assertEquals(6f, vertex(mesh, 0, 0, 1), 1e-5f, "translation column read as y");
        assertEquals(7f, vertex(mesh, 0, 0, 2), 1e-5f, "translation column read as z");
        assertEquals(7f, vertex(mesh, 0, 1, 0), 1e-5f, "second corner: 1 scaled by 2, then +5");
    }

    /**
     * Interleaved vertex data — one bufferView with a byteStride, every attribute at its own offset
     * inside each vertex. Plenty of exporters write this; ignoring byteStride reads pure garbage.
     */
    @Test
    void readsInterleavedBuffersWithByteStride() throws IOException {
        // 3 vertices x (pos vec3, normal vec3, uv vec2) = 32 bytes each
        var bytes = ByteBuffer.allocate(3 * 32).order(ByteOrder.LITTLE_ENDIAN);
        float[][] vertices = {
                {0, 0, 0, 0, 0, 1, 0, 0},
                {1, 0, 0, 0, 0, 1, 1, 0},
                {0, 1, 0, 0, 0, 1, 0, 1},
        };
        for (var v : vertices) for (float f : v) bytes.putFloat(f);
        var json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes":
                    {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}}]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 24, "componentType": 5126, "count": 3, "type": "VEC2"}
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 96, "byteStride": 32}],
                  "buffers": [{"byteLength": 96, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(Base64.getEncoder().encodeToString(bytes.array()));
        var mesh = parse(json);
        assertEquals(1, mesh.quadCount());
        assertEquals(1f, vertex(mesh, 0, 1, 0), 1e-5f, "second vertex x");
        assertEquals(1f, vertex(mesh, 0, 2, 1), 1e-5f, "third vertex y");
        assertEquals(1f, vertex(mesh, 0, 0, 7), 1e-5f, "normal z");
        assertEquals(1f, vertex(mesh, 0, 1, 3), 1e-5f, "second vertex u");
    }

    /** KHR_mesh_quantization-style attributes: normalized integers rather than floats. */
    @Test
    void decodesNormalizedIntegerUVs() throws IOException {
        var bytes = ByteBuffer.allocate(BASE.length * 4 - 24 + 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 18; i++) bytes.putFloat(BASE[i]);      // positions + normals stay float
        short[] uv = {0, 0, (short) 65535, 0, 0, (short) 65535};   // (0,0) (1,0) (0,1) as unorm16
        for (short s : uv) bytes.putShort(s);
        var json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes":
                    {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}}]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 72, "componentType": 5123, "normalized": true,
                     "count": 3, "type": "VEC2"}
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 84}],
                  "buffers": [{"byteLength": 84, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(Base64.getEncoder().encodeToString(bytes.array()));
        var mesh = parse(json);
        assertEquals(0f, vertex(mesh, 0, 0, 3), 1e-4f, "unorm16 0 -> 0.0");
        assertEquals(1f, vertex(mesh, 0, 1, 3), 1e-4f, "unorm16 65535 -> 1.0");
        assertEquals(1f, vertex(mesh, 0, 2, 4), 1e-4f, "unorm16 65535 -> 1.0 (v)");
    }

    /** A real export is many primitives under many nodes; every one has to land, each in its own space. */
    @Test
    void readsEveryPrimitiveOfEveryNode() throws IOException {
        var json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0, 1]}],
                  "nodes": [
                    {"mesh": 0, "children": [2]},
                    {"mesh": 0, "translation": [100, 0, 0]},
                    {"mesh": 0, "translation": [0, 100, 0]}
                  ],
                  "meshes": [{"primitives": [
                    {"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}},
                    {"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}}
                  ]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 72, "componentType": 5126, "count": 3, "type": "VEC2"}
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 96}],
                  "buffers": [{"byteLength": 96, "uri": "%s"}]
                }
                """.formatted(buffer(BASE));
        var mesh = parse(json);
        // 3 nodes (root, its child, the second root) x 2 primitives
        assertEquals(6, mesh.quadCount(), "every node x every primitive");
        float maxX = 0, maxY = 0;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            maxX = Math.max(maxX, vertex(mesh, quad, 0, 0));
            maxY = Math.max(maxY, vertex(mesh, quad, 0, 1));
        }
        assertEquals(100f, maxX, 1e-4f, "the sibling node's translation was applied");
        assertEquals(100f, maxY, 1e-4f, "the CHILD node's translation was applied");
    }

    /**
     * glTF 3.7.2.1: a node whose global transform has a negative determinant must have its winding
     * reversed. Without it a mirrored instance is back-facing — culled away by a cull-enabled material,
     * which looks like the model failed to load rather than like a winding bug.
     */
    @Test
    void reversesWindingForMirroredNodes() throws IOException {
        var mesh = parse(gltf("", "", ", \"scale\": [-1, 1, 1]", BASE));
        // source order is (0,0,0) (1,0,0) (0,1,0); mirrored in x and with corners 1/2 swapped that is
        // (0,0,0) (0,1,0) (-1,0,0)
        assertEquals(0f, vertex(mesh, 0, 1, 0), 1e-5f, "corner 1 x");
        assertEquals(1f, vertex(mesh, 0, 1, 1), 1e-5f, "corner 1 y — the swapped-in third vertex");
        assertEquals(-1f, vertex(mesh, 0, 2, 0), 1e-5f, "corner 2 x — the swapped-in second vertex");
    }

    /** An unmirrored node must keep its winding, or the fix above would break every normal model. */
    @Test
    void keepsWindingForUnmirroredNodes() throws IOException {
        var mesh = parse(gltf("", "", ", \"scale\": [2, 2, 2]", BASE));
        assertEquals(2f, vertex(mesh, 0, 1, 0), 1e-5f, "corner 1 is still the second vertex");
        assertEquals(2f, vertex(mesh, 0, 2, 1), 1e-5f, "corner 2 is still the third vertex");
    }

    /**
     * A corrupt chunk length must not reach {@code new byte[chunkLength]}: that throws OutOfMemoryError,
     * an Error, which {@code GltfModelSource}'s catch(Exception) would not contain — the client would die
     * instead of logging a failed load.
     */
    @Test
    void rejectsAnOverlongGlbChunkWithoutAllocating() {
        var glb = ByteBuffer.allocate(12 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(glb.capacity());
        glb.putInt(Integer.MAX_VALUE - 16).putInt(0x4E4F534A).putInt(0); // absurd JSON chunk length
        var e = assertThrows(IOException.class, () -> GltfMeshParser.parse(glb.array(), false));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
    }

    /** A singular node transform (a zero scale axis — how exporters hide a node) must not emit NaN. */
    @Test
    void aSingularNodeTransformDoesNotProduceNaN() throws IOException {
        var mesh = parse(gltf("", "", ", \"scale\": [0, 1, 1]", BASE));
        var vertices = mesh.vertices();
        for (int corner = 0; corner < 4; corner++) {
            int off = PhotonMesh.vertexOffset(0, corner);
            for (int k = 0; k < PhotonMesh.FLOATS_PER_VERTEX; k++) {
                assertTrue(Float.isFinite(vertices[off + k]),
                        "vertex float " + k + " of corner " + corner + " is not finite");
            }
        }
        for (float t : mesh.tangents()) {
            assertTrue(Float.isFinite(t), "a generated tangent is not finite");
        }
    }

    /**
     * The other half of the NaN story: the transform is fine but the file's own NORMAL data is garbage.
     * The singular-transform guard cannot help here, so this is what pins the per-corner check.
     */
    @Test
    void nonFiniteNormalsInTheFileAreReplaced() throws IOException {
        float[] data = BASE.clone();
        for (int i = 9; i < 18; i++) data[i] = i % 2 == 0 ? Float.NaN : Float.POSITIVE_INFINITY;
        var mesh = parse(gltf("", "", "", data));
        var vertices = mesh.vertices();
        for (int corner = 0; corner < 4; corner++) {
            int off = PhotonMesh.vertexOffset(0, corner);
            for (int k = 5; k < 8; k++) {
                assertTrue(Float.isFinite(vertices[off + k]),
                        "normal component " + k + " of corner " + corner + " is not finite");
            }
        }
    }

    /** A `scene` index past the end used to yield an invisible model with nothing in the log. */
    @Test
    void anOutOfRangeSceneIndexStillRenders() throws IOException {
        var mesh = parse(gltf("", "", "", BASE).replace("\"scene\": 0", "\"scene\": 7"));
        assertEquals(1, mesh.quadCount(), "fell back to walking the node list");
    }

    /** Taking every node as a root would emit a child twice — once as a root, once through its parent. */
    @Test
    void theNoSceneFallbackDoesNotVisitChildrenTwice() throws IOException {
        var json = gltf("", "", "", BASE)
                .replace("\"scene\": 0,", "")
                .replace("\"scenes\": [{\"nodes\": [0]}],", "")
                .replace("\"nodes\": [{\"mesh\": 0}]",
                        "\"nodes\": [{\"mesh\": 0, \"children\": [1]}, {\"mesh\": 0}]");
        var mesh = parse(json);
        assertEquals(2, mesh.quadCount(), "one triangle per node, the child reached only via its parent");
    }

    /** A short `matrix` array would zero-pad into a singular transform that eats the geometry. */
    @Test
    void aShortMatrixArrayFallsBackToIdentity() throws IOException {
        var mesh = parse(gltf("", "", ", \"matrix\": [1, 0, 0, 0]", BASE));
        assertEquals(1, mesh.quadCount());
        assertEquals(1f, vertex(mesh, 0, 1, 0), 1e-5f, "geometry survived at its authored position");
    }

    @Test
    void ignoresNonTriangleModes() throws IOException {
        var json = gltf("", "", "", BASE).replace("\"attributes\"", "\"mode\": 1, \"attributes\"");
        assertTrue(parse(json).isEmpty(), "LINES primitives are not geometry Photon can render");
    }
}
