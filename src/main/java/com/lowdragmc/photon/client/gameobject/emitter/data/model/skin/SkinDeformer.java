package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * Linear blend skinning on the CPU: a clip and a time in, a {@link PhotonMesh} geometry stream out.
 *
 * <p>Two steps, and the split matters because they cost wildly different amounts. {@link #pose} walks the
 * joints — tens of them — and is where the animation is evaluated. {@link #deform} walks the vertices —
 * thousands — and does nothing but multiply. So an emitter that shares a pose with another emitter can
 * share the whole thing, and one that only changed its time still pays the vertex pass; there is no way
 * around the second, which is why the first is worth caching.</p>
 *
 * <p>⚠️ <b>Flat float arrays, deliberately.</b> Written with a JOML object per vertex this is several
 * milliseconds on a twenty-thousand-vertex model, most of it allocation; written like this it is a
 * fraction of one. The arithmetic is the same arithmetic.</p>
 *
 * <p>Not thread-safe: one deformer holds one set of scratch buffers and is meant to be owned by whatever
 * is animating.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SkinDeformer {

    private final Skeleton skeleton;
    /** Sampled pose, refilled from the rest pose on every {@link #pose}. */
    private final float[] trs;
    /** Joint-to-scene matrices, composed in one forward pass (parents precede children). */
    private final float[] world;
    /** {@code world * inverseBind} — what a vertex is actually multiplied by. */
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

    /**
     * Evaluate {@code clip} at {@code time} (seconds) into this deformer's joint matrices. A null clip
     * poses the skeleton at rest, which is what a model with no animation selected should look like —
     * not a heap at the origin.
     */
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
                // parent < joint by construction, so its world matrix is already final
                Skeleton.multiply(skinMatrices, at, world, parent * Skeleton.FLOATS_PER_MATRIX, world, at);
                System.arraycopy(skinMatrices, at, world, at, Skeleton.FLOATS_PER_MATRIX);
            }
            Skeleton.multiply(skinMatrices, at, world, at, inverseBind, at);
        }
    }

    /**
     * Skin {@code rest}'s geometry stream into {@code out} with the pose {@link #pose} left behind.
     *
     * @param rest the bind-pose mesh — read, never written
     * @param skin which joints move which vertex
     * @param out  {@code rest.vertexCount() * }{@link PhotonMesh#FLOATS_PER_GEOMETRY} floats; a buffer to
     *             reuse across frames, since this is called once per pose
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

                // the 3x3 without the inverse transpose, which is what every engine ships: joints are
                // rigid in practice, and a non-uniformly scaled one would need a second matrix per joint
                // to fix a normal nobody is lighting that precisely
                mx += weight * (m0 * nx + m1 * ny + m2 * nz);
                my += weight * (m4 * nx + m5 * ny + m6 * nz);
                mz += weight * (m8 * nx + m9 * ny + m10 * nz);
            }

            if (!skinned) {
                // rigid vertex: its node transform is already baked into the rest position
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
                // opposing influences cancelled, or the pose is degenerate; anything unit will do and a
                // NaN here would reach the vertex buffer
                out[g + 3] = nx;
                out[g + 4] = ny;
                out[g + 5] = nz;
            }
        }
    }

    /**
     * Skin one point rather than a whole mesh — what an emission shape needs.
     *
     * <p>⭐ A shape samples a position on the surface and nothing else, so it can sample the <b>rest</b>
     * pose, which never changes and therefore needs no per-frame rebuild of anything, and then carry just
     * that one point through the deformation. O(1) a particle instead of O(vertices) a frame.</p>
     *
     * <p>The influences are the barycentric blend of the triangle's three corners', which is what the
     * deformation would have produced at that point anyway. ⚠️ The one approximation is the weighting of
     * the sampling itself: triangles are picked by their <b>rest</b> area, so a stretched limb gets the
     * particles its unstretched self would have. Unity's skinned-mesh sampling has the same bias.</p>
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
