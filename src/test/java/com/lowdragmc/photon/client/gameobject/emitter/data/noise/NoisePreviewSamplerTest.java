package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class NoisePreviewSamplerTest {
    @Test
    void matchesUnity2022PreviewPixelsForAllQualitiesFrequenciesAndDamping() throws IOException {
        for (var quality : CurlNoise.Quality.values()) {
            compare(quality, 1, false, 1, quality + "-1-False");
            compare(quality, 0.5f, true, 1, quality + "-0.5-True");
            compare(quality, 2, true, 1, quality + "-2-True");
        }
    }

    @Test
    void matchesUnityNormalizedOctavesIncludingDerivativeFrequencyScale() throws IOException {
        for (var quality : CurlNoise.Quality.values()) {
            compare(quality, 1, false, 3, quality + "-octaves");
        }
    }

    @Test
    void matchesUnityScrollDirectionSpeedFrequencyAndOctaves() throws IOException {
        for (var quality : CurlNoise.Quality.values()) {
            compare(quality, 0.5f, false, 1, 0.375 * 0.25, "scroll-" + quality + "-f0.5-o1-s0.25");
            compare(quality, 1, false, 3, 0.375, "scroll-" + quality + "-f1-o3-s1");
            compare(quality, 2, false, 3, -0.375, "scroll-" + quality + "-f2-o3-s-1");
        }
    }

    @Test
    void matchesUnityAtSuccessiveScrollTimes() throws IOException {
        for (var quality : CurlNoise.Quality.values()) {
            for (var time : new String[]{"0.1", "0.25", "0.5", "1"}) {
                compare(quality, 1, false, 1, Double.parseDouble(time),
                        "scroll-t" + time + "-" + quality + "-f1-o1-s1");
            }
        }
    }

    private void compare(CurlNoise.Quality quality, float frequency, boolean damping, int octaves,
                         String fixture) throws IOException {
        compare(quality, frequency, damping, octaves, 0, fixture);
    }

    private void compare(CurlNoise.Quality quality, float frequency, boolean damping, int octaves,
                         double scroll, String fixture) throws IOException {
        byte[] expected;
        try (var stream = getClass().getResourceAsStream("/noise2-preview/" + fixture + ".raw")) {
            assertNotNull(stream, fixture);
            expected = stream.readAllBytes();
        }
        assertEquals(96 * 96, expected.length);
        var scratch = new Vector3f();
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                float value = NoisePreviewSampler.sample(x / 96d, y / 96d, scroll, frequency,
                        damping, octaves, 0.5f, 2, quality, scratch) * 0.1f;
                int pixel = Math.round(Math.clamp(value * 0.5f + 0.5f, 0, 1) * 255);
                assertEquals(Byte.toUnsignedInt(expected[y * 96 + x]), pixel, 1,
                        fixture + " pixel " + x + "," + y);
            }
        }
    }
}
