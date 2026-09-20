package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * Linear blend skinning on the CPU: a clip and a time in, a geometry stream out.
 * Not thread-safe — one deformer owns one set of scratch buffers.
 */
@OnlyIn(Dist.CLIENT)
public final class SkinDeformer {

    private final Skeleton skeleton;
    private final float[] trs;
    private final float[] world;
    /** {@code world * inverseBind} — what a vertex is multiplied by. */
    private final float[] skinMatrices;

    public SkinDeformer(Skeleton skeleton) {
        this.skeleton = skeleton;
        int joints = skeleton.jointCount();
        this.trs = new float[joints * Skeleton.FLOATS_PER_TRS];
        this.world = new float[joints * Skeleton.FLOATS_PER_MATRIX];
        this.skinMatrices = new float[joints * Skeleton.FLOATS_PER_MATRIX];
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    /** Evaluate {@code clip} at {@code time} (seconds); a null clip poses the skeleton at rest. */
    public void pose(@Nullable AnimationClip clip, float time) {
        System.arraycopy(skeleton.restTrs(), 0, trs, 0, trs.length);
        if (clip != null) {
            clip.sample(time, trs);
        }
        var inverseBind = skeleton.inverseBind();
        int joints = skeleton.jointCount();
        for (int joint = 0; joint < joints; joint++) {
            int at = joint * Skeleton.FLOATS_PER_MATRIX;
            Skeleton.fromTrs(world, at, trs, joint * Skeleton.FLOATS_PER_TRS);
            int parent = skeleton.parent(joint);
            if (parent >= 0) {
                // parent < joint by construction, so its world matrix is already final; skinMatrices
                // doubles as scratch here and is overwritten immediately below
                Skeleton.multiply(skinMatrices, at, world, parent * Skeleton.FLOATS_PER_MATRIX, world, at);
                System.arraycopy(skinMatrices, at, world, at, Skeleton.FLOATS_PER_MATRIX);
            }
            Skeleton.multiply(skinMatrices, at, world, at, inverseBind, at);
        }
    }

    /**
     * Skin {@code rest}'s geometry into {@code out} with the pose {@link #pose} left behind.
     * {@code out} is {@code rest.vertexCount() * }{@link PhotonMesh#FLOATS_PER_GEOMETRY} floats.
     */
    public void deform(PhotonMesh rest, MeshSkin skin, float[] out) {
        var source = rest.geometry();
        var joints = skin.joints();
        var weights = skin.weights();
        int vertexCount = Math.min(rest.vertexCount(), skin.vertexCount());
        int jointCount = skeleton.jointCount();

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int g = vertex * PhotonMesh.FLOATS_PER_GEOMETRY;
            int w = vertex * MeshSkin.INFLUENCES;
            float px = source[g], py = source[g + 1], pz = source[g + 2];
            float nx = source[g + 3], ny = source[g + 4], nz = source[g + 5];

            float ox = 0f, oy = 0f, oz = 0f;
            float mx = 0f, my = 0f, mz = 0f;
            boolean skinned = false;
            for (int i = 0; i < MeshSkin.INFLUENCES; i++) {
                float weight = weights[w + i];
                if (weight == 0f) continue;
                int joint = joints[w + i];
                if (joint < 0 || joint >= jointCount) continue;
                skinned = true;
                int m = joint * Skeleton.FLOATS_PER_MATRIX;

                float m0 = skinMatrices[m], m1 = skinMatrices[m + 1], m2 = skinMatrices[m + 2], m3 = skinMatrices[m + 3];
                float m4 = skinMatrices[m + 4], m5 = skinMatrices[m + 5], m6 = skinMatrices[m + 6], m7 = skinMatrices[m + 7];
                float m8 = skinMatrices[m + 8], m9 = skinMatrices[m + 9], m10 = skinMatrices[m + 10], m11 = skinMatrices[m + 11];

                ox += weight * (m0 * px + m1 * py + m2 * pz + m3);
                oy += weight * (m4 * px + m5 * py + m6 * pz + m7);
                oz += weight * (m8 * px + m9 * py + m10 * pz + m11);

                // the 3x3 without the inverse transpose, which is what every engine ships
                mx += weight * (m0 * nx + m1 * ny + m2 * nz);
                my += weight * (m4 * nx + m5 * ny + m6 * nz);
                mz += weight * (m8 * nx + m9 * ny + m10 * nz);
            }

            if (!skinned) {
                out[g] = px;
                out[g + 1] = py;
                out[g + 2] = pz;
                out[g + 3] = nx;
                out[g + 4] = ny;
                out[g + 5] = nz;
                continue;
            }

            out[g] = ox;
            out[g + 1] = oy;
            out[g + 2] = oz;
            float len2 = mx * mx + my * my + mz * mz;
            if (len2 > 1.0e-12f && Float.isFinite(len2)) {
                float inv = 1f / (float) Math.sqrt(len2);
                out[g + 3] = mx * inv;
                out[g + 4] = my * inv;
                out[g + 5] = mz * inv;
            } else {
                out[g + 3] = nx;
                out[g + 4] = ny;
                out[g + 5] = nz;
            }
        }
    }

    /**
     * Skin one point rather than a whole mesh: sample the rest pose, then carry just that point through.
     * O(1) a particle instead of O(vertices) a frame.
     *
     * @param out written as {@code x, y, z}
     */
    public void deformPoint(MeshSkin skin, int a, int b, int c, float wa, float wb, float wc,
                            float px, float py, float pz, float[] out) {
        float ox = 0f, oy = 0f, oz = 0f;
        float total = 0f;
        var joints = skin.joints();
        var weights = skin.weights();
        int jointCount = skeleton.jointCount();
        int[] corners = {a, b, c};
        float[] barycentric = {wa, wb, wc};

        for (int corner = 0; corner < 3; corner++) {
            int vertex = corners[corner];
            if (vertex < 0 || vertex >= skin.vertexCount()) continue;
            float share = barycentric[corner];
            int w = vertex * MeshSkin.INFLUENCES;
            for (int i = 0; i < MeshSkin.INFLUENCES; i++) {
                float weight = weights[w + i] * share;
                if (weight == 0f) continue;
                int joint = joints[w + i];
                if (joint < 0 || joint >= jointCount) continue;
                int m = joint * Skeleton.FLOATS_PER_MATRIX;
                ox += weight * (skinMatrices[m] * px + skinMatrices[m + 1] * py + skinMatrices[m + 2] * pz + skinMatrices[m + 3]);
                oy += weight * (skinMatrices[m + 4] * px + skinMatrices[m + 5] * py + skinMatrices[m + 6] * pz + skinMatrices[m + 7]);
                oz += weight * (skinMatrices[m + 8] * px + skinMatrices[m + 9] * py + skinMatrices[m + 10] * pz + skinMatrices[m + 11]);
                total += weight;
            }
        }
        if (total == 0f) {
            out[0] = px;
            out[1] = py;
            out[2] = pz;
        } else {
            out[0] = ox;
            out[1] = oy;
            out[2] = oz;
        }
    }
}
