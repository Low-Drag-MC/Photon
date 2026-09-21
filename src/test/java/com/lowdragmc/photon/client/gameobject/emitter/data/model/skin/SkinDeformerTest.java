package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linear blend skinning against arithmetic worked out by hand. Every failure mode here is the same one:
 * the model still draws, and it draws wrong.
 */
class SkinDeformerTest {

    private static final float[] IDENTITY_TRS = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
    private static final float[] IDENTITY_MATRIX = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    /** TRS with a translation and no rotation or scale. */
    private static float[] translationTrs(float x, float y, float z) {
        return new float[]{x, y, z, 0, 0, 0, 1, 1, 1, 1};
    }

    /** TRS rotating {@code degrees} about +Y. */
    private static float[] yRotationTrs(double degrees) {
        double half = Math.toRadians(degrees) / 2;
        return new float[]{0, 0, 0, 0, (float) Math.sin(half), 0, (float) Math.cos(half), 1, 1, 1};
    }

    /** Affine 3x4 of a pure translation. */
    private static float[] translationMatrix(float x, float y, float z) {
        return new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    /** One joint at rest with an identity bind pose. */
    private static Skeleton oneJoint(float[] trs, float[] inverseBind) {
        var builder = new Skeleton.Builder();
        builder.joint(0, -1, "root", trs, 0);
        builder.inverseBind(0, inverseBind, 0);
        var out = new Skeleton[1];
        builder.sortInto(out);
        return out[0];
    }

    /** A single triangle whose three vertices sit at the given positions, normal +Z. */
    private static PhotonMesh triangle(float[]... positions) {
        var builder = new PhotonMesh.Builder();
        var corners = new float[positions.length][];
        for (int i = 0; i < positions.length; i++) {
            corners[i] = new float[]{positions[i][0], positions[i][1], positions[i][2], 0, 0, 0, 0, 1};
        }
        builder.triangle(corners[0], corners[1], corners[2]);
        return builder.build();
    }

    /** Weights binding every vertex fully to one joint. */
    private static MeshSkin boundTo(int vertexCount, int joint) {
        var joints = new int[vertexCount * MeshSkin.INFLUENCES];
        var weights = new float[vertexCount * MeshSkin.INFLUENCES];
        for (int v = 0; v < vertexCount; v++) {
            joints[v * MeshSkin.INFLUENCES] = joint;
            weights[v * MeshSkin.INFLUENCES] = 1f;
        }
        return new MeshSkin(joints, weights);
    }

    @Test
    void oneJointTranslationMovesTheVertexByExactlyThat() {
        var skeleton = oneJoint(IDENTITY_TRS, IDENTITY_MATRIX);
        var clip = new AnimationClip("slide", List.of(new AnimationClip.Channel(
                0, AnimationClip.Path.TRANSLATION, AnimationClip.Interpolation.LINEAR,
                new float[]{0f, 1f}, new float[]{0, 0, 0, 0, 10, 0})));
        var mesh = triangle(new float[]{1, 2, 3}, new float[]{0, 0, 0}, new float[]{0, 1, 0});
        var deformer = new SkinDeformer(skeleton);
        var out = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];

        deformer.pose(clip, 0.5f);
        deformer.deform(mesh, boundTo(mesh.vertexCount(), 0), out);

        assertEquals(1f, out[0], 1e-5f);
        assertEquals(7f, out[1], 1e-5f, "2 + half of 10");
        assertEquals(3f, out[2], 1e-5f);
        assertEquals(1f, out[5], 1e-5f, "a translation leaves the normal alone");
    }

    @Test
    void aNullClipPosesTheSkeletonAtRest() {
        // rest translation (0,10,0) with the matching inverse bind: the two must cancel, or the model
        // arrives at twice its bind offset
        var skeleton = oneJoint(translationTrs(0, 10, 0), translationMatrix(0, -10, 0));
        var mesh = triangle(new float[]{1, 2, 3}, new float[]{0, 0, 0}, new float[]{0, 1, 0});
        var deformer = new SkinDeformer(skeleton);
        var out = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];

        deformer.pose(null, 0f);
        deformer.deform(mesh, boundTo(mesh.vertexCount(), 0), out);

        assertEquals(1f, out[0], 1e-5f);
        assertEquals(2f, out[1], 1e-5f, "the bind pose cancelled; the vertex did not move");
        assertEquals(3f, out[2], 1e-5f);
    }

    @Test
    void twoJointsBlendByTheirWeights() {
        var builder = new Skeleton.Builder();
        builder.joint(0, -1, "a", translationTrs(10, 0, 0), 0);
        builder.joint(1, -1, "b", IDENTITY_TRS, 0);
        builder.inverseBind(0, IDENTITY_MATRIX, 0);
        builder.inverseBind(1, IDENTITY_MATRIX, 0);
        var out = new Skeleton[1];
        builder.sortInto(out);

        var mesh = triangle(new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{0, 1, 0});
        var joints = new int[mesh.vertexCount() * MeshSkin.INFLUENCES];
        var weights = new float[mesh.vertexCount() * MeshSkin.INFLUENCES];
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int at = v * MeshSkin.INFLUENCES;
            joints[at] = 0;
            weights[at] = 0.5f;
            joints[at + 1] = 1;
            weights[at + 1] = 0.5f;
        }

        var deformer = new SkinDeformer(out[0]);
        var geometry = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(null, 0f);
        deformer.deform(mesh, new MeshSkin(joints, weights), geometry);

        assertEquals(5f, geometry[0], 1e-5f, "half of the joint that moved 10");
    }

    /** ⚠️ A child's transform is its parent's composed with its own, and in that order. */
    @Test
    void aChildJointInheritsItsParentsRotation() {
        var builder = new Skeleton.Builder();
        builder.joint(0, -1, "parent", yRotationTrs(90), 0);
        builder.joint(1, 0, "child", translationTrs(1, 0, 0), 0);
        builder.inverseBind(0, IDENTITY_MATRIX, 0);
        builder.inverseBind(1, IDENTITY_MATRIX, 0);
        var out = new Skeleton[1];
        builder.sortInto(out);

        var mesh = triangle(new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{0, 0, 0});
        var deformer = new SkinDeformer(out[0]);
        var geometry = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(null, 0f);
        deformer.deform(mesh, boundTo(mesh.vertexCount(), 1), geometry);

        // the child sits at +X in its parent's space; rotating that 90 degrees about +Y lands on -Z
        assertEquals(0f, geometry[0], 1e-5f, "x");
        assertEquals(0f, geometry[1], 1e-5f, "y");
        assertEquals(-1f, geometry[2], 1e-5f, "+X rotated 90 degrees about +Y is -Z");
    }

    @Test
    void theBuilderPutsParentsBeforeChildrenWhateverOrderTheyArriveIn() {
        var builder = new Skeleton.Builder();
        // child first, which is legal glTF and breaks the single forward composition pass
        builder.joint(7, 3, "child", IDENTITY_TRS, 0);
        builder.joint(3, -1, "parent", IDENTITY_TRS, 0);
        var out = new Skeleton[1];
        builder.sortInto(out);
        var skeleton = out[0];

        assertEquals(2, skeleton.jointCount());
        for (int joint = 0; joint < skeleton.jointCount(); joint++) {
            int parent = skeleton.parent(joint);
            assertTrue(parent < joint, "joint " + joint + " has parent " + parent + ", which is not before it");
        }
        assertEquals("parent", skeleton.name(0));
        assertEquals("child", skeleton.name(1));
    }

    @Test
    void aVertexWithNoWeightsIsCopiedThrough() {
        var skeleton = oneJoint(translationTrs(100, 0, 0), IDENTITY_MATRIX);
        var mesh = triangle(new float[]{1, 2, 3}, new float[]{4, 5, 6}, new float[]{7, 8, 9});
        // every weight zero: a rigid vertex whose node transform is already in its position
        var skin = new MeshSkin(new int[mesh.vertexCount() * MeshSkin.INFLUENCES],
                new float[mesh.vertexCount() * MeshSkin.INFLUENCES]);
        assertTrue(skin.isEmpty());

        var deformer = new SkinDeformer(skeleton);
        var out = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(null, 0f);
        deformer.deform(mesh, skin, out);

        assertArrayEquals(mesh.geometry(), out, 1e-6f, "a rigid vertex must not be moved by any joint");
    }

    @Test
    void rotationRotatesTheNormalAndKeepsItUnit() {
        var skeleton = oneJoint(yRotationTrs(90), IDENTITY_MATRIX);
        // normal +Z, which a 90 degree turn about +Y takes to +X
        var builder = new PhotonMesh.Builder();
        builder.triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                new float[]{0, 1, 0, 0, 1, 0, 0, 1});
        var mesh = builder.build();

        var deformer = new SkinDeformer(skeleton);
        var out = new float[mesh.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(null, 0f);
        deformer.deform(mesh, boundTo(mesh.vertexCount(), 0), out);

        assertEquals(1f, out[3], 1e-5f, "+Z rotated 90 degrees about +Y is +X");
        assertEquals(0f, out[4], 1e-5f);
        assertEquals(0f, out[5], 1e-5f);
        float length = out[3] * out[3] + out[4] * out[4] + out[5] * out[5];
        assertEquals(1f, length, 1e-5f, "normals stay unit");
    }

    /** A rotated joint turns the tangent frame with it, or a normal-mapped model lights as if it never
     *  moved — which reads as the animation not affecting the surface at all. */
    @Test
    void theTangentFrameTurnsWithTheJoint() {
        var skeleton = oneJoint(yRotationTrs(90), IDENTITY_MATRIX);
        var mesh = triangle(new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{0, 1, 0});
        int vertices = mesh.vertexCount();

        var restTangents = new float[vertices * PhotonMesh.FLOATS_PER_TANGENT];
        for (int v = 0; v < vertices; v++) {
            int t = v * PhotonMesh.FLOATS_PER_TANGENT;
            restTangents[t] = 1f;      // +X
            restTangents[t + 3] = -1f; // and a handedness a pose must not touch
        }

        var deformer = new SkinDeformer(skeleton);
        deformer.pose(null, 0f);
        var out = new float[vertices * PhotonMesh.FLOATS_PER_GEOMETRY];
        var outTangents = new float[restTangents.length];
        deformer.deform(mesh, boundTo(vertices, 0), out, restTangents, outTangents);

        for (int v = 0; v < vertices; v++) {
            int t = v * PhotonMesh.FLOATS_PER_TANGENT;
            assertEquals(0f, outTangents[t], 1e-5f, "vertex " + v + " tangent x");
            assertEquals(0f, outTangents[t + 1], 1e-5f, "vertex " + v + " tangent y");
            assertEquals(-1f, outTangents[t + 2], 1e-5f, "+X rotated 90 about +Y is -Z");
            assertEquals(-1f, outTangents[t + 3], "handedness is not a pose's business");
        }
    }

    /** The three-argument form is what everything that does not draw with tangents calls. */
    @Test
    void deformingWithoutTangentsMatchesDeformingWithThem() {
        var skeleton = oneJoint(yRotationTrs(37), translationMatrix(0, -1, 0));
        var mesh = triangle(new float[]{0, 0, 0}, new float[]{2, 0, 0}, new float[]{0, 3, 1});
        int vertices = mesh.vertexCount();
        var skin = boundTo(vertices, 0);

        var deformer = new SkinDeformer(skeleton);
        deformer.pose(null, 0f);
        var withoutTangents = new float[vertices * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.deform(mesh, skin, withoutTangents);

        var withTangents = new float[withoutTangents.length];
        deformer.deform(mesh, skin, withTangents,
                new float[vertices * PhotonMesh.FLOATS_PER_TANGENT],
                new float[vertices * PhotonMesh.FLOATS_PER_TANGENT]);

        assertArrayEquals(withoutTangents, withTangents, 0f);
    }

    /** Mismatched lengths skip tangents rather than throwing mid-frame. */
    @Test
    void tangentArraysThatDoNotFitAreSkipped() {
        var skeleton = oneJoint(IDENTITY_TRS, IDENTITY_MATRIX);
        var mesh = triangle(new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{0, 1, 0});
        int vertices = mesh.vertexCount();
        var deformer = new SkinDeformer(skeleton);
        deformer.pose(null, 0f);
        var out = new float[vertices * PhotonMesh.FLOATS_PER_GEOMETRY];
        var tooShort = new float[4];
        assertDoesNotThrow(() -> deformer.deform(mesh, boundTo(vertices, 0), out, tooShort, tooShort));
        assertDoesNotThrow(() -> deformer.deform(mesh, boundTo(vertices, 0), out, null,
                new float[vertices * PhotonMesh.FLOATS_PER_TANGENT]));
    }

    @Test
    void anInverseBindMatrixIsReadColumnMajor() {
        // glTF's 4x4 with the translation in elements 12..14
        var columnMajor = new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                5, 6, 7, 1};
        var affine = new float[Skeleton.FLOATS_PER_MATRIX];
        Skeleton.fromColumnMajor4x4(affine, 0, columnMajor, 0);
        assertEquals(5f, affine[3], 1e-6f, "translation x");
        assertEquals(6f, affine[7], 1e-6f, "translation y");
        assertEquals(7f, affine[11], 1e-6f, "translation z");
        assertEquals(1f, affine[0], 1e-6f);
    }

    /** T * R * S, not any other order: a scaled child of a rotated parent depends on it. */
    @Test
    void fromTrsComposesTranslationRotationScale() {
        var trs = new float[]{1, 2, 3, 0, 0, 0, 1, 2, 4, 8};
        var matrix = new float[Skeleton.FLOATS_PER_MATRIX];
        Skeleton.fromTrs(matrix, 0, trs, 0);
        assertEquals(2f, matrix[0], 1e-6f, "scale x on the diagonal");
        assertEquals(4f, matrix[5], 1e-6f, "scale y");
        assertEquals(8f, matrix[10], 1e-6f, "scale z");
        assertEquals(1f, matrix[3], 1e-6f, "translation is not scaled");
        assertEquals(2f, matrix[7], 1e-6f);
        assertEquals(3f, matrix[11], 1e-6f);
    }
}
