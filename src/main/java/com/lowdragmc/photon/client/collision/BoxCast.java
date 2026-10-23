package com.lowdragmc.photon.client.collision;

/**
 * Swept-volume math in plain doubles; no allocation, any thread. Positions come back from float storage an
 * ulp inside the face they stopped on, so an overlap within the tolerance is touching: a contact at t = 0 when
 * moving into the face, none when sliding along or leaving it. Deeper overlaps are ignored (callers depenetrate).
 * Tolerances are per axis, from {@link #tolerance(double)} of that coordinate.
 */
public final class BoxCast {
    private static final double STATIC = 1.0E-12;

    private BoxCast() {
    }

    private static final double MAX_TOLERANCE = 1.0 / 64;

    /** Time of impact in [0, 1] of the motion, and the unit contact normal. */
    public static final class Hit {
        public double t;
        public double nx, ny, nz;

        public void set(double t, double nx, double ny, double nz) {
            this.t = t;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    /**
     * The overlap that still counts as touching at {@code coordinate}: 4 to 8 float ulps, from 1e-7 up to 1/64 of a
     * block. Beyond that (past 32768 blocks out) it would swallow whole overlaps with a block: floats there are too
     * coarse, and what rounding leaves inside a face is pushed out instead.
     */
    public static double tolerance(double coordinate) {
        // |c| * 2^-21 bounds 4 * ulp((float) c) from above, without the ulp's cost on every cast
        double tolerance = Math.abs(coordinate) * 0x1p-21;
        return tolerance > MAX_TOLERANCE ? MAX_TOLERANCE : tolerance > 1.0E-7 ? tolerance : 1.0E-7;
    }

    /** Earliest contact of a box (center {@code c}, half extents {@code h}) moving by {@code d} with a static box. */
    public static boolean boxVsBox(double cx, double cy, double cz, double hx, double hy, double hz,
                                   double dx, double dy, double dz,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                   double tx, double ty, double tz, Hit out) {
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        int axis = -1;

        double lo = minX - hx, hi = maxX + hx;
        if (Math.abs(dx) < STATIC) {
            if (cx <= lo + tx || cx >= hi - tx) return false;
        } else {
            double t1 = (lo - cx) / dx, t2 = (hi - cx) / dx;
            double near = Math.min(t1, t2);
            if (near > enter) {
                enter = near;
                axis = 0;
            }
            exit = Math.min(exit, Math.max(t1, t2));
        }

        lo = minY - hy;
        hi = maxY + hy;
        if (Math.abs(dy) < STATIC) {
            if (cy <= lo + ty || cy >= hi - ty) return false;
        } else {
            double t1 = (lo - cy) / dy, t2 = (hi - cy) / dy;
            double near = Math.min(t1, t2);
            if (near > enter) {
                enter = near;
                axis = 1;
            }
            exit = Math.min(exit, Math.max(t1, t2));
        }

        lo = minZ - hz;
        hi = maxZ + hz;
        if (Math.abs(dz) < STATIC) {
            if (cz <= lo + tz || cz >= hi - tz) return false;
        } else {
            double t1 = (lo - cz) / dz, t2 = (hi - cz) / dz;
            double near = Math.min(t1, t2);
            if (near > enter) {
                enter = near;
                axis = 2;
            }
            exit = Math.min(exit, Math.max(t1, t2));
        }

        if (axis < 0 || enter > exit || enter > 1 || exit <= 0) return false;
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // grazing an edge, or leaving a face it only touches
        if ((exit - Math.max(enter, 0)) * speed <= Math.max(tx, Math.max(ty, tz))) return false;
        if (enter < 0) {
            double axisSpeed = axis == 0 ? Math.abs(dx) : axis == 1 ? Math.abs(dy) : Math.abs(dz);
            // started overlapping: only a touch along the axis it entered through counts
            if (-enter * axisSpeed > (axis == 0 ? tx : axis == 1 ? ty : tz)) return false;
            enter = 0;
        }
        double d = axis == 0 ? dx : axis == 1 ? dy : dz;
        double sign = d > 0 ? -1 : 1;
        out.set(enter, axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
        return true;
    }

    /**
     * The pushes that move a box clear of a static box it overlaps by more than the tolerance, along +x, -x, +y,
     * -y, +z and -z, into {@code out}. False when they do not overlap.
     */
    public static boolean pushes(double cx, double cy, double cz, double hx, double hy, double hz,
                                 double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                 double tx, double ty, double tz, double[] out) {
        double px = maxX - (cx - hx), nx = (cx + hx) - minX;
        double py = maxY - (cy - hy), ny = (cy + hy) - minY;
        double pz = maxZ - (cz - hz), nz = (cz + hz) - minZ;
        if (px <= tx || nx <= tx || py <= ty || ny <= ty || pz <= tz || nz <= tz) return false;
        out[0] = px;
        out[1] = nx;
        out[2] = py;
        out[3] = ny;
        out[4] = pz;
        out[5] = nz;
        return true;
    }

    /** Whether two boxes overlap by more than the tolerance on every axis. */
    public static boolean overlaps(double cx, double cy, double cz, double hx, double hy, double hz,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                   double tx, double ty, double tz) {
        return cx + hx > minX + tx && cx - hx < maxX - tx
                && cy + hy > minY + ty && cy - hy < maxY - ty
                && cz + hz > minZ + tz && cz - hz < maxZ - tz;
    }

    /** Earliest contact of a sphere moving by {@code d} with a one-sided plane through {@code p} facing unit {@code n}. */
    public static boolean sphereVsPlane(double cx, double cy, double cz, double r, double dx, double dy, double dz,
                                        double px, double py, double pz, double nx, double ny, double nz,
                                        double tol, Hit out) {
        double gap = (cx - px) * nx + (cy - py) * ny + (cz - pz) * nz - r;
        if (gap < -tol) return false;
        double closing = -(dx * nx + dy * ny + dz * nz);
        if (closing <= STATIC) return false;
        double t = Math.max(0, gap / closing);
        if (t > 1) return false;
        out.set(t, nx, ny, nz);
        return true;
    }

    /** Earliest contact of a sphere moving by {@code d} with a solid sphere at {@code s} of the given radius. */
    public static boolean sphereVsSphere(double cx, double cy, double cz, double r, double dx, double dy, double dz,
                                         double sx, double sy, double sz, double radius, double tol, Hit out) {
        double ox = cx - sx, oy = cy - sy, oz = cz - sz;
        double reach = r + radius;
        double a = dx * dx + dy * dy + dz * dz;
        double distance = Math.sqrt(ox * ox + oy * oy + oz * oz);
        double approach = ox * dx + oy * dy + oz * dz;
        // static, moving apart, or sliding along the surface (a tangent motion only rounds to approaching)
        if (a < STATIC || approach >= -1e-9 * Math.sqrt(a) * distance) return false;
        if (distance < reach) {
            if (reach - distance > tol || distance < STATIC) return false;
            out.set(0, ox / distance, oy / distance, oz / distance);
            return true;
        }
        double b = 2 * approach;
        double c = (distance - reach) * (distance + reach);
        double disc = b * b - 4 * a * c;
        if (disc < 0) return false;
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        if (t < 0 || t > 1) return false;
        double hx = ox + dx * t, hy = oy + dy * t, hz = oz + dz * t;
        double length = Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (length < STATIC) return false;
        out.set(t, hx / length, hy / length, hz / length);
        return true;
    }

    /** A ray from {@code o} along {@code d} against a box; one starting inside hits at t = 0, like vanilla's clip. */
    public static boolean rayVsBox(double ox, double oy, double oz, double dx, double dy, double dz,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                   Hit out) {
        if (ox > minX && ox < maxX && oy > minY && oy < maxY && oz > minZ && oz < maxZ) {
            double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
            if (ax >= ay && ax >= az) out.set(0, dx > 0 ? -1 : 1, 0, 0);
            else if (ay >= az) out.set(0, 0, dy > 0 ? -1 : 1, 0);
            else out.set(0, 0, 0, dz > 0 ? -1 : 1);
            return true;
        }
        double enter = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
        int axis = -1;
        if (Math.abs(dx) < STATIC) {
            if (ox < minX || ox > maxX) return false;
        } else {
            double t1 = (minX - ox) / dx, t2 = (maxX - ox) / dx;
            if (Math.min(t1, t2) > enter) { enter = Math.min(t1, t2); axis = 0; }
            exit = Math.min(exit, Math.max(t1, t2));
        }
        if (Math.abs(dy) < STATIC) {
            if (oy < minY || oy > maxY) return false;
        } else {
            double t1 = (minY - oy) / dy, t2 = (maxY - oy) / dy;
            if (Math.min(t1, t2) > enter) { enter = Math.min(t1, t2); axis = 1; }
            exit = Math.min(exit, Math.max(t1, t2));
        }
        if (Math.abs(dz) < STATIC) {
            if (oz < minZ || oz > maxZ) return false;
        } else {
            double t1 = (minZ - oz) / dz, t2 = (maxZ - oz) / dz;
            if (Math.min(t1, t2) > enter) { enter = Math.min(t1, t2); axis = 2; }
            exit = Math.min(exit, Math.max(t1, t2));
        }
        if (axis < 0 || enter > exit || enter > 1 || enter < 0) return false;
        double d = axis == 0 ? dx : axis == 1 ? dy : dz;
        double sign = d > 0 ? -1 : 1;
        out.set(enter, axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
        return true;
    }
}
