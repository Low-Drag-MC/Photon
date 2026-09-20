package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linear blend skinning, asserted against arithmetic worked out by hand.
 *
 * <p>Every failure mode here is the same one: the model still draws, and it draws wrong. A transposed
 * matrix, a TRS composed in the other order, an inverse bind matrix applied twice or not at all — none of
 * them throws, and all of them look like an exploding character. So the assertions are on exact numbers
 * for poses simple enough to compute on paper.</p>
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

    /**
     * ⭐ The shape path and the render path must agree. An emission shape deforms one sampled point
     * ({@link SkinDeformer#deformPoint}) while the renderer deforms every vertex; if the two disagree,
     * particles spawn off the surface they were supposed to spawn on, by an amount that looks like a
     * physics bug rather than a skinning one.
     */
    @Test
    void deformPointAgreesWithDeformingTheWholeMesh() {
        var builder = new Skeleton.Builder();
        builder.joint(0, -1, "parent", yRotationTrs(37), 0);
        builder.joint(1, 0, "child", translationTrs(0.5f, 1.5f, -0.25f), 0);
        builder.inverseBind(0, translationMatrix(0, -1, 0), 0);
        builder.inverseBind(1, translationMatrix(-0.5f, 0, 0), 0);
        var out = new Skeleton[1];
        builder.sortInto(out);

        var mesh = triangle(new float[]{0, 0, 0}, new float[]{2, 0, 0}, new float[]{0, 3, 1});
        int vertices = mesh.vertexCount();
        var joints = new int[vertices * MeshSkin.INFLUENCES];
        var weights = new float[vertices * MeshSkin.INFLUENCES];
        for (int v = 0; v < vertices; v++) {
            int at = v * MeshSkin.INFLUENCES;
            joints[at] = 0;
            joints[at + 1] = 1;
            // a different blend per vertex, so a deformPoint that ignored the barycentric share would
            // still be wrong at an interior point even if it happened to be right at a corner
            weights[at] = 0.25f + 0.25f * v;
            weights[at + 1] = 1f - weights[at];
        }
        var skin = new MeshSkin(joints, weights);

        var deformer = new SkinDeformer(out[0]);
        deformer.pose(null, 0f);
        var geometry = new float[vertices * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.deform(mesh, skin, geometry);

        var indices = mesh.indices();
        int a = indices[0], b = indices[1], c = indices[2];
        var point = new float[3];

        // at a corner the barycentric weight is entirely that corner's
        deformer.deformPoint(skin, a, b, c, 1f, 0f, 0f,
                mesh.geometry()[PhotonMesh.geometryOffset(a)],
                mesh.geometry()[PhotonMesh.geometryOffset(a) + 1],
                mesh.geometry()[PhotonMesh.geometryOffset(a) + 2], point);
        int at = PhotonMesh.geometryOffset(a);
        assertEquals(geometry[at], point[0], 1e-5f, "corner x");
        assertEquals(geometry[at + 1], point[1], 1e-5f, "corner y");
        assertEquals(geometry[at + 2], point[2], 1e-5f, "corner z");

        // and at the centroid it must equal deforming each corner and averaging, since blend skinning
        // is linear in the position
        float third = 1f / 3f;
        var rest = mesh.geometry();
        float cx = third * (rest[PhotonMesh.geometryOffset(a)] + rest[PhotonMesh.geometryOffset(b)]
                + rest[PhotonMesh.geometryOffset(c)]);
        float cy = third * (rest[PhotonMesh.geometryOffset(a) + 1] + rest[PhotonMesh.geometryOffset(b) + 1]
                + rest[PhotonMesh.geometryOffset(c) + 1]);
        float cz = third * (rest[PhotonMesh.geometryOffset(a) + 2] + rest[PhotonMesh.geometryOffset(b) + 2]
                + rest[PhotonMesh.geometryOffset(c) + 2]);
        deformer.deformPoint(skin, a, b, c, third, third, third, cx, cy, cz, point);

        // the expected value: average of the three corners' skinning, each applied to the CENTROID
        var perCorner = new float[3];
        float ex = 0, ey = 0, ez = 0;
        for (int corner : new int[]{a, b, c}) {
            deformer.deformPoint(skin, corner, corner, corner, 1f, 0f, 0f, cx, cy, cz, perCorner);
            ex += third * perCorner[0];
            ey += third * perCorner[1];
            ez += third * perCorner[2];
        }
        assertEquals(ex, point[0], 1e-4f, "centroid x");
        assertEquals(ey, point[1], 1e-4f, "centroid y");
        assertEquals(ez, point[2], 1e-4f, "centroid z");
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
