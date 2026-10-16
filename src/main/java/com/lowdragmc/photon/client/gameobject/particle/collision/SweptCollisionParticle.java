package com.lowdragmc.photon.client.gameobject.particle.collision;

import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSettings;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SweptCollisionParticle extends TileParticle {
    private Map<CollisionFrame.Target, Long> seen;
    private int bounces;

    public SweptCollisionParticle(ParticleEmitter emitter, ParticleConfig config) {
        super(emitter, config);
    }

    @Override
    protected void updatePositionAndInternalVelocity(float dt) {
        ParticleEmitter owner = (ParticleEmitter) emitter;
        if (owner.config.physics.collision.backend != CollisionSettings.Backend.CACHED_SWEEP || !runtime.physics.isEnable() || !runtime.physics.hasCollision() || emitter.getLevel() == null) {
            super.updatePositionAndInternalVelocity(dt);
            return;
        }
        if (dt <= 0 || !Float.isFinite(dt)) return;
        CollisionSettings settings = owner.config.physics.collision;
        if (seen == null) seen = new HashMap<>();
        Vector3f velocity = getRealVelocity();
        Vec3 remaining = new Vec3(velocity.x * dt, velocity.y * dt, velocity.z * dt);
        if (!Double.isFinite(remaining.lengthSqr())) { setRemoved(true); return; }
        Vector3f worldStart = getWorldPos(1);
        Vec3 start = new Vec3(worldStart.x, worldStart.y, worldStart.z);
        AABB bounds = settings.rayProbe ? new AABB(start, start) : getRealBoundingBox(1);
        Set<CollisionFrame.Target> stepHits = new HashSet<>();
        int contacts = 0;
        double timeLeft = dt;
        boolean overlap = settings.detectStationaryOverlaps && remaining.lengthSqr() <= 1e-16;
        while (remaining.lengthSqr() > 1e-16 || overlap) {
            long tick = owner.collisionRuntime().collisionTick();
            CollisionFrame.Hit hit = owner.collisionRuntime().frame().nearest(bounds, remaining, timeLeft, settings, owner.collisionRuntime().ownerId(), target -> {
                return stepHits.contains(target) || owner.collisionRuntime().isSourceEntity(target);
            });
            if (hit == null) {
                start = start.add(remaining);
                break;
            }
            Vec3 advance = remaining.scale(hit.time());
            start = start.add(advance);
            bounds = bounds.move(advance);
            Vec3 normal = hit.normal();
            Vec3 point = start.subtract(normal.multiply(bounds.getXsize() * 0.5, bounds.getYsize() * 0.5, bounds.getZsize() * 0.5));
            AABB targetBounds = hit.candidate().bounds();
            if (settings.sweepEntityMotion && hit.candidate().target().kind() == CollisionFrame.Kind.ENTITY) targetBounds = targetBounds.move(hit.candidate().movement().scale(-timeLeft * (1 - hit.time())));
            point = new Vec3(Math.clamp(point.x, targetBounds.minX, targetBounds.maxX), Math.clamp(point.y, targetBounds.minY, targetBounds.maxY), Math.clamp(point.z, targetBounds.minZ, targetBounds.maxZ));
            setWorldPosition(start);
            CollisionSettings.Response response = settings.response;
            boolean inheritResponse = response == CollisionSettings.Response.INHERIT;
            if (inheritResponse) {
                if (runtime.physics.isRemovedWhenCollided()) response = CollisionSettings.Response.REMOVE;
                else response = randomSource.nextFloat() < runtime.physics.getBounceChance(this) ? CollisionSettings.Response.BOUNCE : CollisionSettings.Response.STOP;
            }
            if (response == CollisionSettings.Response.BOUNCE && settings.maxBounces > 0 && bounces >= settings.maxBounces) response = CollisionSettings.Response.STOP;
            boolean first = !isFirstCollision;
            isFirstCollision = true;
            Long previous = seen.get(hit.candidate().target());
            boolean notify = !settings.deduplicateParticleHits || previous == null || settings.contactCooldownTicks > 0 && tick - previous >= settings.contactCooldownTicks;
            if (notify) {
                seen.put(hit.candidate().target(), tick);
                if (owner.collisionRuntime().recordHit(hit, point)) CollisionEffects.contact(owner, this, hit, point, first, response == CollisionSettings.Response.REMOVE);
            }
            if (response == CollisionSettings.Response.REMOVE) { setRemoved(true); return; }
            if (response == CollisionSettings.Response.STOP) {
                collided = true;
                setInternalVelocity(new Vector3f());
                remaining = Vec3.ZERO;
                break;
            }
            remaining = remaining.scale(Math.max(0, 1 - hit.time()));
            timeLeft *= Math.max(0, 1 - hit.time());
            if (response == CollisionSettings.Response.PIERCE) stepHits.add(hit.candidate().target());
            if (response == CollisionSettings.Response.SLIDE) {
                remaining = remaining.subtract(normal.scale(Math.min(0, remaining.dot(normal))));
                Vector3f stored = getSpaceTransform().transformDirection(new Vector3f(velocityX, velocityY, velocityZ));
                Vec3 sliding = new Vec3(stored.x, stored.y, stored.z);
                sliding = sliding.subtract(normal.scale(Math.min(0, sliding.dot(normal))));
                setInternalVelocity(getSpaceTransformInverse().transformDirection(new Vector3f((float) sliding.x, (float) sliding.y, (float) sliding.z)));
                Vec3 separation = normal.scale(0.00001);
                start = start.add(separation);
                bounds = bounds.move(separation);
            }
            if (response == CollisionSettings.Response.BOUNCE) {
                bounces++;
                double restitution = Math.max(0, Math.min(1, inheritResponse ? runtime.physics.getBounceRate(this) : settings.restitution));
                remaining = remaining.subtract(normal.scale(2 * remaining.dot(normal))).scale(restitution);
                Vector3f stored = getSpaceTransform().transformDirection(new Vector3f(velocityX, velocityY, velocityZ));
                Vec3 reflected = new Vec3(stored.x, stored.y, stored.z);
                reflected = reflected.subtract(normal.scale(2 * reflected.dot(normal))).scale(restitution);
                if (inheritResponse) {
                    double spread = runtime.physics.getBounceSpreadRate(this);
                    if (spread > 0) reflected = reflected.add(normal.x == 0 ? spread * randomSource.nextGaussian() : 0, normal.y == 0 ? spread * randomSource.nextGaussian() : 0, normal.z == 0 ? spread * randomSource.nextGaussian() : 0);
                }
                setInternalVelocity(getSpaceTransformInverse().transformDirection(new Vector3f((float) reflected.x, (float) reflected.y, (float) reflected.z)));
                Vec3 separation = normal.scale(0.00001);
                start = start.add(separation);
                bounds = bounds.move(separation);
            }
            contacts++;
            if (settings.maxHitsPerStep > 0 && contacts >= settings.maxHitsPerStep) { remaining = Vec3.ZERO; break; }
        }
        setWorldPosition(start);
        applyForces(owner, dt);
    }

    private void setWorldPosition(Vec3 position) {
        Vector3f local = new Vector3f((float) position.x, (float) position.y, (float) position.z).mulPosition(getSpaceTransformInverse());
        setSimPos(local, false);
    }

    private void applyForces(ParticleEmitter owner, float dt) {
        if (runtime.externalForces.isEnable()) {
            float multiplier = runtime.externalForces.getMultiplier(this);
            if (multiplier != 0) {
                Vector3f position = getWorldPos(1);
                Vector3f velocity = getSpaceTransform().transformDirection(new Vector3f(velocityX, velocityY, velocityZ));
                for (var field : owner.getActiveForceFields()) field.apply(position, velocity, Math.max(sizeX, Math.max(sizeY, sizeZ)), dt, this, multiplier);
                setInternalVelocity(getSpaceTransformInverse().transformDirection(velocity));
            }
        }
        float gravity = runtime.physics.getGravity(this);
        if (gravity != 0) addInternalVelocity(getSpaceTransformInverse().transformDirection(new Vector3f(0, -gravity * 0.04f * dt, 0)));
        float friction = (float) Math.pow(collided ? runtime.physics.getFriction(this) * runtime.physics.getCollidedFriction(this) : runtime.physics.getFriction(this), dt);
        velocityX *= friction;
        velocityY *= friction;
        velocityZ *= friction;
    }
}

