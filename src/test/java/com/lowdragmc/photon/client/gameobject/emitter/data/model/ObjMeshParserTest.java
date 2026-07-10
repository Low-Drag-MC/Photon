package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjMeshParserTest {

    private static float vertex(PhotonMesh mesh, int quad, int corner, int component) {
        return mesh.vertices()[PhotonMesh.vertexOffset(quad, corner) + component];
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
        assertEquals(1, mesh.quadCount());
        assertTrue(mesh.isTriangle(0), "corner 3 must repeat corner 2");
        // raw author-space positions, no centering
        assertEquals(1f, vertex(mesh, 0, 1, 0));
        assertEquals(0f, vertex(mesh, 0, 1, 1));
        // uv
        assertEquals(1f, vertex(mesh, 0, 1, 3));
        assertEquals(0f, vertex(mesh, 0, 1, 4));
        // normal
        assertEquals(1f, vertex(mesh, 0, 0, 7));
        assertEquals(1f, mesh.shadeBrightness(0));
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
        assertEquals(0.75f, vertex(mesh, 0, 0, 4));
        assertEquals(0f, vertex(mesh, 0, 2, 4));
    }

    @Test
    void quadFaceEmitsTwoTrianglesAndNegativeIndicesResolve() {
        var mesh = ObjMeshParser.parseText("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                f -4 -3 -2 -1
                """, false);
        assertEquals(2, mesh.quadCount(), "n-gon triangulation: quad = 2 triangles");
        assertTrue(mesh.isTriangle(0));
        assertTrue(mesh.isTriangle(1));
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
        assertEquals(0f, vertex(mesh, 0, 0, 5));
        assertEquals(0f, vertex(mesh, 0, 0, 6));
        assertEquals(1f, vertex(mesh, 0, 0, 7));
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
