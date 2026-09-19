package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;

/** The inspector displays dNoise/dx on a five-unit XY slice, not a component of curl(A). */
public final class NoisePreviewSampler {
    private static final double PREVIEW_EXTENT = 5;

    private NoisePreviewSampler() {
    }

    public static float sample(double u, double v, double scroll, float frequency, boolean damping,
                               int octaves, float multiplier, float octaveScale, CurlNoise.Quality quality,
                               Vector3f scratch) {
        if (!Float.isFinite(frequency)) return 0;
        double scale = Math.max(0, frequency);
        float amplitude = damping ? 1 / Math.max(frequency, 0.0001f) : 1;
        float value = 0;
        float weight = 1, weightSum = 0, derivativeScale = 1;
        for (int layer = 0; layer < Math.clamp(octaves, 1, 4); layer++) {
            // Scroll is the integral of speed in seconds, in normalized preview coordinates.
            // Unity scrolls the last available dimension: X for 1D, Y for 2D, Z for 3D.
            // In particular, High evolves the slice without also translating it in XY.
            double layerScroll = scroll * PREVIEW_EXTENT * scale;
            double x = u * PREVIEW_EXTENT * scale, y = v * PREVIEW_EXTENT * scale, z = 0;
            switch (quality) {
                case Low -> x += layerScroll;
                case Medium -> y += layerScroll;
                case High -> z = layerScroll;
            }
            value += PerlinNoiseDerivative.sample(x, y, z, quality, scratch).x * amplitude * weight * derivativeScale;
            weightSum += weight;
            scale *= Math.clamp(octaveScale, 1, 16);
            derivativeScale *= Math.clamp(octaveScale, 1, 16);
            weight *= Math.clamp(multiplier, 0, 1);
        }
        return value / weightSum;
    }
}
