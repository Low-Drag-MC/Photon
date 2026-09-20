package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A joint hierarchy with its rest pose and its inverse bind matrices — glTF's {@code skin}, flattened.
 *
 * <h2>Flat arrays, and 3x4 instead of 4x4</h2>
 *
 * <p>Everything here is {@code float[]} indexed by joint rather than an object per joint, and every
 * matrix is the <b>affine 3x4</b> (row-major, the bottom row implicitly {@code 0 0 0 1}) rather than a
 * full 4x4. Both choices are about the deformation: it runs once a frame over every vertex, and a
 * JOML object per joint per frame plus twelve multiplies that are known to produce {@code 0 0 0 1} is
 * the difference between a deformation that costs a fraction of a millisecond and one that shows up in
 * the frame time. A skeleton is built once and read constantly.</p>
 *
 * <p>⚠️ <b>Parents precede children.</b> {@link #parent(int)} is guaranteed to be smaller than the joint
 * itself, which is what lets {@link SkinDeformer} compose world matrices in one forward pass instead of
 * recursing. The builder enforces it by construction — glTF does not require it of a file, so the
 * importer has to topologically sort.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class Skeleton {

    /** Floats per joint in the rest pose: translation xyz, rotation xyzw, scale xyz. */
    public static final int FLOATS_PER_TRS = 10;
    /** Floats per affine matrix: three rows of four. */
    public static final int FLOATS_PER_MATRIX = 12;

    private final int[] parents;
    private final float[] restTrs;
    private final float[] inverseBind;
    private final String[] names;

    Skeleton(int[] parents, float[] restTrs, float[] inverseBind, String[] names) {
        this.parents = parents;
        this.restTrs = restTrs;
        this.inverseBind = inverseBind;
        this.names = names;
    }

    public int jointCount() {
        return parents.length;
    }

    /** The joint's parent, or {@code -1} for a root. Always less than {@code joint}. */
    public int parent(int joint) {
        return parents[joint];
    }

    public String name(int joint) {
        return names[joint];
    }

    /** The rest pose, {@link #FLOATS_PER_TRS} floats per joint. Animation channels override into a copy. */
    public float[] restTrs() {
        return restTrs;
    }

    /** Inverse bind matrices, {@link #FLOATS_PER_MATRIX} floats per joint, already affine row-major. */
    public float[] inverseBind() {
        return inverseBind;
    }

    // ---- affine 3x4 helpers, shared with SkinDeformer -------------------------------------------

    /** {@code out[outOff..] = a[aOff..] * b[bOff..]}, all affine 3x4. Aliasing-safe against neither input. */
    public static void multiply(float[] out, int outOff, float[] a, int aOff, float[] b, int bOff) {
        for (int r = 0; r < 3; r++) {
            int ar = aOff + r * 4;
            float a0 = a[ar], a1 = a[ar + 1], a2 = a[ar + 2], a3 = a[ar + 3];
            int or = outOff + r * 4;
            out[or] = a0 * b[bOff] + a1 * b[bOff + 4] + a2 * b[bOff + 8];
            out[or + 1] = a0 * b[bOff + 1] + a1 * b[bOff + 5] + a2 * b[bOff + 9];
            out[or + 2] = a0 * b[bOff + 2] + a1 * b[bOff + 6] + a2 * b[bOff + 10];
            // the translation column picks up a's own translation because b's implicit bottom row is 0 0 0 1
            out[or + 3] = a0 * b[bOff + 3] + a1 * b[bOff + 7] + a2 * b[bOff + 11] + a3;
        }
    }

    /**
     * A joint's local matrix from its TRS, composed {@code T * R * S} — glTF's order, and the only one
     * that makes a non-uniform scale on a parent behave the way an exporter meant it to.
     */
    public static void fromTrs(float[] out, int outOff, float[] trs, int trsOff) {
        float tx = trs[trsOff], ty = trs[trsOff + 1], tz = trs[trsOff + 2];
        float qx = trs[trsOff + 3], qy = trs[trsOff + 4], qz = trs[trsOff + 5], qw = trs[trsOff + 6];
        float sx = trs[trsOff + 7], sy = trs[trsOff + 8], sz = trs[trsOff + 9];

        // quaternion -> rotation matrix (assumes unit; the importer normalizes, and so does slerp)
        float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx = qw * qx, wy = qw * qy, wz = qw * qz;

        out[outOff] = (1 - 2 * (yy + zz)) * sx;
        out[outOff + 1] = (2 * (xy - wz)) * sy;
        out[outOff + 2] = (2 * (xz + wy)) * sz;
        out[outOff + 3] = tx;

        out[outOff + 4] = (2 * (xy + wz)) * sx;
        out[outOff + 5] = (1 - 2 * (xx + zz)) * sy;
        out[outOff + 6] = (2 * (yz - wx)) * sz;
        out[outOff + 7] = ty;

        out[outOff + 8] = (2 * (xz - wy)) * sx;
        out[outOff + 9] = (2 * (yz + wx)) * sy;
        out[outOff + 10] = (1 - 2 * (xx + yy)) * sz;
        out[outOff + 11] = tz;
    }

    /** The identity, for a joint with no parent chain to compose into. */
    public static void identity(float[] out, int outOff) {
        for (int i = 0; i < FLOATS_PER_MATRIX; i++) {
            out[outOff + i] = 0f;
        }
        out[outOff] = 1f;
        out[outOff + 5] = 1f;
        out[outOff + 10] = 1f;
    }

    /**
     * glTF stores a 4x4 <b>column-major</b>; this is the affine 3x4 row-major of the same transform.
     * Reading the two conventions into each other is the classic way to get a model that is almost but
     * not quite right, so the conversion lives in one place.
     */
    public static void fromColumnMajor4x4(float[] out, int outOff, float[] columnMajor, int inOff) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                out[outOff + r * 4 + c] = columnMajor[inOff + c * 4 + r];
            }
        }
    }

    /**
     * Collects joints in any order and hands back a skeleton whose parents precede their children.
     *
     * <p>Joints are added under a caller-chosen key (glTF: the node index) and referenced by the same
     * key, because a skin's joint list and an animation's channel targets name nodes, not slots.</p>
     */
    public static final class Builder {
        private final it.unimi.dsi.fastutil.ints.IntArrayList keys = new it.unimi.dsi.fastutil.ints.IntArrayList();
        private final it.unimi.dsi.fastutil.ints.IntArrayList parentKeys = new it.unimi.dsi.fastutil.ints.IntArrayList();
        private final it.unimi.dsi.fastutil.floats.FloatArrayList trs = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        private final it.unimi.dsi.fastutil.floats.FloatArrayList inverseBind = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        private final java.util.List<String> names = new java.util.ArrayList<>();
        private final it.unimi.dsi.fastutil.ints.Int2IntMap slotOfKey = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

        public Builder() {
            slotOfKey.defaultReturnValue(-1);
        }

        /** Whether this key was already added, so a joint shared by two skins is not duplicated. */
        public boolean has(int key) {
            return slotOfKey.containsKey(key);
        }

        /**
         * @param key       the caller's identity for this joint (a glTF node index)
         * @param parentKey the parent's key, or {@code -1}
         * @param trs       10 floats: translation, rotation (xyzw), scale
         */
        public void joint(int key, int parentKey, String name, float[] trs, int trsOff) {
            if (slotOfKey.containsKey(key)) return;
            slotOfKey.put(key, keys.size());
            keys.add(key);
            parentKeys.add(parentKey);
            names.add(name);
            this.trs.addElements(this.trs.size(), trs, trsOff, FLOATS_PER_TRS);
            // identity until setInverseBind; a joint no skin lists still needs a slot to animate through
            for (int i = 0; i < FLOATS_PER_MATRIX; i++) {
                inverseBind.add(i == 0 || i == 5 || i == 10 ? 1f : 0f);
            }
        }

        /** The slot a key was assigned, or {@code -1}. */
        public int slot(int key) {
            return slotOfKey.get(key);
        }

        public void inverseBind(int key, float[] affine3x4, int off) {
            int slot = slotOfKey.get(key);
            if (slot < 0) return;
            for (int i = 0; i < FLOATS_PER_MATRIX; i++) {
                inverseBind.set(slot * FLOATS_PER_MATRIX + i, affine3x4[off + i]);
            }
        }

        public int jointCount() {
            return keys.size();
        }

        /**
         * Sorts so every parent precedes its children and returns the skeleton, plus the mapping from the
         * builder's slots to the sorted ones — the per-vertex joint indices were recorded against the
         * former and have to be rewritten.
         *
         * @return {@code remap[builderSlot] = sortedSlot}
         */
        public int[] sortInto(Skeleton[] out) {
            int n = keys.size();
            int[] remap = new int[n];
            java.util.Arrays.fill(remap, -1);
            int[] order = new int[n];
            int written = 0;
            // repeatedly take every joint whose parent is already placed: a cycle (illegal glTF) leaves
            // the rest unplaced, and they are appended as roots rather than dropped
            boolean progress = true;
            while (written < n && progress) {
                progress = false;
                for (int slot = 0; slot < n; slot++) {
                    if (remap[slot] >= 0) continue;
                    int parentKey = parentKeys.getInt(slot);
                    int parentSlot = parentKey < 0 ? -1 : slotOfKey.get(parentKey);
                    if (parentSlot >= 0 && remap[parentSlot] < 0) continue; // parent not placed yet
                    order[written] = slot;
                    remap[slot] = written++;
                    progress = true;
                }
            }
            for (int slot = 0; slot < n; slot++) {
                if (remap[slot] < 0) {
                    order[written] = slot;
                    remap[slot] = written++;
                }
            }

            int[] parents = new int[n];
            float[] restTrs = new float[n * FLOATS_PER_TRS];
            float[] invBind = new float[n * FLOATS_PER_MATRIX];
            String[] jointNames = new String[n];
            for (int sorted = 0; sorted < n; sorted++) {
                int slot = order[sorted];
                int parentKey = parentKeys.getInt(slot);
                int parentSlot = parentKey < 0 ? -1 : slotOfKey.get(parentKey);
                parents[sorted] = parentSlot < 0 ? -1 : remap[parentSlot];
                jointNames[sorted] = names.get(slot);
                System.arraycopy(trs.elements(), slot * FLOATS_PER_TRS, restTrs,
                        sorted * FLOATS_PER_TRS, FLOATS_PER_TRS);
                System.arraycopy(inverseBind.elements(), slot * FLOATS_PER_MATRIX, invBind,
                        sorted * FLOATS_PER_MATRIX, FLOATS_PER_MATRIX);
            }
            out[0] = new Skeleton(parents, restTrs, invBind, jointNames);
            return remap;
        }
    }
}
