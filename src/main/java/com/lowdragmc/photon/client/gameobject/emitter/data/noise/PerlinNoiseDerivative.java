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
    private static final double SQRT_TWO = Math.sqrt(2);
    private static final double[] GRADIENT_X_2D = {SQRT_TWO, -SQRT_TWO, 0, 0, 1, -1, 1, -1};
    private static final double[] GRADIENT_Y_2D = {0, 0, SQRT_TWO, -SQRT_TWO, 1, 1, -1, -1};

    private PerlinNoiseDerivative() {
    }

    private static int perm(int index) {
        return PERMUTATION[index & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double fadeDerivative(double t) {
        return 30 * t * t * (t - 1) * (t - 1);
    }

    public static Vector3f sample(double x, double y, double z, CurlNoise.Quality quality, Vector3f result) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return result.zero();
        x -= Math.floor(x / 256) * 256;
        y -= Math.floor(y / 256) * 256;
        z -= Math.floor(z / 256) * 256;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double tx = x - ix, ty = y - iy, tz = z - iz;
        double ux = fade(tx), uy = fade(ty), uz = fade(tz);
        double dx = fadeDerivative(tx), dy = fadeDerivative(ty), dz = fadeDerivative(tz);
        if (quality == CurlNoise.Quality.Low) {
            double a = (perm(ix) & 1) == 0 ? 2 : -2;
            double b = (perm(ix + 1) & 1) == 0 ? 2 : -2;
            return result.set((float) ((1 - ux) * a + ux * b + dx * (b * (tx - 1) - a * tx)), 0, 0);
        }
        boolean twoDimensions = quality == CurlNoise.Quality.Medium;
        double rx = 0, ry = 0, rz = 0;
        for (int i = 0; i < 2; i++) {
            double wx = i == 0 ? 1 - ux : ux, dwx = i == 0 ? -dx : dx;
            for (int j = 0; j < 2; j++) {
                double wy = j == 0 ? 1 - uy : uy, dwy = j == 0 ? -dy : dy;
                for (int k = 0; k < (twoDimensions ? 1 : 2); k++) {
                    double wz = twoDimensions ? 1 : k == 0 ? 1 - uz : uz;
                    double dwz = twoDimensions ? 0 : k == 0 ? -dz : dz;
                    int h = perm(perm(ix + i) + iy + j);
                    double gx, gy, gz;
                    if (twoDimensions) {
                        gx = GRADIENT_X_2D[h & 7];
                        gy = GRADIENT_Y_2D[h & 7];
                        gz = 0;
                    } else {
                        h = perm(h + iz + k) & 15;
                        // The final four lattice gradients use the 12,14,13,15 ordering.
                        if (h == 13) h = 14;
                        else if (h == 14) h = 13;
                        int u = h < 8 ? 0 : 1;
                        int v = h < 4 ? 1 : h == 12 || h == 14 ? 0 : 2;
                        double gu = (h & 1) == 0 ? 1 : -1, gv = (h & 2) == 0 ? 1 : -1;
                        gx = (u == 0 ? gu : 0) + (v == 0 ? gv : 0);
                        gy = (u == 1 ? gu : 0) + (v == 1 ? gv : 0);
                        gz = v == 2 ? gv : 0;
                    }
                    double dot = gx * (tx - i) + gy * (ty - j) + gz * (tz - k);
                    rx += dwx * wy * wz * dot + wx * wy * wz * gx;
                    ry += wx * dwy * wz * dot + wx * wy * wz * gy;
                    rz += wx * wy * dwz * dot + wx * wy * wz * gz;
                }
            }
        }
        return result.set((float) rx, (float) ry, (float) rz);
    }
}
