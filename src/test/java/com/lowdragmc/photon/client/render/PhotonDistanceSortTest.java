package com.lowdragmc.photon.client.render;

import it.unimi.dsi.fastutil.ints.IntArrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PhotonDistanceSort} exists to replace {@code VertexSorting.byDistance} without changing a
 * single index of the output — these tests are that guarantee. The reference below is exactly what
 * {@code VertexSorting.byDistance} does: a STABLE fastutil mergeSort over the index array with a
 * reversed {@code Float.compare}. Ties are the whole point: {@code Arrays.sort} isn't stable, so if the
 * complemented-index packing were wrong, equal distances would come out reversed — visible with
 * non-commutative blending, invisible to a test that only compares the sorted keys.
 */
public class PhotonDistanceSortTest {

    /** The exact ordering {@code VertexSorting.byDistance} produces for the given keys. */
    private static int[] reference(float[] keys, int count) {
        var indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = i;
        }
        IntArrays.mergeSort(indices, (a, b) -> Float.compare(keys[b], keys[a]));
        return indices;
    }

    private static int[] actual(float[] distances, int count) {
        var keys = PhotonDistanceSort.keys(count);
        System.arraycopy(distances, 0, keys, 0, count);
        var order = PhotonDistanceSort.farToNear(count);
        // the scratch array may be longer than count — only the prefix is meaningful
        var trimmed = new int[count];
        System.arraycopy(order, 0, trimmed, 0, count);
        return trimmed;
    }

    private static void assertMatchesReference(float[] keys) {
        assertArrayEquals(reference(keys, keys.length), actual(keys, keys.length));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64, 255, 256, 1000, 4096})
    @DisplayName("distinct distances: same order as the stable mergeSort")
    public void distinctKeys(int count) {
        var random = new Random(0xC0FFEE + count);
        var keys = new float[count];
        for (int i = 0; i < count; i++) {
            keys[i] = random.nextFloat() * 10_000f;
        }
        assertMatchesReference(keys);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 8, 100, 1000, 4096})
    @DisplayName("duplicate-heavy distances: ties keep emit order, like the stable sort")
    public void duplicateKeys(int count) {
        // only 5 distinct values -> long runs of ties, which is where an unstable sort diverges
        var random = new Random(0xBEEF + count);
        var buckets = new float[]{0f, 1.5f, 1.5f, 42f, 1000f};
        var keys = new float[count];
        for (int i = 0; i < count; i++) {
            keys[i] = buckets[random.nextInt(buckets.length)];
        }
        assertMatchesReference(keys);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 500})
    @DisplayName("all distances equal: order is the identity")
    public void allEqual(int count) {
        var keys = new float[count];
        java.util.Arrays.fill(keys, 7.25f);
        assertMatchesReference(keys);
        var identity = new int[count];
        for (int i = 0; i < count; i++) {
            identity[i] = i;
        }
        assertArrayEquals(identity, actual(keys, count));
    }

    @Test
    @DisplayName("zero, negative zero and the non-finites order like Float.compare")
    public void edgeValues() {
        // -0.0f has a negative int bit pattern, and Float.compare also puts it below +0.0 — the packing
        // stays consistent for every value a squared distance can produce (never strictly negative)
        assertMatchesReference(new float[]{0f, -0f, 0f, Float.MIN_VALUE, Float.MAX_VALUE, 1f});
        assertMatchesReference(new float[]{1f, Float.POSITIVE_INFINITY, 0f, Float.NaN, 5f, Float.NaN});
    }

    @Test
    @DisplayName("farthest first")
    public void farToNearDirection() {
        var order = actual(new float[]{1f, 100f, 10f}, 3);
        assertArrayEquals(new int[]{1, 2, 0}, order);
    }

    @Test
    @DisplayName("scratch arrays are reused and stay correct after shrinking")
    public void scratchReuse() {
        assertMatchesReference(new float[]{5f, 1f, 9f, 3f, 7f, 2f, 8f});
        // a smaller follow-up must not read stale entries from the larger previous run
        var order = actual(new float[]{2f, 1f}, 2);
        assertEquals(2, order.length);
        assertArrayEquals(new int[]{0, 1}, order);
    }
}
