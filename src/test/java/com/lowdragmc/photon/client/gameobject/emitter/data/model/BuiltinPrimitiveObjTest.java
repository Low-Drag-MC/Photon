package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        var vertices = mesh.vertices();
        float[] max = new float[3];
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            for (int corner = 0; corner < 4; corner++) {
                int off = PhotonMesh.vertexOffset(quad, corner);
                for (int k = 0; k < 3; k++) {
                    assertTrue(Math.abs(vertices[off + k]) <= bounds[k] + 1e-4f,
                            name + " vertex out of bounds on axis " + k + ": " + vertices[off + k]);
                    max[k] = Math.max(max[k], Math.abs(vertices[off + k]));
                }
                // v in 0..1; u in 0..1 too, but equirectangular sources (sphere/capsule) let the
                // seam column slightly exceed 1 for a continuous single-image wrap (repeat sampling)
                float uMax = (name.equals("sphere") || name.equals("capsule")) ? 1.1f : 1 + 1e-4f;
                assertTrue(vertices[off + 3] >= -1e-4f && vertices[off + 3] <= uMax, name + " u out of range: " + vertices[off + 3]);
                assertTrue(vertices[off + 4] >= -1e-4f && vertices[off + 4] <= 1 + 1e-4f, name + " v out of range");
                // normals unit-length
                float nl = vertices[off + 5] * vertices[off + 5]
                        + vertices[off + 6] * vertices[off + 6]
                        + vertices[off + 7] * vertices[off + 7];
                assertEquals(1f, nl, 1e-3f, name + " normal not unit length");
            }
        }
        // the mesh actually reaches its bounds (catches accidentally scaled-down output)
        for (int k = 0; k < 3; k++) {
            if (bounds[k] > 0) {
                assertEquals(bounds[k], max[k], 0.05f, name + " smaller than expected on axis " + k);
            }
        }
    }

    @Test
    void sphereNormalsPointOutward() throws IOException {
        var mesh = load("sphere");
        var vertices = mesh.vertices();
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            for (int corner = 0; corner < 4; corner++) {
                int off = PhotonMesh.vertexOffset(quad, corner);
                float dot = vertices[off] * vertices[off + 5]
                        + vertices[off + 1] * vertices[off + 6]
                        + vertices[off + 2] * vertices[off + 7];
                assertTrue(dot > 0, "sphere normal not outward at quad " + quad);
            }
        }
    }

    /**
     * Equirectangular property: one texture wraps the whole sphere. Every vertex's u must match its
     * longitude atan2(z,x) (modulo the whole-turn seam offset), NOT a per-cube-face 0..1 tile.
     */
    @Test
    void sphereUvIsEquirectangular() throws IOException {
        var mesh = load("sphere");
        var vertices = mesh.vertices();
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            for (int corner = 0; corner < 4; corner++) {
                int off = PhotonMesh.vertexOffset(quad, corner);
                float x = vertices[off], y = vertices[off + 1], z = vertices[off + 2];
                if (Math.hypot(x, z) < 1e-4) continue; // pole: longitude undefined
                double expectedU = Math.atan2(z, x) / (2 * Math.PI) + 0.5;
                double fracU = vertices[off + 3] - Math.floor(vertices[off + 3]);
                double diff = Math.abs(fracU - expectedU);
                diff = Math.min(diff, 1 - diff); // circular distance
                assertTrue(diff < 1e-3, "u not longitude-mapped at (" + x + "," + y + "," + z + "): u=" + fracU + " expected=" + expectedU);
                // v must be latitude (parser was loaded with flipV=true, so v = 1 - fileV)
                double expectedV = 0.5 - Math.asin(Math.max(-1, Math.min(1, y / 0.5))) / Math.PI;
                assertEquals(expectedV, vertices[off + 4], 1e-3, "v not latitude-mapped");
            }
        }
    }
}
