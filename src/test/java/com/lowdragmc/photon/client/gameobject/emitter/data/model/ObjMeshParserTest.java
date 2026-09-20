package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjMeshParserTest {

    /** {@code component} indexes the geometry stream: 0..2 position, 3..5 normal. */
    private static float geometry(PhotonMesh mesh, int vertex, int component) {
        return mesh.geometry()[PhotonMesh.geometryOffset(vertex) + component];
    }

    /** {@code component} indexes the attribute stream: 0 u, 1 v, 2 shade. */
    private static float attribute(PhotonMesh mesh, int vertex, int component) {
        return mesh.attributes()[PhotonMesh.attributeOffset(vertex) + component];
    }

    @Test
    void parsesTriangleWithUVsAndNormals() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1
                """, false);
        assertEquals(1, mesh.triangleCount());
        assertEquals(3, mesh.vertexCount());
        assertArrayEquals(new int[]{0, 1, 2}, mesh.indices());
        // raw author-space positions, no centering
        assertEquals(1f, geometry(mesh, 1, 0));
        assertEquals(0f, geometry(mesh, 1, 1));
        // uv
        assertEquals(1f, attribute(mesh, 1, 0));
        assertEquals(0f, attribute(mesh, 1, 1));
        // normal
        assertEquals(1f, geometry(mesh, 0, 5));
        assertEquals(1f, attribute(mesh, 0, 2), "OBJ carries no face shade");
    }

    @Test
    void flipVMirrorsUVs() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0.25
                vt 1 0.25
                vt 0 1
                f 1/1 2/2 3/3
                """, true);
        assertEquals(0.75f, attribute(mesh, 0, 1));
        assertEquals(0f, attribute(mesh, 2, 1));
    }

    /**
     * A convex quad face stays a quad: two triangles sharing the {@code a-c} diagonal, flagged so the
     * CPU draw path can emit it as one QUADS-mode primitive instead of two degenerate ones.
     */
    @Test
    void convexQuadFaceStaysAQuadAndNegativeIndicesResolve() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                f -4 -3 -2 -1
                """, false);
        assertEquals(2, mesh.triangleCount());
        assertEquals(4, mesh.vertexCount(), "a quad's four corners, welded, not 2x3 loose ones");
        assertArrayEquals(new int[]{0, 1, 2, 2, 3, 0}, mesh.indices());
        assertTrue(mesh.quadPaired(0), "first half of an authored quad");
        assertFalse(mesh.quadPaired(1), "second half closes it");
    }

    /**
     * A concave quad must NOT take the quad path: {@code Builder.quad} splits on the {@code a-c}
     * diagonal, which for a reflex corner at {@code a} or {@code c} lies outside the polygon and would
     * put geometry where the author drew none.
     */
    @Test
    void concaveQuadFaceIsEarClippedInstead() {
        // corner 2 is pulled in past the 0-2 diagonal, making it reflex
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 2 0 0
                v 0.6 0.6 0
                v 0 2 0
                f 1 2 3 4
                """, false);
        assertEquals(2, mesh.triangleCount());
        assertFalse(mesh.quadPaired(0), "a concave quad may not be reassembled as a quad");
        assertFalse(mesh.quadPaired(1));
    }

    /** Two faces naming the same {@code v/vt/vn} triplets are the same vertices, by definition of OBJ. */
    @Test
    void facesSharingATripletShareTheVertex() {
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
        assertEquals(4, mesh.vertexCount(), "6 corners over 4 distinct triplets");
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, mesh.indices());
    }

    /**
     * ⚠️ Corners with no {@code vn} take their <b>face's</b> polygon normal, so two faces sharing a
     * {@code v/vt} but no {@code vn} are genuinely different vertices and must not weld — welding them
     * would give one of the two faces the other's normal.
     */
    @Test
    void cornersWithoutNormalsDoNotWeldAcrossFaces() {
        // two triangles sharing the edge 1-2, folded 90 degrees apart, no vn anywhere
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 0 0 -1
                f 1 2 3
                f 1 3 4
                """, false);
        assertEquals(2, mesh.triangleCount());
        assertEquals(6, mesh.vertexCount(), "no vn: the faces cannot share a vertex");
        // first face is CCW in the XY plane -> +Z; the second lies in the YZ plane -> not +Z
        assertEquals(1f, geometry(mesh, 0, 5), 1e-6f);
        assertNotEquals(1f, geometry(mesh, 3, 5), 1e-6f);
    }

    @Test
    void missingNormalsFallBackToFaceNormal() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                """, false);
        // Newell's method normal of a CCW triangle in the XY plane is +Z
        assertEquals(0f, geometry(mesh, 0, 3));
        assertEquals(0f, geometry(mesh, 0, 4));
        assertEquals(1f, geometry(mesh, 0, 5));
    }

    /** Raw-UV sources record no sprite bounds at all — an empty array stands for the identity. */
    @Test
    void rawUvSourcesCarryNoSpriteBounds() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                """, false);
        assertEquals(0, mesh.spriteBounds().length);
    }

    @Test
    void invalidAndEmptyInputYieldsEmptyMesh() {
        assertTrue(ObjMeshParser.parseText("", false).isEmpty());
        assertTrue(ObjMeshParser.parseText("# comment only\no name\n", false).isEmpty());
        // face with out-of-range index is skipped
        assertTrue(ObjMeshParser.parseText("v 0 0 0\nf 1 2 3\n", false).isEmpty());
        assertSame(PhotonMesh.EMPTY, ObjMeshParser.parseText("", false));
    }
}
