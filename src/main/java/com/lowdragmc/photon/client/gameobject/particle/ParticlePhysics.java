package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.photon.client.collision.BlockBoxSource;
import com.lowdragmc.photon.client.collision.CollisionEvent;
import com.lowdragmc.photon.client.collision.CollisionQuery;
import com.lowdragmc.photon.client.collision.CollisionScene;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.client.collision.ContactSolver;
import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.function.LongConsumer;

/** One collision step of a {@link TileParticle}: maps it to a {@link ContactSolver.Body} and back. Any thread. */
final class ParticlePhysics {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    /** How far a stuck particle reaches back into its surface to find it still there. */
    private static final double PROBE = 0.05;

    private ParticlePhysics() {
    }

    static void step(TileParticle particle, ParticleEmitter emitter, CollisionScene scene, float dt) {
        var scratch = SCRATCH.get();
        scratch.bind(particle, emitter, scene);
        try {
            solve(particle, scene, scratch, dt);
        } finally {
            // the thread-local scratch must not keep a particle, scene or entity (and their level) alive
            scratch.bind(null, null, null);
            scratch.contact.payload = null;
            CollisionQuery.get().reset();
        }
    }

    private static void solve(TileParticle particle, CollisionScene scene, Scratch scratch, float dt) {
        var physics = particle.runtime.physics;
        var collision = physics.collision;
        var setting = collision.config();
        var body = scratch.body;

        var center = particle.collisionCenter(scratch.center);
        double extent = setting.getShape() == CollisionSetting.Shape.Point ? 0 : Math.max(0, setting.getRadiusScale()) * 0.5;
        var space = particle.getSpaceTransform();
        var scale = (space.properties() & Matrix4fc.PROPERTY_IDENTITY) != 0 ? scratch.scale.set(1) : space.getScale(scratch.scale);
        body.cx = center.x;
        body.cy = center.y;
        body.cz = center.z;
        body.hx = Math.abs(particle.sizeX * scale.x) * extent;
        body.hy = Math.abs(particle.sizeY * scale.y) * extent;
        body.hz = Math.abs(particle.sizeZ * scale.z) * extent;
        var velocity = particle.getRealVelocity();
        body.vx = velocity.x;
        body.vy = velocity.y;
        body.vz = velocity.z;
        var stored = particle.getSpaceTransform().transformDirection(
                scratch.vector3.set(particle.velocityX, particle.velocityY, particle.velocityZ));
        body.svx = stored.x;
        body.svy = stored.y;
        body.svz = stored.z;
        if (particle.stuckKind >= 0 && holdStuck(particle, scene, scratch, dt)) return;
        body.previousAxes = particle.contactAxes;
        body.previousTarget = particle.contactTarget;
        body.bounces = particle.bounces;
        body.impacts = particle.impacts;
        body.insideTriggerCount = Math.min(particle.triggerCount, body.insideTriggers.length);
        if (body.insideTriggerCount > 0) System.arraycopy(particle.triggers, 0, body.insideTriggers, 0, body.insideTriggerCount);

        var params = scratch.params;
        params.bounceChance = physics.getBounceChance(particle);
        params.bounceRate = physics.getBounceRate(particle);
        params.bounceSpread = physics.getBounceSpreadRate(particle);
        params.dampen = collision.getDampen(particle);
        params.lifetimeLoss = collision.getLifetimeLoss(particle);
        params.minKillSpeed = setting.response.getMinKillSpeed();
        params.maxKillSpeed = setting.response.getMaxKillSpeed();
        params.minImpactSpeed = setting.events.getMinImpactSpeed();
        params.gravityStep = 0.04 * physics.getGravity(particle) * dt;
        params.maxContacts = Math.max(1, setting.advanced.getMaxContacts());
        params.maxBounces = setting.response.getMaxBounces();
        params.killAfterImpacts = setting.response.getKillAfterImpacts();
        params.stick = setting.response.isStick();
        params.removeOnContact = physics.isRemovedWhenCollided();
        params.killOnTriggerEnter = setting.isKillOnTriggerEnter();
        params.depenetrate = setting.advanced.isDepenetrate();
        params.maxDepenetration = setting.advanced.getMaxDepenetration();

        ContactSolver.step(CollisionQuery.get(), scene, body, params, dt, particle.randomSource, scratch,
                scratch.contact, scratch.vector);

        if (body.moveX != 0 || body.moveY != 0 || body.moveZ != 0) {
            var moveLocal = particle.getSpaceTransformInverse().transformDirection(
                    scratch.vector3.set((float) body.moveX, (float) body.moveY, (float) body.moveZ));
            particle.setSimPos(particle.localX + moveLocal.x, particle.localY + moveLocal.y,
                    particle.localZ + moveLocal.z, false);
        }
        particle.setInternalVelocity(particle.getSpaceTransformInverse().transformDirection(
                scratch.vector3.set((float) body.svx, (float) body.svy, (float) body.svz)));
        particle.contactAxes = body.axes;
        particle.contactTarget = body.target;
        particle.bounces = body.bounces;
        particle.impacts = body.impacts;
        if (body.touched) particle.collided = true;
        if (body.lifetimeLost > 0 && particle.lifetime > 0) {
            particle.age += (float) (particle.lifetime * body.lifetimeLost);
        }
        if (scene.hasTriggers || particle.triggerCount > 0) {
            scratch.updateTriggers(body);
        }
        if (body.killed) {
            particle.setRemoved(true);
            return;
        }
        if (body.stuck) {
            stick(particle, scene, body, scratch.vector);
            return;
        }
        particle.applyForces(dt, body.touched, body.contactNx, body.contactNy, body.contactNz,
                body.contactVx, body.contactVy, body.contactVz);
    }

    /** Remembers what the particle stuck to, and where its collision box came to rest on it. */
    private static void stick(TileParticle particle, CollisionScene scene, ContactSolver.Body body, double[] local) {
        double x = body.cx + body.moveX, y = body.cy + body.moveY, z = body.cz + body.moveZ;
        switch (body.stuckKind) {
            case Contact.ENTITY -> {
                var entity = scene.entity((int) body.stuckKey);
                if (entity == null) return;
                x -= (entity.minX() + entity.maxX()) / 2;
                y -= (entity.minY() + entity.maxY()) / 2;
                z -= (entity.minZ() + entity.maxZ()) / 2;
            }
            case Contact.COLLIDER -> {
                var collider = scene.collider(body.stuckKey);
                if (collider == null || !collider.toLocal(x, y, z, local)) return;
                x = local[0];
                y = local[1];
                z = local[2];
            }
            default -> particle.stuckVersion = scene.blocks instanceof CollisionWorld world ? world.version() : 0;
        }
        particle.stuckKind = body.stuckKind;
        particle.stuckKey = body.stuckKey;
        particle.stuckX = (float) x;
        particle.stuckY = (float) y;
        particle.stuckZ = (float) z;
        particle.stuckNx = (float) body.contactNx;
        particle.stuckNy = (float) body.contactNy;
        particle.stuckNz = (float) body.contactNz;
        particle.stuckHx = (float) body.hx;
        particle.stuckHy = (float) body.hy;
        particle.stuckHz = (float) body.hz;
    }

    /** Keeps a stuck particle with what it stuck to: false once that is gone, and it moves (falls) again. */
    private static boolean holdStuck(TileParticle particle, CollisionScene scene, Scratch scratch, float dt) {
        double x, y, z, vx = 0, vy = 0, vz = 0;
        switch (particle.stuckKind) {
            case Contact.ENTITY -> {
                var entity = scene.entity((int) particle.stuckKey);
                if (entity == null) return unstick(particle);
                x = (entity.minX() + entity.maxX()) / 2 + particle.stuckX;
                y = (entity.minY() + entity.maxY()) / 2 + particle.stuckY;
                z = (entity.minZ() + entity.maxZ()) / 2 + particle.stuckZ;
                vx = entity.motionX();
                vy = entity.motionY();
                vz = entity.motionZ();
            }
            case Contact.COLLIDER -> {
                var collider = scene.collider(particle.stuckKey);
                if (collider == null) return unstick(particle);
                collider.toWorld(particle.stuckX, particle.stuckY, particle.stuckZ, scratch.vector);
                x = scratch.vector[0];
                y = scratch.vector[1];
                z = scratch.vector[2];
                vx = collider.mx;
                vy = collider.my;
                vz = collider.mz;
            }
            default -> {
                int mask = particle.stuckKind == Contact.FLUID ? BlockBoxSource.FLUIDS : BlockBoxSource.BLOCKS;
                if (scene.blocks == null || (scene.blockMask & mask) == 0) return unstick(particle);
                x = particle.stuckX;
                y = particle.stuckY;
                z = particle.stuckZ;
                // a block changed somewhere: is the surface it lies on still there?
                if (scene.blocks instanceof CollisionWorld world && world.version() != particle.stuckVersion) {
                    double dx = -particle.stuckNx * PROBE, dy = -particle.stuckNy * PROBE, dz = -particle.stuckNz * PROBE;
                    double hx = particle.stuckHx, hy = particle.stuckHy, hz = particle.stuckHz;
                    var query = CollisionQuery.get();
                    query.prepare(scene, x, y, z, hx, hy, hz, dx, dy, dz);
                    if (query.cast(scene, x, y, z, hx, hy, hz, dx, dy, dz, dt, null, scratch.contact) != CollisionQuery.HIT) {
                        return unstick(particle);
                    }
                    particle.stuckVersion = world.version();
                }
            }
        }
        // the box stays where it stuck: the position moves under this tick's noise the other way
        var inverse = particle.getSpaceTransformInverse();
        var noise = particle.collisionNoise(scratch.scale);
        var local = inverse.transformPosition(scratch.vector3.set((float) x, (float) y, (float) z)).sub(noise);
        particle.setSimPos(local.x, local.y, local.z, false);
        particle.setInternalVelocity(inverse.transformDirection(scratch.vector3.set((float) vx, (float) vy, (float) vz)));
        return true;
    }

    private static boolean unstick(TileParticle particle) {
        particle.stuckKind = -1;
        return false;
    }

    private static final class Scratch implements ContactSolver.Events, LongConsumer {
        final ContactSolver.Body body = new ContactSolver.Body();
        final ContactSolver.Params params = new ContactSolver.Params();
        final Contact contact = new Contact();
        final double[] vector = new double[3];
        final Vector3f center = new Vector3f();
        final Vector3f scale = new Vector3f();
        final Vector3f vector3 = new Vector3f();
        final long[] inside = new long[8];
        int insideCount;
        TileParticle particle;
        ParticleEmitter emitter;
        CollisionScene scene;

        void bind(TileParticle particle, ParticleEmitter emitter, CollisionScene scene) {
            this.particle = particle;
            this.emitter = emitter;
            this.scene = scene;
        }

        private boolean wanted() {
            return particle.runtime.subEmitters.isEnable() || emitter.wantsCollisionEvents();
        }

        private void dispatch(CollisionEvent event, SubEmittersSetting.Event subEvent) {
            if (particle.runtime.subEmitters.isEnable()) {
                particle.runtime.subEmitters.triggerEvent(particle, subEvent, event);
            }
            emitter.reportCollision(particle, event);
        }

        @Override
        public void impact(Contact contact, double speed) {
            boolean first = !particle.isFirstCollision;
            particle.isFirstCollision = true;
            if (!wanted()) return;
            var event = CollisionEvent.of(CollisionEvent.Type.IMPACT, contact, speed);
            dispatch(event, SubEmittersSetting.Event.Collision);
            if (first && particle.runtime.subEmitters.isEnable()) {
                particle.runtime.subEmitters.triggerEvent(particle, SubEmittersSetting.Event.FirstCollision, event);
            }
        }

        @Override
        public void killed(Contact contact) {
            if (!wanted()) return;
            dispatch(CollisionEvent.of(CollisionEvent.Type.DEATH, contact, 0), SubEmittersSetting.Event.Death);
        }

        @Override
        public void triggerEnter(Contact contact) {
            if (!wanted()) return;
            dispatch(CollisionEvent.of(CollisionEvent.Type.TRIGGER_ENTER, contact, 0), SubEmittersSetting.Event.TriggerEnter);
        }

        @Override
        public void accept(long identity) {
            if (insideCount < inside.length) inside[insideCount++] = identity;
        }

        /** Re-takes the triggers the particle is inside, reporting those it left and those it is in without having crossed in. */
        void updateTriggers(ContactSolver.Body body) {
            insideCount = 0;
            double x = body.cx + body.moveX, y = body.cy + body.moveY, z = body.cz + body.moveZ;
            if (scene.hasTriggers) {
                CollisionQuery.get().overlapTriggers(scene, x, y, z, body.hx, body.hy, body.hz, this);
            }
            if (!body.killed) {
                for (int i = 0; i < particle.triggerCount; i++) {
                    if (!contains(particle.triggers[i])) report(CollisionEvent.Type.TRIGGER_EXIT, particle.triggers[i], x, y, z);
                }
                for (int i = 0; i < body.crossedCount(); i++) {
                    long identity = body.crossed(i);
                    if (!contains(identity) && !wasInside(identity)) report(CollisionEvent.Type.TRIGGER_EXIT, identity, x, y, z);
                }
                // born inside, or the trigger came to it
                for (int i = 0; i < insideCount && !body.killed; i++) {
                    long identity = inside[i];
                    if (wasInside(identity) || body.excluded(identity)) continue;
                    report(CollisionEvent.Type.TRIGGER_ENTER, identity, x, y, z);
                    if (params.killOnTriggerEnter) {
                        body.killed = true;
                        report(CollisionEvent.Type.DEATH, identity, x, y, z);
                    }
                }
            }
            if (insideCount == 0) {
                particle.triggerCount = 0;
                return;
            }
            if (particle.triggers == null || particle.triggers.length < insideCount) particle.triggers = new long[inside.length];
            System.arraycopy(inside, 0, particle.triggers, 0, insideCount);
            particle.triggerCount = insideCount;
        }

        private boolean contains(long identity) {
            for (int i = 0; i < insideCount; i++) if (inside[i] == identity) return true;
            return false;
        }

        private boolean wasInside(long identity) {
            for (int i = 0; i < particle.triggerCount; i++) if (particle.triggers[i] == identity) return true;
            return false;
        }

        private void report(CollisionEvent.Type type, long identity, double x, double y, double z) {
            if (!wanted()) return;
            var event = new CollisionEvent(type, new Vector3f((float) x, (float) y, (float) z), new Vector3f(),
                    (byte) (identity & 3), scene.targetOf(identity), identity >> 2, 0);
            dispatch(event, switch (type) {
                case TRIGGER_ENTER -> SubEmittersSetting.Event.TriggerEnter;
                case TRIGGER_EXIT -> SubEmittersSetting.Event.TriggerExit;
                default -> SubEmittersSetting.Event.Death;
            });
        }
    }
}
