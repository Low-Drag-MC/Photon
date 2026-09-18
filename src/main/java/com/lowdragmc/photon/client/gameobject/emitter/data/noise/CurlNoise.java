package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;

/**
 * Stateless curl of a Perlin vector potential. Derivatives are analytic, so sampling needs no
 * finite-difference epsilon and is safe on parallel particle workers. This implements the public
 * Unity noise model, not Unity's private permutation tables or quality-specific channel mixing.
 */
public final class CurlNoise {
    public enum Quality { Low, Medium, High }

    private CurlNoise() {
    }

    public static Vector3f sample(double x, double y, double z, double scroll, int seed,
                                  float frequency, boolean damping, int octaves,
                                  float octaveMultiplier, float octaveScale, Quality quality,
                                  Vector3f result) {
        result.zero();
        if (!Float.isFinite(frequency) || frequency <= 0) return result;
        var gradient = new Vector3f();
        double scale = frequency;
        float amplitude = 0.25f * dampingScale(frequency, damping);
        int layers = Math.clamp(octaves, 1, 4);
        float persistence = Math.clamp(octaveMultiplier, 0f, 1f);
        float lacunarity = Math.clamp(octaveScale, 1f, 16f);
        for (int layer = 0; layer < layers; layer++) {
            // Scroll is a translation in noise coordinates, independent of particle lifetime.
            double layerScroll = scroll * (scale / frequency);
            double px = x * scale + layerScroll;
            double py = y * scale + layerScroll * 0.754877666;
            double pz = z * scale + layerScroll * 0.569840296;
            int layerSeed = seed + layer * 0x9e3779b9;
            if (quality == Quality.Low) {
                // A=(n(y), n(z), n(x)). Reusing a single 1D projection for all three
                // potentials collapses every velocity onto one fixed line, despite having zero
                // divergence. Three inexpensive 1D potentials keep directions spatially varied.
                result.add(-gradient1D(pz, layerSeed ^ 0x68bc21eb) * amplitude,
                        -gradient1D(px, layerSeed ^ 0x02e5be93) * amplitude,
                        -gradient1D(py, layerSeed) * amplitude);
                scale *= lacunarity;
                amplitude *= persistence;
                continue;
            }
            gradient(px, py, pz, layerSeed, quality == Quality.Medium, gradient);
            float ax = gradient.x, ay = gradient.y, az = gradient.z;
            float bx, bz;
            if (quality == Quality.Medium) {
                gradient(py, pz, 0, layerSeed ^ 0x68bc21eb, true, gradient);
                bx = 0;
                bz = gradient.y;
            } else {
                gradient(px, py, pz, layerSeed ^ 0x68bc21eb, false, gradient);
                bx = gradient.x;
                bz = gradient.z;
            }
            float cx = ax, cy = ay;
            if (quality == Quality.High) {
                gradient(px, py, pz, layerSeed ^ 0x02e5be93, false, gradient);
                cx = gradient.x;
                cy = gradient.y;
            }
            // curl(A) = (dAz/dy - dAy/dz, dAx/dz - dAz/dx, dAy/dx - dAx/dy).
            // Medium reuses potential channels; never normalize the curl (loses divergence-free flow).
            result.add((cy - bz) * amplitude, (az - cx) * amplitude, (bx - ay) * amplitude);
            scale *= lacunarity;
            amplitude *= persistence;
        }
        return result;
    }

    public static float dampingScale(float frequency, boolean damping) {
        return damping ? 1f / Math.max(frequency, 0.0001f) : 1f;
    }

    private static int hash(int x, int y, int z, int seed) {
        int h = seed ^ x * 0x8da6b343 ^ y * 0xd8163841 ^ z * 0xcb1ab31f;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        return h ^ h >>> 16;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double fadeDerivative(double t) {
        return 30 * t * t * (t - 1) * (t - 1);
    }

    private static float gradient1D(double x, int seed) {
        x -= Math.floor(x / 1048576) * 1048576;
        int ix = (int) Math.floor(x);
        double t = x - ix, u = fade(t), du = fadeDerivative(t);
        double a = (hash(ix, 0, 0, seed) & 1) == 0 ? 1 : -1;
        double b = (hash((ix + 1) & 0xfffff, 0, 0, seed) & 1) == 0 ? 1 : -1;
        return (float) ((1 - u) * a + u * b + du * (b * (t - 1) - a * t));
    }

    /** Gradient of improved Perlin noise, including the derivatives of the interpolation weights. */
    private static void gradient(double x, double y, double z, int seed, boolean twoDimensions, Vector3f result) {
        if (twoDimensions) z = 0;
        // Periodic reduction avoids integer overflow far from the origin and after long scrolls.
        x -= Math.floor(x / 1048576) * 1048576;
        y -= Math.floor(y / 1048576) * 1048576;
        z -= Math.floor(z / 1048576) * 1048576;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double ux = fade(fx), uy = fade(fy), uz = fade(fz);
        double dx = fadeDerivative(fx), dy = fadeDerivative(fy), dz = fadeDerivative(fz);
        double rx = 0, ry = 0, rz = 0;
        for (int i = 0; i < 2; i++) {
            double wx = i == 0 ? 1 - ux : ux;
            double dwx = i == 0 ? -dx : dx;
            for (int j = 0; j < 2; j++) {
                double wy = j == 0 ? 1 - uy : uy;
                double dwy = j == 0 ? -dy : dy;
                for (int k = 0; k < (twoDimensions ? 1 : 2); k++) {
                    double wz = twoDimensions ? 1 : k == 0 ? 1 - uz : uz;
                    double dwz = twoDimensions ? 0 : k == 0 ? -dz : dz;
                    int h = hash((ix + i) & 0xfffff, (iy + j) & 0xfffff, (iz + k) & 0xfffff, seed) & 15;
                    int u = h < 8 ? 0 : 1;
                    int v = h < 4 ? 1 : h == 12 || h == 14 ? 0 : 2;
                    double gu = (h & 1) == 0 ? 1 : -1;
                    double gv = (h & 2) == 0 ? 1 : -1;
                    double gx = (u == 0 ? gu : 0) + (v == 0 ? gv : 0);
                    double gy = (u == 1 ? gu : 0) + (v == 1 ? gv : 0);
                    double gz = !twoDimensions && v == 2 ? gv : 0;
                    double dot = gx * (fx - i) + gy * (fy - j) + gz * (fz - k);
                    rx += dwx * wy * wz * dot + wx * wy * wz * gx;
                    ry += wx * dwy * wz * dot + wx * wy * wz * gy;
                    rz += wx * wy * dwz * dot + wx * wy * wz * gz;
                }
            }
        }
        result.set((float) rx, (float) ry, (float) rz);
    }
}
