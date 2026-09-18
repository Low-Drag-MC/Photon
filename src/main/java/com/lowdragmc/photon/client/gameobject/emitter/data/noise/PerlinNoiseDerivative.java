package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;

/** Analytic derivatives of the 1D/2D/3D improved Perlin field used by the noise inspector. */
public final class PerlinNoiseDerivative {
    private static final int[] PERMUTATION = {
        151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
        140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
        247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
        57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
        74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
        60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
        65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
        200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
        52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
        207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
        119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
        129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
        218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
        81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
        184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
        222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
    };
    private static final float SQRT_TWO = (float) Math.sqrt(2);
    private static final float[] GRADIENT_X_2D = {SQRT_TWO, -SQRT_TWO, 0, 0, 1, -1, 1, -1};
    private static final float[] GRADIENT_Y_2D = {0, 0, SQRT_TWO, -SQRT_TWO, 1, 1, -1, -1};

    private PerlinNoiseDerivative() {
    }

    private static int perm(int index) {
        return PERMUTATION[index & 255];
    }

    private static final ThreadLocal<float[]> CORNERS = ThreadLocal.withInitial(() -> new float[32]);

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float fadeDerivative(float t) {
        return 30 * t * t * (t * (t - 2) + 1);
    }

    public static Vector3f sample(double x, double y, double z, CurlNoise.Quality quality, Vector3f result) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return result.zero();
        x -= Math.floor(x / 256) * 256;
        y -= Math.floor(y / 256) * 256;
        z -= Math.floor(z / 256) * 256;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float tx = (float) (x - ix), ty = (float) (y - iy), tz = (float) (z - iz);
        float ux = fade(tx), uy = fade(ty), uz = fade(tz);
        float dx = fadeDerivative(tx), dy = fadeDerivative(ty), dz = fadeDerivative(tz);
        if (quality == CurlNoise.Quality.Low) {
            float a = (perm(ix) & 1) == 0 ? 2 : -2;
            float b = (perm(ix + 1) & 1) == 0 ? 2 : -2;
            return result.set(a + dx * ((b - a) * tx - b) + ux * (b - a), 0, 0);
        }
        boolean twoDimensions = quality == CurlNoise.Quality.Medium;
        var corners = CORNERS.get();
        for (int k = 0; k < (twoDimensions ? 1 : 2); k++) {
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) {
                    int h = perm(perm(ix + i) + iy + j);
                    float gx, gy, gz;
                    if (twoDimensions) {
                        gx = GRADIENT_X_2D[h & 7];
                        gy = GRADIENT_Y_2D[h & 7];
                        gz = 0;
                    } else {
                        h = perm(h + iz + k) & 15;
                        if (h == 13) h = 14;
                        else if (h == 14) h = 13;
                        int u = h < 8 ? 0 : 1;
                        int v = h < 4 ? 1 : h == 12 || h == 14 ? 0 : 2;
                        float gu = (h & 1) == 0 ? 1 : -1, gv = (h & 2) == 0 ? 1 : -1;
                        gx = (u == 0 ? gu : 0) + (v == 0 ? gv : 0);
                        gy = (u == 1 ? gu : 0) + (v == 1 ? gv : 0);
                        gz = v == 2 ? gv : 0;
                    }
                    int index = (k * 4 + j * 2 + i) * 4;
                    corners[index] = gx;
                    corners[index + 1] = gy;
                    corners[index + 2] = gz;
                    corners[index + 3] = gx * (tx - i) + gy * (ty - j) + gz * (tz - k);
                }
            }
        }
        // Interpolate value and its derivatives together, in float and in X/Y/Z order.
        // Equivalent double-precision corner sums drift sooner in chaotic particle trajectories.
        for (int k = 0; k < (twoDimensions ? 1 : 2); k++) {
            for (int j = 0; j < 2; j++) interpolate(corners, (k * 4 + j * 2) * 4, (k * 4 + j * 2 + 1) * 4, 0, ux, dx);
            interpolate(corners, k * 16, k * 16 + 8, 1, uy, dy);
        }
        if (!twoDimensions) interpolate(corners, 0, 16, 2, uz, dz);
        return result.set(corners[0], corners[1], corners[2]);
    }

    private static void interpolate(float[] values, int a, int b, int axis, float weight, float derivative) {
        float difference = values[b + 3] - values[a + 3];
        for (int component = 0; component < 3; component++) {
            float gradientDifference = values[b + component] - values[a + component];
            float gradient = values[a + component];
            float change = weight * gradientDifference;
            if (component == axis) change += derivative * difference;
            values[a + component] = gradient + change;
        }
        values[a + 3] += weight * difference;
    }
}
