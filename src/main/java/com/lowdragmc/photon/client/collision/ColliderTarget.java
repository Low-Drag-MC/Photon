package com.lowdragmc.photon.client.collision;

import org.jetbrains.annotations.Nullable;

/**
 * An analytic collider frozen for one tick, in world units. Bodies meet it as a sphere of their largest half
 * extent (planes, spheres) or a cube of it in the box's own axes (boxes).
 */
public final class ColliderTarget {
    public enum Shape {
        /** one-sided infinite plane through {@code center}, facing {@code axes[3..5]} (the local +Y) */
        PLANE,
        /** oriented box: {@code center}, unit axes in {@code axes}, half extents along them */
        BOX,
        SPHERE
    }

    public final Shape shape;
    public final boolean trigger;
    /** Stable across ticks: identifies the collider in trigger bookkeeping. */
    public final long index;
    @Nullable
    public final Object source;
    public final double cx, cy, cz;
    /** Three unit axes, x then y then z. */
    final double[] axes;
    final double ex, ey, ez;
    final double radius;
    /** How far it moved during the tick that ends here. */
    public final double mx, my, mz;
    /** Its local x, y and z axes as they reach the world, scale included; null: none, for what is stuck to it. */
    @Nullable
    private final double[] frame;

    private ColliderTarget(Shape shape, boolean trigger, long index, @Nullable Object source,
                           double cx, double cy, double cz, double[] axes,
                           double ex, double ey, double ez, double radius, double mx, double my, double mz,
                           @Nullable double[] frame) {
        this.shape = shape;
        this.trigger = trigger;
        this.index = index;
        this.source = source;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.axes = axes;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
        this.radius = radius;
        this.mx = mx;
        this.my = my;
        this.mz = mz;
        this.frame = frame;
    }

    /** This collider, having moved by {@code m} during the tick to where it is now. */
    public ColliderTarget moving(double mx, double my, double mz) {
        return new ColliderTarget(shape, trigger, index, source, cx, cy, cz, axes, ex, ey, ez, radius, mx, my, mz, frame);
    }

    /** This collider with its local frame: x, y and z axes in world units (see {@link #toLocal}). */
    public ColliderTarget framed(double[] frame) {
        return new ColliderTarget(shape, trigger, index, source, cx, cy, cz, axes, ex, ey, ez, radius, mx, my, mz, frame);
    }

    /** A world point in its local frame, so it can follow the collider's moves, turns and scaling; false if that has collapsed. */
    public boolean toLocal(double px, double py, double pz, double[] out) {
        double ox = px - cx, oy = py - cy, oz = pz - cz;
        if (frame == null) {
            out[0] = ox;
            out[1] = oy;
            out[2] = oz;
            return true;
        }
        var f = frame;
        // the rows of the inverse are the cross products of the other two axes, over the determinant
        double yzx = f[4] * f[8] - f[5] * f[7], yzy = f[5] * f[6] - f[3] * f[8], yzz = f[3] * f[7] - f[4] * f[6];
        double zxx = f[7] * f[2] - f[8] * f[1], zxy = f[8] * f[0] - f[6] * f[2], zxz = f[6] * f[1] - f[7] * f[0];
        double xyx = f[1] * f[5] - f[2] * f[4], xyy = f[2] * f[3] - f[0] * f[5], xyz = f[0] * f[4] - f[1] * f[3];
        double det = f[0] * yzx + f[1] * yzy + f[2] * yzz;
        if (!(Math.abs(det) > 1e-12)) return false;
        out[0] = (ox * yzx + oy * yzy + oz * yzz) / det;
        out[1] = (ox * zxx + oy * zxy + oz * zxz) / det;
        out[2] = (ox * xyx + oy * xyy + oz * xyz) / det;
        return true;
    }

    /** The world point at {@code l} in its local frame; see {@link #toLocal}. */
    public void toWorld(double lx, double ly, double lz, double[] out) {
        if (frame == null) {
            out[0] = cx + lx;
            out[1] = cy + ly;
            out[2] = cz + lz;
            return;
        }
        var f = frame;
        out[0] = cx + lx * f[0] + ly * f[3] + lz * f[6];
        out[1] = cy + lx * f[1] + ly * f[4] + lz * f[7];
        out[2] = cz + lx * f[2] + ly * f[5] + lz * f[8];
    }

    /** Its motion as seen from a frame that itself moved by {@code f}, times {@code scale}. */
    public ColliderTarget relativeTo(double fx, double fy, double fz, double scale) {
        return fx == 0 && fy == 0 && fz == 0 && scale == 1 ? this : moving((mx - fx) * scale, (my - fy) * scale, (mz - fz) * scale);
    }

    public static ColliderTarget plane(long index, @Nullable Object source, boolean trigger,
                                       double px, double py, double pz, double nx, double ny, double nz) {
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-12) {
            nx = 0;
            ny = 1;
            nz = 0;
            length = 1;
        }
        return new ColliderTarget(Shape.PLANE, trigger, index, source, px, py, pz,
                new double[]{0, 0, 0, nx / length, ny / length, nz / length, 0, 0, 0}, 0, 0, 0, 0, 0, 0, 0, null);
    }

    public static ColliderTarget sphere(long index, @Nullable Object source, boolean trigger,
                                        double cx, double cy, double cz, double radius) {
        return new ColliderTarget(Shape.SPHERE, trigger, index, source, cx, cy, cz, new double[9], 0, 0, 0,
                radius > 0 ? radius : 0, 0, 0, 0, null);
    }

    /**
     * {@code axes} = the box's local x, y, z directions, made unit and perpendicular here (a skewed box becomes
     * the nearest cuboid); {@code e*} = world half extents. Null when two of the axes have collapsed.
     */
    @Nullable
    public static ColliderTarget box(long index, @Nullable Object source, boolean trigger,
                                     double cx, double cy, double cz, double[] axes,
                                     double ex, double ey, double ez) {
        var unit = orthonormal(axes);
        if (unit == null) return null;
        return new ColliderTarget(Shape.BOX, trigger, index, source, cx, cy, cz, unit,
                extent(ex), extent(ey), extent(ez), 0, 0, 0, 0, null);
    }

    private static double extent(double e) {
        return e != e ? 0 : Math.abs(e);
    }

    /** The two longest axes made perpendicular, and the third across both; null when fewer than two remain. */
    @Nullable
    private static double[] orthonormal(double[] axes) {
        double[] length = new double[3];
        for (int a = 0; a < 3; a++) {
            length[a] = Math.sqrt(axes[a * 3] * axes[a * 3] + axes[a * 3 + 1] * axes[a * 3 + 1] + axes[a * 3 + 2] * axes[a * 3 + 2]);
        }
        int first = length[0] >= length[1] ? (length[0] >= length[2] ? 0 : 2) : (length[1] >= length[2] ? 1 : 2);
        int second = (first + 1) % 3, third = (first + 2) % 3;
        if (length[third] > length[second]) {
            int swap = second;
            second = third;
            third = swap;
        }
        if (!(length[second] > 1e-9)) return null;
        var unit = new double[9];
        for (int i = 0; i < 3; i++) unit[first * 3 + i] = axes[first * 3 + i] / length[first];
        double along = 0;
        for (int i = 0; i < 3; i++) along += axes[second * 3 + i] * unit[first * 3 + i];
        double norm = 0;
        for (int i = 0; i < 3; i++) {
            unit[second * 3 + i] = axes[second * 3 + i] - along * unit[first * 3 + i];
            norm += unit[second * 3 + i] * unit[second * 3 + i];
        }
        norm = Math.sqrt(norm);
        if (!(norm > 1e-9)) return null;
        for (int i = 0; i < 3; i++) unit[second * 3 + i] /= norm;
        int f = first * 3, s = second * 3, t = third * 3;
        unit[t] = unit[f + 1] * unit[s + 2] - unit[f + 2] * unit[s + 1];
        unit[t + 1] = unit[f + 2] * unit[s] - unit[f] * unit[s + 2];
        unit[t + 2] = unit[f] * unit[s + 1] - unit[f + 1] * unit[s];
        return unit;
    }

    boolean cast(double px, double py, double pz, double r, double dx, double dy, double dz, double tol, BoxCast.Hit out) {
        return switch (shape) {
            case PLANE -> BoxCast.sphereVsPlane(px, py, pz, r, dx, dy, dz, cx, cy, cz, axes[3], axes[4], axes[5], tol, out);
            case SPHERE -> BoxCast.sphereVsSphere(px, py, pz, r, dx, dy, dz, cx, cy, cz, radius, tol, out);
            case BOX -> {
                double ox = px - cx, oy = py - cy, oz = pz - cz;
                double lx = dot(0, ox, oy, oz), ly = dot(1, ox, oy, oz), lz = dot(2, ox, oy, oz);
                double mx = dot(0, dx, dy, dz), my = dot(1, dx, dy, dz), mz = dot(2, dx, dy, dz);
                if (!BoxCast.boxVsBox(lx, ly, lz, r, r, r, mx, my, mz, -ex, -ey, -ez, ex, ey, ez, tol, tol, tol, out)) yield false;
                double nx = out.nx, ny = out.ny, nz = out.nz;
                out.set(out.t,
                        nx * axes[0] + ny * axes[3] + nz * axes[6],
                        nx * axes[1] + ny * axes[4] + nz * axes[7],
                        nx * axes[2] + ny * axes[5] + nz * axes[8]);
                yield true;
            }
        };
    }

    /**
     * How far a sphere of radius {@code r} at {@code p} must move along the normal written into {@code out} to
     * clear this solid; 0 when it is clear, or behind a plane (planes are one-sided).
     */
    double separation(double px, double py, double pz, double r, double tol, BoxCast.Hit out) {
        double ox = px - cx, oy = py - cy, oz = pz - cz;
        switch (shape) {
            case PLANE -> {
                double distance = ox * axes[3] + oy * axes[4] + oz * axes[5];
                if (distance < -tol || distance >= r - tol) return 0;
                out.set(0, axes[3], axes[4], axes[5]);
                return r - distance;
            }
            case SPHERE -> {
                double length = Math.sqrt(ox * ox + oy * oy + oz * oz);
                double depth = radius + r - length;
                if (depth <= tol) return 0;
                if (length < 1e-12) out.set(0, 0, 1, 0);
                else out.set(0, ox / length, oy / length, oz / length);
                return depth;
            }
            default -> {
                double best = Double.POSITIVE_INFINITY;
                int axis = 0;
                double sign = 1;
                for (int a = 0; a < 3; a++) {
                    double local = dot(a, ox, oy, oz);
                    double depth = (a == 0 ? ex : a == 1 ? ey : ez) + r - Math.abs(local);
                    if (depth <= tol) return 0;
                    if (depth < best) {
                        best = depth;
                        axis = a;
                        sign = local >= 0 ? 1 : -1;
                    }
                }
                out.set(0, axes[axis * 3] * sign, axes[axis * 3 + 1] * sign, axes[axis * 3 + 2] * sign);
                return best;
            }
        }
    }

    /** Inside the volume, or behind a plane. */
    boolean contains(double px, double py, double pz, double r) {
        double ox = px - cx, oy = py - cy, oz = pz - cz;
        return switch (shape) {
            case PLANE -> ox * axes[3] + oy * axes[4] + oz * axes[5] < r;
            case SPHERE -> ox * ox + oy * oy + oz * oz < (radius + r) * (radius + r);
            case BOX -> Math.abs(dot(0, ox, oy, oz)) < ex + r && Math.abs(dot(1, ox, oy, oz)) < ey + r
                    && Math.abs(dot(2, ox, oy, oz)) < ez + r;
        };
    }

    void closestPoint(double px, double py, double pz, double[] out) {
        double ox = px - cx, oy = py - cy, oz = pz - cz;
        switch (shape) {
            case PLANE -> {
                double distance = ox * axes[3] + oy * axes[4] + oz * axes[5];
                out[0] = px - axes[3] * distance;
                out[1] = py - axes[4] * distance;
                out[2] = pz - axes[5] * distance;
            }
            case SPHERE -> {
                double length = Math.sqrt(ox * ox + oy * oy + oz * oz);
                double scale = length < 1e-12 ? 0 : radius / length;
                out[0] = cx + ox * scale;
                out[1] = cy + oy * scale;
                out[2] = cz + oz * scale;
            }
            case BOX -> {
                double lx = CollisionQuery.clamp(dot(0, ox, oy, oz), -ex, ex);
                double ly = CollisionQuery.clamp(dot(1, ox, oy, oz), -ey, ey);
                double lz = CollisionQuery.clamp(dot(2, ox, oy, oz), -ez, ez);
                out[0] = cx + lx * axes[0] + ly * axes[3] + lz * axes[6];
                out[1] = cy + lx * axes[1] + ly * axes[4] + lz * axes[7];
                out[2] = cz + lx * axes[2] + ly * axes[5] + lz * axes[8];
            }
        }
    }

    private double dot(int axis, double x, double y, double z) {
        return x * axes[axis * 3] + y * axes[axis * 3 + 1] + z * axes[axis * 3 + 2];
    }
}
