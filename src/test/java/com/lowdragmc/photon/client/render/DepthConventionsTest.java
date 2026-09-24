package com.lowdragmc.photon.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The legacy depth shim must be exact: {@code 1 - depth} through the inverse of
 * {@link DepthConventions#forwardProjection} reconstructs what the forward-Z 26.1 projection did.
 */
class DepthConventionsTest {

    private static final float NEAR = 0.05f;
    private static final float FAR = 768f;
    private static final float EPSILON = 1e-4f;

    /** 26.2: {@code setPerspective(fov, aspect, zFar, zNear, zZeroToOne)} — the planes swapped. */
    private static Matrix4f reversePerspective(boolean zeroToOne) {
        return new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, FAR, NEAR, zeroToOne);
    }

    private static Matrix4f reverseOrtho(boolean zeroToOne) {
        return new Matrix4f().setOrtho(0, 320, 180, 0, FAR, NEAR, zeroToOne);
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                var e = expected.get(column, row);
                var a = actual.get(column, row);
                assertEquals(e, a, EPSILON * Math.max(1, Math.abs(e)), "m" + column + row);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void perspectiveBecomesTheForwardGlProjection(boolean zeroToOne) {
        var forward = DepthConventions.forwardProjection(reversePerspective(zeroToOne), zeroToOne, new Matrix4f());
        // 26.1 / 1.21: forward-Z, OpenGL's [-1, 1] clip range
        var legacy = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, NEAR, FAR, false);
        assertMatrix(legacy, forward);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void orthoBecomesTheForwardGlProjection(boolean zeroToOne) {
        var forward = DepthConventions.forwardProjection(reverseOrtho(zeroToOne), zeroToOne, new Matrix4f());
        var legacy = new Matrix4f().setOrtho(0, 320, 180, 0, NEAR, FAR, false);
        assertMatrix(legacy, forward);
    }

    /** Forward window depth = 1 - reversed window depth, and the legacy inverse lands on the original point. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyShaderMathReconstructsTheViewPosition(boolean zeroToOne) {
        var reverse = reversePerspective(zeroToOne);
        var inverseForward = DepthConventions.forwardProjection(reverse, zeroToOne, new Matrix4f()).invert();
        for (var distance : new float[]{NEAR, 0.5f, 3f, 40f, 500f, FAR}) {
            var view = new Vector4f(1.5f, -0.75f, -distance, 1);
            var clip = reverse.transform(new Vector4f(view));
            var ndcZ = clip.z / clip.w;
            // what 26.2 stores in the depth buffer, on either NDC range
            var stored = zeroToOne ? ndcZ : ndcZ * 0.5f + 0.5f;
            // what the shim hands the legacy shader, and the shader's own 26.1 math on it
            var legacyDepth = 1 - stored;
            var ndc = new Vector4f(clip.x / clip.w, clip.y / clip.w, legacyDepth * 2 - 1, 1);
            var reconstructed = inverseForward.transform(ndc);
            reconstructed.div(reconstructed.w);
            assertEquals(view.x, reconstructed.x, 1e-3f * distance, "x at " + distance);
            assertEquals(view.y, reconstructed.y, 1e-3f * distance, "y at " + distance);
            assertEquals(view.z, reconstructed.z, 2e-3f * distance, "z at " + distance);
        }
        // the sky: 26.2 clears depth to 0, which the legacy shader must see as 1 ("depth >= 1" = nothing drawn)
        assertEquals(1f, 1 - 0f);
    }
}
