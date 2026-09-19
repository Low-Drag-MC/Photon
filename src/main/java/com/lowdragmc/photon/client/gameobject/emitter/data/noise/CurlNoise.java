package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;

/** Spatial curl noise calibrated against Unity 2022.3 particle simulation. */
public final class CurlNoise {
    public enum Quality { Low, Medium, High }

    private CurlNoise() {
    }

    /** Unity's particle noise uses an emitter-wide Xorshift128 phase, independent of particle seeds. */
    public static Vector3f seedOffset(int seed, Vector3f result) {
        int x = seed;
        int y = 1812433253 * x + 1;
        int z = 1812433253 * y + 1;
        int w = 1812433253 * z + 1;
        for (int axis = 0; axis < 3; axis++) {
            int t = x ^ (x << 11);
            int next = w ^ (w >>> 19) ^ t ^ (t >>> 8);
            result.setComponent(axis, (next & 0x7fffff) * (1f / 0x7fffff) * 100);
            x = y;
            y = z;
            z = w;
            w = next;
        }
        return result;
    }

    /** Unity's default maximum particle timestep is 0.03 seconds; Photon dt is measured in ticks. */
    public static int substepCount(float dt) {
        return Math.max(1, (int) Math.ceil(dt / 0.6f));
    }

    public static Vector3f sample(double x, double y, double z, double scroll, int seed,
                                  float frequency, boolean damping, int octaves,
                                  float octaveMultiplier, float octaveScale, Quality quality,
                                  Vector3f result) {
        return sample(x, y, z, scroll, seedOffset(seed, new Vector3f()), frequency, damping,
                octaves, octaveMultiplier, octaveScale, quality, result);
    }

    public static Vector3f sample(double x, double y, double z, double scroll, Vector3f offset,
                                  float frequency, boolean damping, int octaves,
                                  float octaveMultiplier, float octaveScale, Quality quality,
                                  Vector3f result) {
        result.zero();
        if (!Float.isFinite(frequency) || frequency < 0) return result;
        // Keep the same float coordinate rounding as the particle simulator. Do not hash a new
        // permutation per octave: each octave scales the same seeded spatial field.
        float px = (float) x + offset.x;
        float py = (float) y + offset.y;
        float pz = (float) z + offset.z;
        float scale = Math.max(frequency, 0.0001f);
        float amplitude = 1, weightSum = 0;
        float persistence = Math.clamp(octaveMultiplier, 0, 1);
        float lacunarity = Math.clamp(octaveScale, 1, 16);
        var a = new Vector3f();
        var b = new Vector3f();
        var c = new Vector3f();
        for (int layer = 0; layer < Math.clamp(octaves, 1, 4); layer++) {
            float sx = (px + 100) * scale, sy = py * scale, sz = pz * scale;
            float scrollX = (px + 100 + (float) scroll) * scale;
            float scrollY = (py + (float) scroll) * scale;
            float scrollZ = (pz + (float) scroll) * scale;
            if (quality == Quality.Low) {
                PerlinNoiseDerivative.sample(scrollY, 0, 0, quality, a);
                PerlinNoiseDerivative.sample(scrollZ, 0, 0, quality, b);
                PerlinNoiseDerivative.sample(scrollX, 0, 0, quality, c);
                result.add(a.x * scale * amplitude, b.x * scale * amplitude, c.x * scale * amplitude);
            } else {
                if (quality == Quality.Medium) {
                    PerlinNoiseDerivative.sample(sz, scrollY, 0, quality, a);
                    PerlinNoiseDerivative.sample(sx, scrollZ, 0, quality, b);
                    PerlinNoiseDerivative.sample(sy, scrollX, 0, quality, c);
                } else {
                    PerlinNoiseDerivative.sample(sz, sy, (px + (float) scroll) * scale, quality, a);
                    PerlinNoiseDerivative.sample(sx, sz, scrollY, quality, b);
                    PerlinNoiseDerivative.sample(sy, sx, scrollZ, quality, c);
                }
                // The potential channels permute coordinates; differentiate in those coordinates.
                result.add((c.x - b.y) * scale * amplitude,
                        (a.x - c.y) * scale * amplitude,
                        (b.x - a.y) * scale * amplitude);
            }
            weightSum += amplitude;
            amplitude *= persistence;
            scale *= lacunarity;
        }
        return result.mul(dampingScale(frequency, damping) / weightSum);
    }

    public static float dampingScale(float frequency, boolean damping) {
        return damping ? 1f / Math.max(frequency, 0.0001f) : 1f;
    }
}
