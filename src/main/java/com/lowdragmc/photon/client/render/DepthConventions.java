package com.lowdragmc.photon.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Depth math for legacy (forward-Z) shaders under 26.2's reverse-Z: a point's forward window depth is
 * {@code 1 - }its reversed one, so legacy shaders get {@code 1 - depth} plus the inverse of {@link #forwardProjection}.
 */
public final class DepthConventions {

    private DepthConventions() {
    }

    /**
     * The reverse-Z {@code projection} with its z row rewritten to forward-Z GL clip space: {@code z' = w - 2z} for a
     * [0,1] depth range, {@code z' = -z} for [-1,1].
     */
    public static Matrix4f forwardProjection(Matrix4fc projection, boolean zeroToOne, Matrix4f dest) {
        dest.set(projection);
        if (zeroToOne) {
            dest.m02(projection.m03() - 2 * projection.m02())
                    .m12(projection.m13() - 2 * projection.m12())
                    .m22(projection.m23() - 2 * projection.m22())
                    .m32(projection.m33() - 2 * projection.m32());
        } else {
            dest.m02(-projection.m02())
                    .m12(-projection.m12())
                    .m22(-projection.m22())
                    .m32(-projection.m32());
        }
        return dest;
    }
}
