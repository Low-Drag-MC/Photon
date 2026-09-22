package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.MeshSkin;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinDeformer;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parser against a real export rather than a hand-built fixture: {@code fox.glb}, the Khronos glTF
 * sample Fox (CC0), whose inverse bind matrices and keyframes came from an actual exporter.
 *
 * <p>Four ways of being wrong survive a fixture with an identity bind pose and round keyframes — a
 * transposed inverse bind matrix, a TRS composed in another order, a child composed before its parent,
 * {@code JOINTS_0} through the wrong indirection — and all four land on
 * {@link #restPoseReproducesTheAuthoredVertices()}.</p>
 */
class RealGlbTest {

    private static SkinnedModel fox() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                RealGlbTest.class.getResourceAsStream("/assets/photon/models/fox.glb"),
                "fox.glb missing from test resources")) {
            return GltfMeshParser.parseModel(in.readAllBytes(), false);
        }
    }

    @Test
    void readsTheExportsGeometrySkinAndClips() throws IOException {
        var model = fox();
        assertTrue(model.isAnimated(), "a rigged, animated export must come out animated");
        assertEquals(1728, model.mesh().vertexCount(), "the file's own vertex count, not a de-indexed one");
        assertEquals(576, model.mesh().triangleCount());
        assertEquals(List.of("Survey", "Walk", "Run"), model.clipNames(), "in file order");

        var skeleton = Objects.requireNonNull(model.skeleton());
        assertEquals(25, skeleton.jointCount(), "24 bones plus the armature node above them");
        for (int joint = 0; joint < skeleton.jointCount(); joint++) {
            assertTrue(skeleton.parent(joint) < joint,
                    "joint " + joint + "'s parent " + skeleton.parent(joint) + " is not before it");
        }
        for (var clip : model.clips()) {
            assertTrue(clip.duration() > 0f, clip.name() + " has no duration");
            assertFalse(clip.channels().isEmpty(), clip.name() + " drives nothing");
        }
    }

    @Test
    void everyVertexIsSkinnedWithNormalizedWeights() throws IOException {
        var model = fox();
        var skin = Objects.requireNonNull(model.skin());
        var skeleton = Objects.requireNonNull(model.skeleton());
        assertEquals(model.mesh().vertexCount(), skin.vertexCount());
        for (int vertex = 0; vertex < skin.vertexCount(); vertex++) {
            int at = vertex * MeshSkin.INFLUENCES;
            float sum = 0f;
            for (int i = 0; i < MeshSkin.INFLUENCES; i++) {
                int joint = skin.joints()[at + i];
                assertTrue(joint >= 0 && joint < skeleton.jointCount(),
                        "vertex " + vertex + " influence " + i + " names joint " + joint);
                sum += skin.weights()[at + i];
            }
            assertEquals(1f, sum, 1e-4f, "vertex " + vertex + " weights do not sum to 1");
        }
    }

    /**
     * ⭐ The whole point of testing against an export. Its node transforms are the pose its inverse bind
     * matrices were built against, so {@code jointWorld * inverseBind} is the identity for every joint and
     * skinning the rest pose has to give the authored positions straight back.
     */
    @Test
    void restPoseReproducesTheAuthoredVertices() throws IOException {
        var model = fox();
        var mesh = model.mesh();
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var out = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];

        deformer.pose(null, 0f);
        deformer.deform(mesh, Objects.requireNonNull(model.skin()), out);

        var authored = mesh.geometry();
        // the fox is about 140 units tall, so a tolerance of 0.01 is four decimal places of its size and
        // nowhere near loose enough to hide a convention error
        float worst = 0f;
        int worstVertex = -1;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int g = PhotonMesh.geometryOffset(vertex);
            for (int k = 0; k < 3; k++) {
                float delta = Math.abs(out[g + k] - authored[g + k]);
                if (delta > worst) {
                    worst = delta;
                    worstVertex = vertex;
                }
            }
        }
        assertTrue(worst < 0.01f, "the rest pose moved vertex " + worstVertex + " by " + worst
                + " — a bind matrix, a TRS order or a parent composition is wrong");
    }

    /** And the clips actually move it, so the test above is not passing because nothing happens. */
    @Test
    void aClipMovesTheMesh() throws IOException {
        var model = fox();
        var mesh = model.mesh();
        var skin = Objects.requireNonNull(model.skin());
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var rest = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        var moved = new float[rest.length];

        deformer.pose(null, 0f);
        deformer.deform(mesh, skin, rest);

        var run = Objects.requireNonNull(model.clip("Run"));
        deformer.pose(run, run.duration() * 0.5f);
        deformer.deform(mesh, skin, moved);

        float largest = 0f;
        for (int i = 0; i < rest.length; i++) {
            largest = Math.max(largest, Math.abs(moved[i] - rest[i]));
        }
        assertTrue(largest > 1f, "halfway through Run nothing moved by more than " + largest);

        for (float f : moved) {
            assertTrue(Float.isFinite(f), "a deformed component is not finite");
        }
    }

    /** Normals survive the deformation as unit vectors, which the lighting depends on. */
    @Test
    void deformedNormalsStayUnitLength() throws IOException {
        var model = fox();
        var mesh = model.mesh();
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var out = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        var walk = Objects.requireNonNull(model.clip("Walk"));
        deformer.pose(walk, walk.duration() * 0.25f);
        deformer.deform(mesh, Objects.requireNonNull(model.skin()), out);

        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int g = PhotonMesh.geometryOffset(vertex);
            float length = out[g + 3] * out[g + 3] + out[g + 4] * out[g + 4] + out[g + 5] * out[g + 5];
            assertEquals(1f, length, 1e-3f, "vertex " + vertex + " normal is not unit after skinning");
        }
    }

    /** Selecting a clip by name picks that one, and an unknown name is not silently the first. */
    @Test
    void clipsAreSelectableByName() throws IOException {
        var model = fox();
        assertEquals("Survey", Objects.requireNonNull(model.clipAt(0)).name());
        assertEquals("Run", Objects.requireNonNull(model.clip("Run")).name());
        assertNull(model.clip("Gallop"), "an unknown name must not resolve to something");
    }
}
