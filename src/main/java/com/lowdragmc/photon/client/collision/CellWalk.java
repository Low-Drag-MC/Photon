package com.lowdragmc.photon.client.collision;

import net.minecraft.util.Mth;

/**
 * The cells a ray crosses, in the order and with the arithmetic of vanilla's {@code BlockGetter.traverseBlocks}: the
 * ray stretched by 1e-7 at both ends, and ties through edges and corners broken by the same rounding.
 */
final class CellWalk {
    int x, y, z;
    private int stepX, stepY, stepZ;
    private double deltaX, deltaY, deltaZ, nextX, nextY, nextZ;

    /** Starts at the cell holding {@code o}, for the ray to {@code e}. */
    void start(double ox, double oy, double oz, double ex, double ey, double ez) {
        double sx = Mth.lerp(-1.0E-7, ox, ex), sy = Mth.lerp(-1.0E-7, oy, ey), sz = Mth.lerp(-1.0E-7, oz, ez);
        double lx = Mth.lerp(-1.0E-7, ex, ox) - sx, ly = Mth.lerp(-1.0E-7, ey, oy) - sy, lz = Mth.lerp(-1.0E-7, ez, oz) - sz;
        x = Mth.floor(sx);
        y = Mth.floor(sy);
        z = Mth.floor(sz);
        stepX = Mth.sign(lx);
        stepY = Mth.sign(ly);
        stepZ = Mth.sign(lz);
        deltaX = stepX == 0 ? Double.MAX_VALUE : stepX / lx;
        deltaY = stepY == 0 ? Double.MAX_VALUE : stepY / ly;
        deltaZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / lz;
        nextX = deltaX * (stepX > 0 ? 1 - Mth.frac(sx) : Mth.frac(sx));
        nextY = deltaY * (stepY > 0 ? 1 - Mth.frac(sy) : Mth.frac(sy));
        nextZ = deltaZ * (stepZ > 0 ? 1 - Mth.frac(sz) : Mth.frac(sz));
    }

    /** Moves on to the next cell; false past the end of the ray. */
    boolean next() {
        if (!(nextX <= 1 || nextY <= 1 || nextZ <= 1)) return false;
        if (nextX < nextY) {
            if (nextX < nextZ) {
                x += stepX;
                nextX += deltaX;
            } else {
                z += stepZ;
                nextZ += deltaZ;
            }
        } else if (nextY < nextZ) {
            y += stepY;
            nextY += deltaY;
        } else {
            z += stepZ;
            nextZ += deltaZ;
        }
        return true;
    }
}
