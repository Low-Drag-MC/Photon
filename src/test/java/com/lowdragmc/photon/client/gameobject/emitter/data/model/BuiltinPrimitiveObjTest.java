package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped Unity-style primitive assets parse cleanly and stay inside their expected bounds. */
class BuiltinPrimitiveObjTest {

    private static PhotonMesh load(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BuiltinPrimitiveObjTest.class.getResourceAsStream("/assets/photon/models/" + name + ".obj"),
                name + ".obj missing from resources")) {
            return ObjMeshParser.parse(in, true);
        }
    }

    @ParameterizedTest
    @CsvSource({
            // name, |x|max, |y|max, |z|max
            "cube,     0.5, 0.5, 0.5",
            "quad,     0.5, 0.5, 0.0",
            "plane,    5.0, 0.0, 5.0",
            "sphere,   0.5, 0.5, 0.5",
            "cylinder, 0.5, 1.0, 0.5",
            "capsule,  0.5, 1.0, 0.5",
    })
    void primitiveParsesWithinBounds(String name, float bx, float by, float bz) throws IOException {
        var mesh = load(name);
        assertFalse(mesh.isEmpty(), name + " parsed empty");
        var bounds = new float[]{bx, by, bz};
        var geometry = mesh.geometry();
        var attributes = mesh.attributes();
        float[] max = new float[3];
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int off = PhotonMesh.geometryOffset(vertex);
            int at = PhotonMesh.attributeOffset(vertex);
            for (int k = 0; k < 3; k++) {
                assertTrue(Math.abs(geometry[off + k]) <= bounds[k] + 1e-4f,
                        name + " vertex out of bounds on axis " + k + ": " + geometry[off + k]);
                max[k] = Math.max(max[k], Math.abs(geometry[off + k]));
            }
            // v in 0..1; u in 0..1 too, but equirectangular sources (sphere/capsule) let the
            // seam column slightly exceed 1 for a continuous single-image wrap (repeat sampling)
            float uMax = (name.equals("sphere") || name.equals("capsule")) ? 1.1f : 1 + 1e-4f;
            assertTrue(attributes[at] >= -1e-4f && attributes[at] <= uMax,
                    name + " u out of range: " + attributes[at]);
            assertTrue(attributes[at + 1] >= -1e-4f && attributes[at + 1] <= 1 + 1e-4f,
                    name + " v out of range");
            // normals unit-length
            float nl = geometry[off + 3] * geometry[off + 3]
                    + geometry[off + 4] * geometry[off + 4]
                    + geometry[off + 5] * geometry[off + 5];
            assertEquals(1f, nl, 1e-3f, name + " normal not unit length");
        }
        // the mesh actually reaches its bounds (catches accidentally scaled-down output)
        for (int k = 0; k < 3; k++) {
            if (bounds[k] > 0) {
                assertEquals(bounds[k], max[k], 0.05f, name + " smaller than expected on axis " + k);
            }
        }
    }

    /**
     * Every index addresses a real vertex and every triangle is referenced — the invariant the whole
     * indexed format rests on, and the one whose violation is a silent read of the wrong vertex rather
     * than a crash.
     */
    @ParameterizedTest
    @ValueSource(strings = {"cube", "quad", "plane", "sphere", "cylinder", "capsule"})
    void indicesStayInRange(String name) throws IOException {
        var mesh = load(name);
        assertEquals(0, mesh.indices().length % 3, name + " index count is not a multiple of 3");
        assertEquals(mesh.triangleCount() * 3, mesh.indices().length,
                name + " triangleCount disagrees with the index buffer");
        for (int index : mesh.indices()) {
            assertTrue(index >= 0 && index < mesh.vertexCount(),
                    name + " index " + index + " outside 0.." + mesh.vertexCount());
        }
    }

    /** The primitive generator emits quads, so welding must actually collapse the shared corners. */
    @Test
    void weldingCollapsesTheCubesSharedCorners() throws IOException {
        var mesh = load("cube");
        // 6 faces x 1 quad = 12 triangles over 6 x 4 corners; the faces have distinct normals, so
        // nothing welds ACROSS them — what must not happen is the pre-index 4-corners-per-triangle blowup
        assertEquals(12, mesh.triangleCount());
        assertEquals(24, mesh.vertexCount(), "one vertex per face corner, not per triangle corner");
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle += 2) {
            assertTrue(mesh.quadPaired(triangle), "cube face " + (triangle / 2) + " lost its quad");
        }
    }

    @Test
    void sphereNormalsPointOutward() throws IOException {
        var mesh = load("sphere");
        var geometry = mesh.geometry();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int off = PhotonMesh.geometryOffset(vertex);
            float dot = geometry[off] * geometry[off + 3]
                    + geometry[off + 1] * geometry[off + 4]
                    + geometry[off + 2] * geometry[off + 5];
            assertTrue(dot > 0, "sphere normal not outward at vertex " + vertex);
        }
    }

    /**
     * Equirectangular property: one texture wraps the whole sphere. Every vertex's u must match its
     * longitude atan2(z,x) (modulo the whole-turn seam offset), NOT a per-cube-face 0..1 tile.
     */
    @Test
    void sphereUvIsEquirectangular() throws IOException {
        var mesh = load("sphere");
        var geometry = mesh.geometry();
        var attributes = mesh.attributes();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int off = PhotonMesh.geometryOffset(vertex);
            int at = PhotonMesh.attributeOffset(vertex);
            float x = geometry[off], y = geometry[off + 1], z = geometry[off + 2];
            if (Math.hypot(x, z) < 1e-4) continue; // pole: longitude undefined
            double expectedU = Math.atan2(z, x) / (2 * Math.PI) + 0.5;
            double fracU = attributes[at] - Math.floor(attributes[at]);
            double diff = Math.abs(fracU - expectedU);
            diff = Math.min(diff, 1 - diff); // circular distance
            assertTrue(diff < 1e-3, "u not longitude-mapped at (" + x + "," + y + "," + z + "): u=" + fracU + " expected=" + expectedU);
            // v must be latitude (parser was loaded with flipV=true, so v = 1 - fileV)
            double expectedV = 0.5 - Math.asin(Math.max(-1, Math.min(1, y / 0.5))) / Math.PI;
            assertEquals(expectedV, attributes[at + 1], 1e-3, "v not latitude-mapped");
        }
    }
}
