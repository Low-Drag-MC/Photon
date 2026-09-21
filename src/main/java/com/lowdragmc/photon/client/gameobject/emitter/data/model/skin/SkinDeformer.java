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
        deform(rest, skin, out, null, null);
    }

    /**
     * As {@link #deform(PhotonMesh, MeshSkin, float[])}, also carrying the tangent frame through the
     * same blend — deriving it from the deformed UVs instead costs a weld-map rebuild per pose.
     *
     * @param restTangents the rest pose's {@code tx,ty,tz,w}, or null to skip tangents entirely
     * @param outTangents  where to write them; {@code rest.vertexCount() * }{@link PhotonMesh#FLOATS_PER_TANGENT}
     */
    public void deform(PhotonMesh rest, MeshSkin skin, float[] out,
                       @Nullable float[] restTangents, @Nullable float[] outTangents) {
        var source = rest.geometry();
        var joints = skin.joints();
        var weights = skin.weights();
        int vertexCount = Math.min(rest.vertexCount(), skin.vertexCount());
        int jointCount = skeleton.jointCount();
        int tangentFloats = vertexCount * PhotonMesh.FLOATS_PER_TANGENT;
        boolean withTangents = restTangents != null && outTangents != null
                && restTangents.length >= tangentFloats && outTangents.length >= tangentFloats;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int g = vertex * PhotonMesh.FLOATS_PER_GEOMETRY;
            int t = vertex * PhotonMesh.FLOATS_PER_TANGENT;
            int w = vertex * MeshSkin.INFLUENCES;
            float px = source[g], py = source[g + 1], pz = source[g + 2];
            float nx = source[g + 3], ny = source[g + 4], nz = source[g + 5];
            float ax = 0f, ay = 0f, az = 0f;
            if (withTangents) {
                ax = restTangents[t];
                ay = restTangents[t + 1];
                az = restTangents[t + 2];
                outTangents[t + 3] = restTangents[t + 3]; // handedness is not something a pose changes
            }

            float ox = 0f, oy = 0f, oz = 0f;
            float mx = 0f, my = 0f, mz = 0f;
            float sx = 0f, sy = 0f, sz = 0f;
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

                if (withTangents) {
                    sx += weight * (m0 * ax + m1 * ay + m2 * az);
                    sy += weight * (m4 * ax + m5 * ay + m6 * az);
                    sz += weight * (m8 * ax + m9 * ay + m10 * az);
                }
            }

            if (!skinned) {
                out[g] = px;
                out[g + 1] = py;
                out[g + 2] = pz;
                out[g + 3] = nx;
                out[g + 4] = ny;
                out[g + 5] = nz;
                if (withTangents) {
                    outTangents[t] = ax;
                    outTangents[t + 1] = ay;
                    outTangents[t + 2] = az;
                }
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

            if (withTangents) {
                float tlen2 = sx * sx + sy * sy + sz * sz;
                if (tlen2 > 1.0e-12f && Float.isFinite(tlen2)) {
                    float inv = 1f / (float) Math.sqrt(tlen2);
                    outTangents[t] = sx * inv;
                    outTangents[t + 1] = sy * inv;
                    outTangents[t + 2] = sz * inv;
                } else {
                    outTangents[t] = ax;
                    outTangents[t + 1] = ay;
                    outTangents[t + 2] = az;
                }
            }
        }
    }
}
