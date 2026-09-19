package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CurlNoiseTest {
    private static Vector3f sample(double x, double y, double z, double scroll, int seed,
                                   float frequency, boolean damping, int octaves, float persistence,
                                   CurlNoise.Quality quality) {
        return CurlNoise.sample(x, y, z, scroll, seed, frequency, damping, octaves, persistence,
                2, quality, new Vector3f());
    }

    private static Vector3f field(double x, double y, double z, CurlNoise.Quality quality) {
        return sample(x, y, z, 0.37, 127, 0.7f, false, 3, 0.5f, quality);
    }

    @Test
    void curlHasNegligibleDivergenceAtEveryQuality() {
        double epsilon = 0.003;
        for (var quality : CurlNoise.Quality.values()) {
            for (int i = 0; i < 16; i++) {
                double x = -1.37 + i * 0.219, y = 0.57 + i * 0.31, z = -0.83 + i * 0.17;
                double divergence = (field(x + epsilon, y, z, quality).x - field(x - epsilon, y, z, quality).x
                        + field(x, y + epsilon, z, quality).y - field(x, y - epsilon, z, quality).y
                        + field(x, y, z + epsilon, quality).z - field(x, y, z - epsilon, quality).z) / (2 * epsilon);
                // Seeded coordinates are rounded to float like Unity, so finite differences
                // must tolerate that quantization as well as derivative truncation error.
                assertEquals(0, divergence, 0.02, quality + " at " + i);
            }
        }
    }

    @Test
    void parallelSamplingMatchesSerialSampling() {
        var expected = IntStream.range(0, 128).mapToObj(i -> field(i * 0.017, 0.31, -0.87, CurlNoise.Quality.High)).toList();
        var actual = IntStream.range(0, 128).parallel().mapToObj(i -> field(i * 0.017, 0.31, -0.87, CurlNoise.Quality.High)).toList();
        assertEquals(expected, actual);
    }

    @Test
    void positionSeedAndScrollAllAffectTheField() {
        var original = sample(0.3, -0.6, 1.2, 0, 19, 1, false, 1, 0.5f, CurlNoise.Quality.High);
        assertNotEquals(original, sample(0.5, -0.6, 1.2, 0, 19, 1, false, 1, 0.5f, CurlNoise.Quality.High));
        assertNotEquals(original, sample(0.3, -0.6, 1.2, 0.4, 19, 1, false, 1, 0.5f, CurlNoise.Quality.High));
        assertNotEquals(original, sample(0.3, -0.6, 1.2, 0, 20, 1, false, 1, 0.5f, CurlNoise.Quality.High));
    }

    @Test
    void dampingCompensatesDerivativeFrequencyAtTheSamePosition() {
        var raw = sample(0.3, -0.6, 1.2, 0, 19, 2, false, 3, 0.5f, CurlNoise.Quality.High);
        var damped = sample(0.3, -0.6, 1.2, 0, 19, 2, true, 3, 0.5f, CurlNoise.Quality.High);
        assertTrue(raw.equals(damped.mul(2), 1e-6f));
    }

    @Test
    void zeroOctaveMultiplierReducesToSingleLayer() {
        var single = sample(0.3, -0.6, 1.2, 0.7, 19, 1, false, 1, 0, CurlNoise.Quality.High);
        var multiple = sample(0.3, -0.6, 1.2, 0.7, 19, 1, false, 4, 0, CurlNoise.Quality.High);
        assertEquals(single, multiple);
        assertNotEquals(single, sample(0.3, -0.6, 1.2, 0.7, 19, 1, false, 4, 0.5f, CurlNoise.Quality.High));
    }

    @Test
    void fieldIsContinuousAcrossPositiveNegativeAndPeriodicCellBoundaries() {
        for (double boundary : new double[]{-1, 0, 1, 1048576}) {
            var left = sample(boundary - 1e-6, 0.37, 0.19, 0, 83, 1, false, 1, 0.5f, CurlNoise.Quality.High);
            var right = sample(boundary + 1e-6, 0.37, 0.19, 0, 83, 1, false, 1, 0.5f, CurlNoise.Quality.High);
            assertTrue(left.distance(right) < 0.0001f, "boundary " + boundary);
        }
    }

    @Test
    void zeroAndVerySmallFrequencyRemainFinite() {
        var zero = sample(0.3, -0.6, 1.2, 0.7, 19, 0, true, 4, 0.5f, CurlNoise.Quality.High);
        assertTrue(zero.isFinite());
        assertTrue(sample(0.3, -0.6, 1.2, 0.7, 19, 1e-12f, true, 4, 0.5f, CurlNoise.Quality.High).isFinite());
    }

    @Test
    void lowQualityProducesDifferentDirectionsAcrossSpace() {
        var reference = field(0.31, 0.57, -0.83, CurlNoise.Quality.Low);
        boolean turns = IntStream.range(0, 20).anyMatch(i ->
                new Vector3f(reference).cross(field(i * 0.17, 0.37, i * -0.23, CurlNoise.Quality.Low)).lengthSquared() > 0.001f);
        assertTrue(turns, "A divergence-free field must not collapse to one fixed direction");
    }

    @Test
    void lowQualityParticleTurnsEvenWithoutScrollingOrOtherVelocity() {
        var position = new Vector3f(0.31f, 0.57f, -0.83f);
        var first = sample(position.x, position.y, position.z, 0, 127, 1, false, 1, 0.5f, CurlNoise.Quality.Low);
        boolean turned = false;
        for (int i = 0; i < 200; i++) {
            var velocity = sample(position.x, position.y, position.z, 0, 127, 1, false, 1, 0.5f, CurlNoise.Quality.Low);
            turned |= new Vector3f(first).cross(velocity).lengthSquared() > 0.001f;
            position.fma(0.05f, velocity);
        }
        assertTrue(turned, "The old shared 1D projection produced a permanently straight trajectory");
    }
}
