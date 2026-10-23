package com.lowdragmc.photon.client.gameobject.particle.aratrail;

import com.lowdragmc.photon.client.collision.CollisionQuery;
import com.lowdragmc.photon.client.collision.CollisionScene;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.client.collision.ContactSolver;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraPhysicsSetting;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Moves trail points as small boxes that slide on or bounce off blocks; positions and velocities in trail space. */
final class TrailPointCollision {
    private static final int MAX_CONTACTS = 3;
    private static final double MAX_PUSH = 0.5;

    private final Contact contact = new Contact();
    private final Vector3f scratch = new Vector3f();
    private final double[] push = new double[3];
    private final Matrix4f trailToWorld;
    private final Matrix4f worldToTrail;
    private final CollisionScene scene;
    private final double radius, bounce, keep;

    TrailPointCollision(CollisionScene scene, Matrix4f worldToTrail, AraPhysicsSetting setting) {
        this.scene = scene;
        this.worldToTrail = worldToTrail;
        this.trailToWorld = new Matrix4f(worldToTrail).invert();
        this.radius = Math.max(0, setting.collisionRadius);
        this.bounce = Math.clamp(setting.collisionBounce, 0, 1);
        this.keep = 1 - Math.clamp(setting.collisionFriction, 0, 1);
    }

    /** Moves {@code position} by {@code velocity * timestep}, colliding; both updated in place. */
    void move(Vector3f position, Vector3f velocity, float timestep) {
        var world = trailToWorld.transformPosition(scratch.set(position));
        if (!world.isFinite()) return;
        double px = world.x, py = world.y, pz = world.z;
        var worldVelocity = trailToWorld.transformDirection(scratch.set(velocity));
        double vx = worldVelocity.x, vy = worldVelocity.y, vz = worldVelocity.z;
        double rx = vx * timestep, ry = vy * timestep, rz = vz * timestep;
        double hx = ContactSolver.halfExtent(radius, px), hy = ContactSolver.halfExtent(radius, py),
                hz = ContactSolver.halfExtent(radius, pz);
        var query = CollisionQuery.get();
        try {
            query.prepare(scene, px, py, pz, hx, hy, hz, rx, ry, rz);
            // a point emitted at ground level starts in the ground, up to its whole size deep
            if (query.depenetrate(scene, px, py, pz, hx, hy, hz, 1, Math.max(MAX_PUSH, 2 * radius), push)) {
                px += push[0];
                py += push[1];
                pz += push[2];
            }
            for (int i = 0; i < MAX_CONTACTS && rx * rx + ry * ry + rz * rz > 1e-18; i++) {
                int result = query.cast(scene, px, py, pz, hx, hy, hz, rx, ry, rz, 1, null, contact);
                if (result == CollisionQuery.NONE) {
                    px += rx;
                    py += ry;
                    pz += rz;
                    break;
                }
                if (result == CollisionQuery.MISS) break;
                px += rx * contact.t;
                py += ry * contact.t;
                pz += rz * contact.t;
                double left = 1 - contact.t;
                rx *= left;
                ry *= left;
                rz *= left;
                double nx = contact.nx, ny = contact.ny, nz = contact.nz;
                double vn = vx * nx + vy * ny + vz * nz;
                if (vn < 0) {
                    vx -= (1 + bounce) * vn * nx;
                    vy -= (1 + bounce) * vn * ny;
                    vz -= (1 + bounce) * vn * nz;
                }
                double along = vx * nx + vy * ny + vz * nz;
                vx = along * nx + (vx - along * nx) * keep;
                vy = along * ny + (vy - along * ny) * keep;
                vz = along * nz + (vz - along * nz) * keep;
                double rn = rx * nx + ry * ny + rz * nz;
                if (rn < 0) {
                    rx -= (1 + bounce) * rn * nx;
                    ry -= (1 + bounce) * rn * ny;
                    rz -= (1 + bounce) * rn * nz;
                }
            }
        } finally {
            query.reset();
        }
        position.set(worldToTrail.transformPosition(scratch.set((float) px, (float) py, (float) pz)));
        velocity.set(worldToTrail.transformDirection(scratch.set((float) vx, (float) vy, (float) vz)));
    }
}
