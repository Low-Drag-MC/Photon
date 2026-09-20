package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A joint hierarchy with its rest pose and inverse bind matrices — glTF's {@code skin}, flattened into
 * float arrays of affine 3x4 matrices (row-major, implicit bottom row).
 *
 * <p>⚠️ {@link #parent(int)} is always less than the joint itself, which is what lets
 * {@link SkinDeformer} compose world matrices in one forward pass. The builder sorts for it; glTF does
 * not require it of a file.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class Skeleton {

    /** translation xyz, rotation xyzw, scale xyz. */
    public static final int FLOATS_PER_TRS = 10;
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

    public float[] restTrs() {
        return restTrs;
    }

    public float[] inverseBind() {
        return inverseBind;
    }

    /** {@code out = a * b}, all affine 3x4. Not aliasing-safe. */
    public static void multiply(float[] out, int outOff, float[] a, int aOff, float[] b, int bOff) {
        for (int r = 0; r < 3; r++) {
            int ar = aOff + r * 4;
            float a0 = a[ar], a1 = a[ar + 1], a2 = a[ar + 2], a3 = a[ar + 3];
            int or = outOff + r * 4;
            out[or] = a0 * b[bOff] + a1 * b[bOff + 4] + a2 * b[bOff + 8];
            out[or + 1] = a0 * b[bOff + 1] + a1 * b[bOff + 5] + a2 * b[bOff + 9];
            out[or + 2] = a0 * b[bOff + 2] + a1 * b[bOff + 6] + a2 * b[bOff + 10];
            out[or + 3] = a0 * b[bOff + 3] + a1 * b[bOff + 7] + a2 * b[bOff + 11] + a3;
        }
    }

    /** A joint's local matrix, composed {@code T * R * S} — glTF's order. */
    public static void fromTrs(float[] out, int outOff, float[] trs, int trsOff) {
        float tx = trs[trsOff], ty = trs[trsOff + 1], tz = trs[trsOff + 2];
        float qx = trs[trsOff + 3], qy = trs[trsOff + 4], qz = trs[trsOff + 5], qw = trs[trsOff + 6];
        float sx = trs[trsOff + 7], sy = trs[trsOff + 8], sz = trs[trsOff + 9];

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

    public static void identity(float[] out, int outOff) {
        for (int i = 0; i < FLOATS_PER_MATRIX; i++) {
            out[outOff + i] = 0f;
        }
        out[outOff] = 1f;
        out[outOff + 5] = 1f;
        out[outOff + 10] = 1f;
    }

    /** glTF stores a 4x4 column-major; this is the same transform as an affine 3x4 row-major. */
    public static void fromColumnMajor4x4(float[] out, int outOff, float[] columnMajor, int inOff) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                out[outOff + r * 4 + c] = columnMajor[inOff + c * 4 + r];
            }
        }
    }

    /**
     * Collects joints in any order under a caller-chosen key (glTF: the node index), since a skin's
     * joint list and an animation's channels name nodes rather than slots.
     */
    public static final class Builder {
        private final IntArrayList keys = new IntArrayList();
        private final IntArrayList parentKeys = new IntArrayList();
        private final FloatArrayList trs = new FloatArrayList();
        private final FloatArrayList inverseBind = new FloatArrayList();
        private final List<String> names = new ArrayList<>();
        private final Int2IntMap slotOfKey = new Int2IntOpenHashMap();

        public Builder() {
            slotOfKey.defaultReturnValue(-1);
        }

        public boolean has(int key) {
            return slotOfKey.containsKey(key);
        }

        public void joint(int key, int parentKey, String name, float[] trs, int trsOff) {
            if (slotOfKey.containsKey(key)) return;
            slotOfKey.put(key, keys.size());
            keys.add(key);
            parentKeys.add(parentKey);
            names.add(name);
            this.trs.addElements(this.trs.size(), trs, trsOff, FLOATS_PER_TRS);
            for (int i = 0; i < FLOATS_PER_MATRIX; i++) {
                inverseBind.add(i == 0 || i == 5 || i == 10 ? 1f : 0f);
            }
        }

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
         * Sorts so every parent precedes its children.
         *
         * @return {@code remap[builderSlot] = sortedSlot}, since per-vertex joint indices were recorded
         *         against the former
         */
        public int[] sortInto(Skeleton[] out) {
            int n = keys.size();
            int[] remap = new int[n];
            Arrays.fill(remap, -1);
            int[] order = new int[n];
            int written = 0;
            boolean progress = true;
            while (written < n && progress) {
                progress = false;
                for (int slot = 0; slot < n; slot++) {
                    if (remap[slot] >= 0) continue;
                    int parentKey = parentKeys.getInt(slot);
                    int parentSlot = parentKey < 0 ? -1 : slotOfKey.get(parentKey);
                    if (parentSlot >= 0 && remap[parentSlot] < 0) continue;
                    order[written] = slot;
                    remap[slot] = written++;
                    progress = true;
                }
            }
            // a cycle (illegal glTF) leaves joints unplaced; append them as roots rather than drop them
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
