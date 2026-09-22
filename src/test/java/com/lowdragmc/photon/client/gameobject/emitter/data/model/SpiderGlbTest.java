package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinDeformer;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.VertexAnimationBake;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The user's own spider export, which drew in its bind pose the moment per-particle phase was switched on
 * while the shared-clock path animated it fine. Both read the same clip through the same deformer, so one
 * of the numbers below is the difference.
 */
class SpiderGlbTest {

    private static SkinnedModel spider() throws IOException {
        try (var in = Objects.requireNonNull(
                SpiderGlbTest.class.getResourceAsStream("/assets/photon/models/spider.glb"),
                "spider.glb missing from test resources")) {
            return GltfMeshParser.parseModel(in.readAllBytes(), false);
        }
    }

    @Test
    void itParsesAsAnAnimatedModel() throws IOException {
        var model = spider();
        assertTrue(model.isAnimated(), "not animated at all");
        assertFalse(model.clips().isEmpty(), "no clips");
        assertEquals(3816, model.mesh().vertexCount());
        assertEquals(43, Objects.requireNonNull(model.skeleton()).jointCount());
        assertEquals(21, model.clips().size(), "21 clips, atk_1 first and idle among them");
        assertTrue(model.clipNames().contains("idle"));
        for (var clip : model.clips()) {
            assertTrue(clip.duration() > 0f, clip.name() + " has no duration");
            assertFalse(clip.channels().isEmpty(), clip.name() + " drives nothing");
        }
    }

    /** What the per-particle path does. A null table is the silent T-pose the user saw. */
    @Test
    void itBakesAPoseTableAtTheDefaultFrameCount() throws IOException {
        var model = spider();
        int frames = 30;
        long texels = (long) frames * model.mesh().vertexCount();
        assertTrue(texels < VertexAnimationBake.MAX_TEXELS,
                texels + " texels is past the " + VertexAnimationBake.MAX_TEXELS + " cap");
        var table = VertexAnimationBake.bake(model, model.clipAt(0), frames);
        assertNotNull(table, "the bake refused, so the draw falls back to the undeformed mesh");
    }

    /** And the table has to actually differ frame to frame, or it is a bind pose repeated 30 times. */
    @Test
    void theBakedFramesAreDifferentPoses() throws IOException {
        var model = spider();
        int frames = 30;
        var table = VertexAnimationBake.bake(model, model.clipAt(0), frames);
        assertNotNull(table);
        int stride = model.mesh().vertexCount() * VertexAnimationBake.FLOATS_PER_TEXEL;
        float widest = 0f;
        for (int i = 0; i < stride; i++) {
            // ⚠️ skip .w: it is a packed normal reaching 1.6e7, which swamps any position difference
            if (i % VertexAnimationBake.FLOATS_PER_TEXEL == 3) continue;
            widest = Math.max(widest, Math.abs(table[i] - table[(frames / 2) * stride + i]));
        }
        assertTrue(widest > 1.0e-4f, "every frame is the same pose");
    }

    /** The shared-clock path, for comparison: if this moves and the bake does not, they disagree. */
    @Test
    void theSharedClockPathDeformsIt() throws IOException {
        var model = spider();
        var clip = model.clipAt(0);
        assertNotNull(clip);
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var atZero = new float[model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        var halfway = new float[atZero.length];
        var skin = Objects.requireNonNull(model.skin());

        deformer.pose(clip, 0f);
        deformer.deform(model.mesh(), skin, atZero);
        deformer.pose(clip, clip.duration() / 2f);
        deformer.deform(model.mesh(), skin, halfway);

        float widest = 0f;
        for (int i = 0; i < atZero.length; i++) {
            widest = Math.max(widest, Math.abs(atZero[i] - halfway[i]));
        }
        assertTrue(widest > 1.0e-4f, "the deformer does not move it either");
    }
}
