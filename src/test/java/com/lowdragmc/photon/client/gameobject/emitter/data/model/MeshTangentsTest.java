package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deriving tangents from positions + UVs — the path OBJ and JSON models always take, and the fallback
 * glTF takes when a primitive ships without TANGENT (see {@code GltfMeshParserTest} for the other half).
 */
class MeshTangentsTest {

    private static PhotonMesh load(String name, boolean flipV) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                MeshTangentsTest.class.getResourceAsStream("/assets/photon/models/" + name + ".obj"),
                name + ".obj missing from resources")) {
            return ObjMeshParser.parse(in, flipV);
        }
    }

    private static float tangent(PhotonMesh mesh, int vertex, int component) {
        return mesh.tangents()[PhotonMesh.tangentOffset(vertex) + component];
    }

    /** {@code component} indexes the geometry stream: 0..2 position, 3..5 normal. */
    private static float geometry(PhotonMesh mesh, int vertex, int component) {
        return mesh.geometry()[PhotonMesh.geometryOffset(vertex) + component];
    }

    /**
     * quad.obj is a +Z facing unit square with u along +X and v along +Y, so the tangent is exactly +X.
     * flipV mirrors v, which must flip the handedness while leaving the tangent itself alone — the one
     * assertion that pins the sign convention the shader's {@code cross(N, T) * w} depends on.
     */
    @Test
    void quadTangentIsPlusXAndFlipVFlipsHandedness() throws IOException {
        for (boolean flipV : new boolean[]{false, true}) {
            var mesh = load("quad", flipV);
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                assertEquals(1f, tangent(mesh, vertex, 0), 1e-5f, "tangent.x, flipV=" + flipV);
                assertEquals(0f, tangent(mesh, vertex, 1), 1e-5f, "tangent.y, flipV=" + flipV);
                assertEquals(0f, tangent(mesh, vertex, 2), 1e-5f, "tangent.z, flipV=" + flipV);
                assertEquals(flipV ? -1f : 1f, tangent(mesh, vertex, 3), "handedness, flipV=" + flipV);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cube", "quad", "plane", "sphere", "cylinder", "capsule"})
    void primitiveTangentsAreUnitAndPerpendicularToTheNormal(String name) throws IOException {
        var mesh = load(name, true);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            float tx = tangent(mesh, vertex, 0);
            float ty = tangent(mesh, vertex, 1);
            float tz = tangent(mesh, vertex, 2);
            float w = tangent(mesh, vertex, 3);

            assertTrue(Float.isFinite(tx) && Float.isFinite(ty) && Float.isFinite(tz),
                    name + " tangent not finite at vertex " + vertex);
            assertEquals(1f, tx * tx + ty * ty + tz * tz, 1e-3f, name + " tangent not unit length");
            assertTrue(w == 1f || w == -1f, name + " handedness must be +/-1 but was " + w);

            // the normal is unit-length for every shipped primitive (BuiltinPrimitiveObjTest), so a
            // raw dot is the orthogonality measure
            float dot = tx * geometry(mesh, vertex, 3) + ty * geometry(mesh, vertex, 4)
                    + tz * geometry(mesh, vertex, 5);
            assertEquals(0f, dot, 1e-3f, name + " tangent not perpendicular to normal at vertex " + vertex);
        }
    }

    /**
     * The sphere's equirectangular u runs continuously past 1 at the seam, so seam vertices share a
     * position and normal and weld together — legitimately, since both sides point the same way. The
     * failure this guards is the tangent collapsing to the fallback there.
     */
    @Test
    void sphereTangentsFollowLongitude() throws IOException {
        var mesh = load("sphere", true);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            float x = geometry(mesh, vertex, 0), z = geometry(mesh, vertex, 2);
            if (Math.hypot(x, z) < 1e-3) continue; // pole: longitude undefined

            // u = longitude, so dP/du points along +theta = (-sin, 0, cos) . (x,z) = (-z, 0, x)
            float len = (float) Math.hypot(x, z);
            float ex = -z / len, ez = x / len;
            float dot = tangent(mesh, vertex, 0) * ex + tangent(mesh, vertex, 2) * ez;
            assertEquals(1f, dot, 1e-2f,
                    "sphere tangent not along +longitude at (" + x + ",," + z + ")");
        }
    }

    /**
     * Two faces at the same position/normal with mirrored UVs must NOT average into nothing.
     *
     * <p>⚠️ The two vertices <i>on</i> the mirror seam are one welded vertex now and can only carry one
     * frame — see the class javadoc of {@link MeshTangents} for why that is the exporter's problem and
     * not ours. What must still hold is that the two islands did not cancel: each island's own vertex
     * keeps its own handedness, which is what the weld key's sign component buys.</p>
     */
    @Test
    void mirroredUvIslandsKeepOppositeHandedness() {
        // Two coplanar +Z triangles sharing the edge (0,0)-(0,1); the right one's u increases, the
        // left one's decreases, i.e. the texture is mirrored across the shared edge.
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v -1 0 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1
                f 1/1/1 3/3/1 4/2/1
                """, false);
        assertEquals(2, mesh.triangleCount());
        // v0 and v2 are the shared seam; v1 belongs to the right island only, v3 to the left only
        assertEquals(4, mesh.vertexCount());

        float wRight = tangent(mesh, 1, 3);
        float wLeft = tangent(mesh, 3, 3);
        assertEquals(-wRight, wLeft, "mirrored islands must carry opposite handedness");
        for (int vertex : new int[]{1, 3}) {
            float tx = tangent(mesh, vertex, 0);
            float ty = tangent(mesh, vertex, 1);
            float tz = tangent(mesh, vertex, 2);
            assertEquals(1f, tx * tx + ty * ty + tz * tz, 1e-3f,
                    "vertex " + vertex + " tangent collapsed — the weld key is not splitting on UV winding");
        }
    }

    /** An OBJ face with no {@code vt} has no UV gradient at all; the fallback must still be usable. */
    @Test
    void facesWithoutUVsFallBackToAPerpendicularFrame() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vn 0 0 1
                f 1//1 2//1 3//1
                """, false);
        assertEquals(1, mesh.triangleCount());
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            float tx = tangent(mesh, vertex, 0);
            float ty = tangent(mesh, vertex, 1);
            float tz = tangent(mesh, vertex, 2);
            assertEquals(1f, tx * tx + ty * ty + tz * tz, 1e-3f, "fallback tangent not unit length");
            assertEquals(0f, tz, 1e-5f, "fallback tangent must be perpendicular to the +Z normal");
            assertEquals(1f, tangent(mesh, vertex, 3), "fallback handedness");
        }
    }

    /** Smooth-shaded geometry welds across faces: one shared tangent, not one per face. */
    @Test
    void sharedCornersWeldIntoOneTangent() {
        // two triangles forming a flat quad in the XY plane, split along the diagonal
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1
                f 1/1/1 3/3/1 4/4/1
                """, false);
        assertEquals(2, mesh.triangleCount());
        assertEquals(4, mesh.vertexCount());
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            assertEquals(1f, tangent(mesh, vertex, 0), 1e-5f, "tangent.x");
            assertEquals(1f, tangent(mesh, vertex, 3), "handedness");
        }
    }

    /**
     * A real quad (four distinct corners, so both of its triangles exist — the JSON-model shape) whose
     * SECOND triangle has collinear UVs. Vertices 0 and 2 belong to both triangles; the degenerate one
     * contributes nothing and must not steal them from the good one, or the result would depend on which
     * triangle happened to be visited last.
     */
    @Test
    void aDegenerateHalfDoesNotDiscardTheGoodHalfsTangents() {
        // uv (0,0) (1,0) (1,1) (0.5,0.5): triangle (0,1,2) is fine, triangle (2,3,0) is uv-collinear
        var mesh = new PhotonMesh.Builder()
                .quad(corner(0, 0, 0f, 0f), corner(1, 0, 1f, 0f),
                        corner(1, 1, 1f, 1f), corner(0, 1, 0.5f, 0.5f), 1f)
                .build();
        assertEquals(2, mesh.triangleCount());
        assertEquals(4, mesh.vertexCount());
        for (int vertex : new int[]{0, 1, 2}) {
            assertEquals(1f, tangent(mesh, vertex, 0), 1e-5f,
                    "vertex " + vertex + " lost the good triangle's tangent");
            assertEquals(1f, tangent(mesh, vertex, 3), "vertex " + vertex + " handedness");
        }
        // vertex 3 only ever belonged to the degenerate half, so it legitimately falls back
        float tx = tangent(mesh, 3, 0), ty = tangent(mesh, 3, 1), tz = tangent(mesh, 3, 2);
        assertEquals(1f, tx * tx + ty * ty + tz * tz, 1e-3f, "fallback tangent not unit length");
    }

    /** One corner of a quad lying in the XY plane: {@code x,y,0,u,v} with a +Z normal. */
    private static float[] corner(float x, float y, float u, float v) {
        return new float[]{x, y, 0f, u, v, 0f, 0f, 1f};
    }

    @Test
    void emptyMeshHasEmptyTangents() {
        assertEquals(0, PhotonMesh.EMPTY.tangents().length);
    }
}
