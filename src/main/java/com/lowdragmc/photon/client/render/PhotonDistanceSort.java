package com.lowdragmc.photon.client.render;

import java.util.Arrays;

/**
 * Far-to-near index sort for Photon's translucent geometry — used by both sort paths: the CPU bake's
 * per-quad reorder ({@code Emitter.sortQuads}) and the instanced path's per-record permutation
 * ({@code Emitter.instanceDrawOrder}).
 * <p>
 * Replaces {@code VertexSorting.byDistance}, which sorts an {@code int[]} through a fastutil
 * {@code mergeSort} with a lambda comparator — measured 1.9–2.9x slower than sorting packed keys as
 * primitives, plus four throwaway arrays per call. Here the key and the index ride in one {@code long}
 * and the scratch arrays are reused.
 * <p>
 * <b>The ordering is identical to the stable {@code VertexSorting.byDistance}</b>, ties included:
 * {@code Arrays.sort} is not stable, but the complemented index in the low word makes the total order
 * unambiguous, and reading the result back-to-front yields key-descending with index-ASCENDING within
 * each group of equal keys — exactly what the stable mergeSort produced. {@code PhotonDistanceSortTest}
 * asserts that against the fastutil implementation over duplicate-heavy inputs.
 * <p>
 * <b>Keys must be non-negative</b> (they are squared distances). Raw float bits are monotonic as a
 * signed int only for non-negative values; {@code -0.0f} and the infinities/NaN still order the same
 * way {@code Float.compare} does, but a strictly negative key would sort backwards.
 * <p>
 * <b>Render thread only.</b> The scratch arrays are shared, like {@code Emitter}'s instance staging
 * buffers next door — a future parallel bake has to make this set per-worker along with those.
 * A returned order array is valid until the next {@link #farToNear} call.
 */
public final class PhotonDistanceSort {

    private static float[] keys = new float[0];
    private static long[] packed = new long[0];
    private static int[] order = new int[0];

    private PhotonDistanceSort() {
    }

    /**
     * Scratch to write the elements' SQUARED distances into, at indices {@code [0, count)}. Pair each
     * call with exactly one {@link #farToNear} before asking for keys again.
     */
    public static float[] keys(int count) {
        if (keys.length < count) {
            keys = new float[Math.max(count, keys.length * 2)];
        }
        return keys;
    }

    /**
     * The far-to-near order over the first {@code count} entries of {@link #keys(int)}: element
     * {@code order[i]} is the i-th to draw. The array may be LONGER than {@code count} (it is reused) —
     * read only {@code [0, count)}.
     */
    public static int[] farToNear(int count) {
        if (packed.length < count) {
            packed = new long[Math.max(count, packed.length * 2)];
        }
        var distances = keys;
        var sortable = packed;
        for (int i = 0; i < count; i++) {
            sortable[i] = ((long) Float.floatToRawIntBits(distances[i]) << 32) | (0xFFFFFFFFL - i);
        }
        Arrays.sort(sortable, 0, count);
        if (order.length < count) {
            order = new int[Math.max(count, order.length * 2)];
        }
        var result = order;
        for (int i = 0; i < count; i++) {
            result[i] = (int) (0xFFFFFFFFL - (sortable[count - 1 - i] & 0xFFFFFFFFL));
        }
        return result;
    }
}
