package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.GltfMeshParser;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blending adjacent baked frames instead of snapping to the nearest. The table is a sampling of a
 * continuous deformation, so the question this answers is whether the blend actually lands nearer the
 * pose the deformer would have produced — if it did not, the extra texel fetch would buy nothing.
 *
 * <p>⚠️ Mirrors the arithmetic in the {@code PHOTON_VAT} block of {@code particle.glsl} and in
 * {@code TileParticleRenderer.poseBaseFor}: all three have to pick the same pair of frames and the same
 * blend, or the CPU and GPU paths disagree.</p>
 */
class VertexAnimationInterpolationTest {

    private static final int FRAMES = 8;

    private static SkinnedModel spider() throws IOException {
        try (var in = Objects.requireNonNull(
                VertexAnimationInterpolationTest.class
                        .getResourceAsStream("/assets/photon/models/spider.glb"),
                "spider.glb missing from test resources")) {
            return GltfMeshParser.parseModel(in, false);
        }
    }

    /** Position of {@code vertex} in a baked frame. */
    private static void framePosition(float[] table, int frame, int vertexCount, int vertex, float[] out) {
        int texel = (frame * vertexCount + vertex) * VertexAnimationBake.FLOATS_PER_TEXEL;
        out[0] = table[texel];
        out[1] = table[texel + 1];
        out[2] = table[texel + 2];
    }

    private static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void blendingLandsNearerTheRealDeformationThanSnapping() throws IOException {
        var model = spider();
        var clip = model.clipAt(0);
        assertNotNull(clip);
        int vertices = model.mesh().vertexCount();
        var table = VertexAnimationBake.bake(model, clip, FRAMES);
        assertNotNull(table);

        // a phase deliberately between two baked frames, where snapping is at its worst
        float phase = (2 + 0.5f) / FRAMES;
        float cursor = phase * FRAMES;
        int frame = (int) cursor;
        int next = frame + 1 == FRAMES ? 0 : frame + 1;
        float blend = cursor - (float) Math.floor(cursor);
        assertEquals(0.5f, blend, 1e-5f, "the fixture should sit halfway between frames");

        // what the deformer actually produces at that instant
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var truth = new float[vertices * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(clip, clip.duration() * phase);
        deformer.deform(model.mesh(), Objects.requireNonNull(model.skin()), truth);

        var at = new float[3];
        var after = new float[3];
        var exact = new float[3];
        var blended = new float[3];
        double snappedError = 0;
        double blendedError = 0;
        float worstSnapped = 0;
        for (int vertex = 0; vertex < vertices; vertex++) {
            framePosition(table, frame, vertices, vertex, at);
            framePosition(table, next, vertices, vertex, after);
            int g = PhotonMesh.geometryOffset(vertex);
            exact[0] = truth[g];
            exact[1] = truth[g + 1];
            exact[2] = truth[g + 2];
            for (int i = 0; i < 3; i++) {
                blended[i] = at[i] + (after[i] - at[i]) * blend;
            }
            float snapped = distance(at, exact);
            snappedError += snapped;
            blendedError += distance(blended, exact);
            worstSnapped = Math.max(worstSnapped, snapped);
        }
        System.out.printf("mean error over %d vertices: snapped %.5f, blended %.5f (worst snap %.5f)%n",
                vertices, snappedError / vertices, blendedError / vertices, worstSnapped);

        assertTrue(worstSnapped > 1e-4f, "the fixture does not move between frames, so this proves nothing");
        assertTrue(blendedError < snappedError,
                "blending is no closer to the real pose than snapping: snapped " + snappedError
                        + " vs blended " + blendedError);
    }

    /** Landing exactly on a baked frame must give that frame, blended or not. */
    @Test
    void aPhaseOnAFrameBoundaryBlendsNothing() throws IOException {
        var model = spider();
        var clip = model.clipAt(0);
        var table = VertexAnimationBake.bake(model, clip, FRAMES);
        assertNotNull(table);
        int vertices = model.mesh().vertexCount();

        float phase = 3f / FRAMES;
        float cursor = phase * FRAMES;
        int frame = (int) cursor;
        float blend = cursor - (float) Math.floor(cursor);
        assertEquals(3, frame);
        assertEquals(0f, blend, 1e-6f, "no blend on a boundary");

        // apply the blend for real: with a zero factor the next frame must not leak in, even though it
        // holds a different pose
        var at = new float[3];
        var after = new float[3];
        framePosition(table, frame, vertices, 0, at);
        framePosition(table, frame + 1, vertices, 0, after);
        assertFalse(distance(at, after) < 1e-6f, "the two frames are identical, so this proves nothing");

        var blended = new float[3];
        for (int i = 0; i < 3; i++) {
            blended[i] = at[i] + (after[i] - at[i]) * blend;
        }
        assertArrayEquals(at, blended, 0f, "a zero blend is exactly the frame it started on");
    }

    /** The frame after the last one is the first: the bake samples duration * f / frames, so a looping
     *  clip is continuous across the wrap rather than stuttering once a cycle. */
    @Test
    void theFrameAfterTheLastIsTheFirst() {
        int frame = FRAMES - 1;
        int next = frame + 1 == FRAMES ? 0 : frame + 1;
        assertEquals(0, next);
    }
}
