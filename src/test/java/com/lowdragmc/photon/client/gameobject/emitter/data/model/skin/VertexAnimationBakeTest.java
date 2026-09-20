package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Sampling a clip into the table the vertex shader reads. */
class VertexAnimationBakeTest {

    private static final float[] REST_TRS = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    /** One joint, one triangle bound to it, and a clip sliding the joint +10 in y over one second. */
    private static SkinnedModel model() {
        var builder = new Skeleton.Builder();
        builder.joint(0, -1, "root", REST_TRS, 0);
        builder.inverseBind(0, IDENTITY, 0);
        var skeleton = new Skeleton[1];
        builder.sortInto(skeleton);

        var mesh = new PhotonMesh.Builder()
                .triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                        new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1})
                .build();
        var joints = new int[mesh.vertexCount() * MeshSkin.INFLUENCES];
        var weights = new float[joints.length];
        for (int v = 0; v < mesh.vertexCount(); v++) {
            weights[v * MeshSkin.INFLUENCES] = 1f;
        }
        var clip = new AnimationClip("slide", List.of(new AnimationClip.Channel(
                0, AnimationClip.Path.TRANSLATION, AnimationClip.Interpolation.LINEAR,
                new float[]{0f, 1f}, new float[]{0, 0, 0, 0, 10, 0})));
        return new SkinnedModel(mesh, new MeshSkin(joints, weights), skeleton[0], List.of(clip));
    }

    private static float y(float[] table, int frame, int vertexCount, int vertex) {
        return table[(frame * vertexCount + vertex) * VertexAnimationBake.FLOATS_PER_TEXEL + 1];
    }

    @Test
    void bakesOneTexelPerVertexPerFrame() {
        var model = model();
        var table = VertexAnimationBake.bake(model, model.clipAt(0), 4);
        assertNotNull(table);
        assertEquals(4 * model.mesh().vertexCount() * VertexAnimationBake.FLOATS_PER_TEXEL, table.length);
    }

    /**
     * Frame {@code f} is the clip at {@code duration * f / frames}, not {@code f / (frames - 1)} — so
     * wrapping past the last frame continues a looping clip instead of repeating its end.
     */
    @Test
    void framesAreSpacedSoALoopWrapsCleanly() {
        var model = model();
        int vertices = model.mesh().vertexCount();
        var table = VertexAnimationBake.bake(model, model.clipAt(0), 4);
        assertNotNull(table);

        assertEquals(0f, y(table, 0, vertices, 0), 1e-5f, "frame 0 is the start");
        assertEquals(2.5f, y(table, 1, vertices, 0), 1e-5f, "a quarter in");
        assertEquals(5f, y(table, 2, vertices, 0), 1e-5f);
        assertEquals(7.5f, y(table, 3, vertices, 0), 1e-5f, "three quarters, NOT the end");
    }

    @Test
    void everyVertexOfAFrameIsPosed() {
        var model = model();
        int vertices = model.mesh().vertexCount();
        var table = VertexAnimationBake.bake(model, model.clipAt(0), 2);
        assertNotNull(table);
        // the second frame is halfway: every vertex has moved by 5 from its authored y
        var authored = model.mesh().geometry();
        for (int vertex = 0; vertex < vertices; vertex++) {
            float restY = authored[PhotonMesh.geometryOffset(vertex) + 1];
            assertEquals(restY + 5f, y(table, 1, vertices, vertex), 1e-5f, "vertex " + vertex);
        }
    }

    @Test
    void aModelThatCannotBePosedBakesNothing() {
        var mesh = new PhotonMesh.Builder()
                .triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                        new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1})
                .build();
        assertNull(VertexAnimationBake.bake(SkinnedModel.staticModel(mesh), null, 8));
    }

    @Test
    void anAbsurdFrameCountIsRefusedRatherThanAllocated() {
        var model = model();
        assertNull(VertexAnimationBake.bake(model, model.clipAt(0), Integer.MAX_VALUE),
                "the table would be gigabytes");
        assertNull(VertexAnimationBake.bake(model, model.clipAt(0), 0));
    }

    /**
     * The normal rides in the texel's fourth channel, octahedral at 12 bits a component. ⚠️ The decode
     * here mirrors the shader's; they are in lockstep on the sign convention, which is
     * {@code >= 0 ? 1 : -1} and not {@code sign()}.
     */
    @Test
    void normalsSurviveThePackingToWithinAFractionOfADegree() {
        var out = new float[3];
        float worst = 0f;
        var random = new java.util.Random(1);
        for (int i = 0; i < 20000; i++) {
            float x = (float) random.nextGaussian();
            float y = (float) random.nextGaussian();
            float z = (float) random.nextGaussian();
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length < 1e-4f) continue;
            x /= length;
            y /= length;
            z /= length;

            VertexAnimationBake.unpackNormal(VertexAnimationBake.packNormal(x, y, z), out);
            assertEquals(1f, out[0] * out[0] + out[1] * out[1] + out[2] * out[2], 1e-4f, "not unit");
            float dot = Math.min(1f, Math.max(-1f, x * out[0] + y * out[1] + z * out[2]));
            worst = Math.max(worst, (float) Math.toDegrees(Math.acos(dot)));
        }
        assertTrue(worst < 0.1f, "worst angular error " + worst + " degrees");
    }

    /** The axes are where the octahedral fold is most fragile — and where sign() would collapse it. */
    @Test
    void theAxisDirectionsSurviveExactly() {
        var out = new float[3];
        float[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (var axis : axes) {
            VertexAnimationBake.unpackNormal(
                    VertexAnimationBake.packNormal(axis[0], axis[1], axis[2]), out);
            assertArrayEquals(axis, out, 1e-3f, java.util.Arrays.toString(axis));
        }
    }

    @Test
    void aDegenerateNormalPacksToSomethingUnit() {
        var out = new float[3];
        for (float[] bad : new float[][]{{0, 0, 0}, {Float.NaN, 0, 0}, {Float.POSITIVE_INFINITY, 1, 1}}) {
            VertexAnimationBake.unpackNormal(VertexAnimationBake.packNormal(bad[0], bad[1], bad[2]), out);
            assertEquals(1f, out[0] * out[0] + out[1] * out[1] + out[2] * out[2], 1e-3f,
                    java.util.Arrays.toString(bad));
        }
    }

    /** A baked frame carries the DEFORMED normal, not the rest pose's. */
    @Test
    void theTableCarriesTheDeformedNormal() {
        var model = model();
        int vertices = model.mesh().vertexCount();
        var table = VertexAnimationBake.bake(model, model.clipAt(0), 2);
        assertNotNull(table);
        var out = new float[3];
        for (int vertex = 0; vertex < vertices; vertex++) {
            int texel = vertex * VertexAnimationBake.FLOATS_PER_TEXEL;
            VertexAnimationBake.unpackNormal(table[texel + 3], out);
            // the fixture's joint only translates, so +Z survives the deformation
            assertEquals(0f, out[0], 1e-2f, "vertex " + vertex + " normal x");
            assertEquals(0f, out[1], 1e-2f, "vertex " + vertex + " normal y");
            assertEquals(1f, out[2], 1e-2f, "vertex " + vertex + " normal z");
        }
    }

    /** With no clip the table is the rest pose repeated — usable, and not a heap at the origin. */
    @Test
    void noClipBakesTheRestPose() {
        var model = model();
        var table = VertexAnimationBake.bake(model, null, 3);
        assertNotNull(table);
        int vertices = model.mesh().vertexCount();
        for (int frame = 0; frame < 3; frame++) {
            assertEquals(0f, y(table, frame, vertices, 0), 1e-5f);
        }
    }
}
