package com.lowdragmc.photon.client.collision;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

/**
 * Moves a body through a {@link CollisionScene} for one step: depenetrate, then cast, respond and slide on
 * with the rest of the motion. Responses act on the <i>stored</i> velocity, never on the per-step one, whose
 * extras (velocity over lifetime, forces) are re-evaluated every tick and would compound in a bounce.
 */
public final class ContactSolver {
    /** Axis-aligned contacts are told apart by these bits (+x, -x, +y, -y, +z, -z), others by target. */
    private static final int[] AXIS_BITS = {1, 2, 4, 8, 16, 32};
    /** Bodies collide as at most this half extent: a huge one would gather a world of blocks every step. */
    private static final double MAX_HALF_EXTENT = 8;

    private ContactSolver() {
    }

    public static final class Params {
        public double bounceChance = 1;
        /** Restitution: the share of the normal speed kept when bouncing. */
        public double bounceRate = 1;
        public double bounceSpread;
        /** The share of the speed lost on every impact. */
        public double dampen;
        public double lifetimeLoss;
        public double minKillSpeed;
        /** 0 = off. */
        public double maxKillSpeed;
        public double minImpactSpeed;
        /** Gravity's speed change over the step, along world down (negative: up). */
        public double gravityStep;
        public int maxContacts = 4;
        /** 0 = no limit. */
        public int maxBounces;
        /** Killed on this many impacts; 0 = never. */
        public int killAfterImpacts;
        /** Stop on any solid contact and stay with the surface (see {@link Body#stuck}). */
        public boolean stick;
        public boolean removeOnContact;
        public boolean killOnTriggerEnter;
        public boolean depenetrate = true;
        public double maxDepenetration = 0.5;
    }

    /** A body for one step, in world space; velocities in blocks per tick. */
    public static final class Body implements CollisionQuery.Exclusion {
        public double cx, cy, cz;
        public double hx, hy, hz;
        /** The velocity it moves with this step. */
        public double vx, vy, vz;
        /** The persistent velocity responses act on. */
        public double svx, svy, svz;
        public int previousAxes;
        public long previousTarget = -1;
        public final long[] insideTriggers = new long[8];
        public int insideTriggerCount;
        /** Bounces and impacts so far; updated by the step. */
        public int bounces, impacts;

        // outputs
        public double moveX, moveY, moveZ;
        public int axes;
        public long target = -1;
        public boolean touched;
        /** The last solid contact's normal and surface velocity, for contact friction. */
        public double contactNx, contactNy, contactNz;
        public double contactVx, contactVy, contactVz;
        public boolean killed;
        /** It stuck to the last solid contact: kind and key of what it hit, as in {@link Contact}. */
        public boolean stuck;
        public byte stuckKind;
        public long stuckKey;
        public double lifetimeLost;
        final long[] crossed = new long[8];
        int crossedCount;

        void resetOutputs() {
            moveX = moveY = moveZ = 0;
            axes = 0;
            target = -1;
            touched = false;
            contactNx = contactNy = contactNz = 0;
            contactVx = contactVy = contactVz = 0;
            killed = false;
            stuck = false;
            lifetimeLost = 0;
            crossedCount = 0;
        }

        boolean wasInside(long identity) {
            for (int i = 0; i < insideTriggerCount; i++) if (insideTriggers[i] == identity) return true;
            return false;
        }

        @Override
        public boolean excluded(long identity) {
            for (int i = 0; i < crossedCount; i++) if (crossed[i] == identity) return true;
            return false;
        }

        /** The triggers the body crossed this step, entered or not. */
        public int crossedCount() {
            return crossedCount;
        }

        public long crossed(int index) {
            return crossed[index];
        }
    }

    /** What happened during a step, reported as it happens. */
    public interface Events {
        /** A new impact on a solid surface (not resting on it). */
        void impact(Contact contact, double speed);

        /** The body is removed by a contact rule; {@code contact} is what removed it. */
        void killed(Contact contact);

        void triggerEnter(Contact contact);
    }

    public static void step(CollisionQuery query, CollisionScene scene, Body body, Params params, double dt,
                            RandomSource random, @Nullable Events events, Contact contact, double[] scratch) {
        body.resetOutputs();
        double x = body.cx, y = body.cy, z = body.cz;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        body.hx = halfExtent(body.hx, x);
        body.hy = halfExtent(body.hy, y);
        body.hz = halfExtent(body.hz, z);
        query.prepare(scene, x, y, z, body.hx, body.hy, body.hz, body.vx * dt, body.vy * dt, body.vz * dt);
        if (params.depenetrate && query.depenetrate(scene, x, y, z, body.hx, body.hy, body.hz, dt,
                params.maxDepenetration, scratch)) {
            x += scratch[0];
            y += scratch[1];
            z += scratch[2];
        }
        double rx = body.vx * dt, ry = body.vy * dt, rz = body.vz * dt;
        // what the remaining motion takes: later casts meet moving targets where they are by then
        double time = dt;
        int contacts = 0;
        while (contacts < params.maxContacts && (rx * rx + ry * ry + rz * rz > 1e-18 || scene.hasMovingTargets)) {
            int result = query.cast(scene, x, y, z, body.hx, body.hy, body.hz, rx, ry, rz, time, body, contact);
            if (result != CollisionQuery.HIT) {
                if (result == CollisionQuery.NONE) {
                    x += rx;
                    y += ry;
                    z += rz;
                }
                break;
            }
            x += rx * contact.t;
            y += ry * contact.t;
            z += rz * contact.t;
            double left = 1 - contact.t;
            rx *= left;
            ry *= left;
            rz *= left;
            time *= left;
            if (contact.trigger) {
                // never stops the body, and is crossed at most once per step
                if (body.crossedCount == body.crossed.length) break;
                long identity = contact.identity();
                body.crossed[body.crossedCount++] = identity;
                if (!body.wasInside(identity)) {
                    if (events != null) events.triggerEnter(contact);
                    if (params.killOnTriggerEnter) {
                        body.killed = true;
                        if (events != null) events.killed(contact);
                        break;
                    }
                }
                continue;
            }
            contacts++;
            boolean done = respond(body, params, contact, random, events, rx, ry, rz, time, scratch);
            rx = scratch[0];
            ry = scratch[1];
            rz = scratch[2];
            if (done) {
                // stuck: carried along by the surface for the rest of the step
                if (body.stuck) {
                    x += rx;
                    y += ry;
                    z += rz;
                }
                rx = ry = rz = 0;
                break;
            }
        }
        // out of contacts: the rest of the motion goes as far as the next surface, without a response
        if (contacts >= params.maxContacts && rx * rx + ry * ry + rz * rz > 1e-18) {
            int result = query.cast(scene, x, y, z, body.hx, body.hy, body.hz, rx, ry, rz, time, body, contact);
            double t = result == CollisionQuery.NONE ? 1 : result == CollisionQuery.HIT ? contact.t : 0;
            x += rx * t;
            y += ry * t;
            z += rz * t;
        }
        body.moveX = x - body.cx;
        body.moveY = y - body.cy;
        body.moveZ = z - body.cz;
    }

    /**
     * One solid contact, relative to the surface's own motion; {@code r} is what is left of the motion and takes
     * {@code time}. Writes the motion left after the response into {@code out}; true when the step ends here
     * (the body died or stuck).
     */
    private static boolean respond(Body body, Params params, Contact contact, RandomSource random, @Nullable Events events,
                                   double rx, double ry, double rz, double time, double[] out) {
        double nx = contact.nx, ny = contact.ny, nz = contact.nz;
        double tvx = contact.vx, tvy = contact.vy, tvz = contact.vz;
        // the velocity it meets the surface with: an earlier bounce this step may have turned it
        double vx = time > 0 ? rx / time : body.vx, vy = time > 0 ? ry / time : body.vy, vz = time > 0 ? rz / time : body.vz;
        body.touched = true;
        body.contactNx = nx;
        body.contactNy = ny;
        body.contactNz = nz;
        body.contactVx = tvx;
        body.contactVy = tvy;
        body.contactVz = tvz;
        double speed = -((vx - tvx) * nx + (vy - tvy) * ny + (vz - tvz) * nz);
        int bit = axisBit(nx, ny, nz);
        boolean fresh;
        if (bit != 0) {
            fresh = (body.previousAxes & bit) == 0 && (body.axes & bit) == 0;
            body.axes |= bit;
        } else {
            long identity = contact.identity();
            fresh = body.previousTarget != identity && body.target != identity;
            body.target = identity;
        }
        // speed gravity takes back within a step or two: lying on the surface, not hitting it
        double gravity = params.gravityStep;
        boolean resting = speed <= 2 * Math.abs(gravity) * Math.max(0, gravity >= 0 ? ny : -ny) + 1e-6;
        boolean impact = fresh && !resting;
        if (impact) {
            body.impacts++;
            if (events != null && speed >= params.minImpactSpeed) events.impact(contact, speed);
        }
        if (params.removeOnContact || (params.maxKillSpeed > 0 && speed > params.maxKillSpeed)
                || (impact && params.killAfterImpacts > 0 && body.impacts >= params.killAfterImpacts)) {
            body.killed = true;
            if (events != null) events.killed(contact);
            out[0] = out[1] = out[2] = 0;
            return true;
        }
        if (params.stick) {
            body.stuck = true;
            body.stuckKind = contact.kind;
            body.stuckKey = contact.key;
            body.svx = tvx;
            body.svy = tvy;
            body.svz = tvz;
            out[0] = tvx * time;
            out[1] = tvy * time;
            out[2] = tvz * time;
            return true;
        }
        boolean bounce = !resting && (params.maxBounces <= 0 || body.bounces < params.maxBounces)
                && (params.bounceChance >= 1 || random.nextDouble() < params.bounceChance);
        if (bounce) body.bounces++;
        double restitution = bounce ? 1 + Math.max(0, params.bounceRate) : 1;
        double storedNormal = (body.svx - tvx) * nx + (body.svy - tvy) * ny + (body.svz - tvz) * nz;
        if (storedNormal < 0) {
            body.svx -= restitution * storedNormal * nx;
            body.svy -= restitution * storedNormal * ny;
            body.svz -= restitution * storedNormal * nz;
        }
        double sx = tvx * time, sy = tvy * time, sz = tvz * time;
        double remainingNormal = (rx - sx) * nx + (ry - sy) * ny + (rz - sz) * nz;
        if (remainingNormal < 0) {
            rx -= restitution * remainingNormal * nx;
            ry -= restitution * remainingNormal * ny;
            rz -= restitution * remainingNormal * nz;
        }
        if (bounce && params.bounceSpread > 0) {
            double gx = random.nextGaussian() * params.bounceSpread, gy = random.nextGaussian() * params.bounceSpread,
                    gz = random.nextGaussian() * params.bounceSpread;
            double along = gx * nx + gy * ny + gz * nz;
            body.svx += gx - along * nx;
            body.svy += gy - along * ny;
            body.svz += gz - along * nz;
        }
        if (impact) {
            if (params.dampen > 0) {
                double keep = Math.max(0, 1 - params.dampen);
                body.svx = tvx + (body.svx - tvx) * keep;
                body.svy = tvy + (body.svy - tvy) * keep;
                body.svz = tvz + (body.svz - tvz) * keep;
                rx = sx + (rx - sx) * keep;
                ry = sy + (ry - sy) * keep;
                rz = sz + (rz - sz) * keep;
            }
            body.lifetimeLost += params.lifetimeLoss;
            if (params.minKillSpeed > 0
                    && body.svx * body.svx + body.svy * body.svy + body.svz * body.svz < params.minKillSpeed * params.minKillSpeed) {
                body.killed = true;
                if (events != null) events.killed(contact);
                out[0] = out[1] = out[2] = 0;
                return true;
            }
        }
        out[0] = rx;
        out[1] = ry;
        out[2] = rz;
        return false;
    }

    /**
     * The half extent a body collides with: at least twice the float tolerance at {@code coordinate} (a thinner one
     * would touch both blocks at a seam from the side, and fall through), at most {@link #MAX_HALF_EXTENT}.
     */
    public static double halfExtent(double half, double coordinate) {
        double min = 2 * BoxCast.tolerance(coordinate);
        return half > min ? Math.min(half, MAX_HALF_EXTENT) : min;
    }

    private static int axisBit(double nx, double ny, double nz) {
        if (nx == 1 && ny == 0 && nz == 0) return AXIS_BITS[0];
        if (nx == -1 && ny == 0 && nz == 0) return AXIS_BITS[1];
        if (ny == 1 && nx == 0 && nz == 0) return AXIS_BITS[2];
        if (ny == -1 && nx == 0 && nz == 0) return AXIS_BITS[3];
        if (nz == 1 && nx == 0 && ny == 0) return AXIS_BITS[4];
        if (nz == -1 && nx == 0 && ny == 0) return AXIS_BITS[5];
        return 0;
    }
}
