package com.lowdragmc.photon.client.collision;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.LongAdder;

/** Everything one emitter's particles may collide with during one tick, frozen so any thread can read it. */
public final class CollisionScene {
    public static final EntityTarget[] NO_ENTITIES = new EntityTarget[0];
    public static final ColliderTarget[] NO_COLLIDERS = new ColliderTarget[0];
    /** Targets moving farther than this in a tick jumped (teleport, editor drag): they are taken as still. */
    public static final double MAX_TARGET_MOTION = 4;

    @Nullable
    public final BlockBoxSource blocks;
    /** {@link BlockBoxSource#BLOCKS} and/or {@link BlockBoxSource#FLUIDS}. */
    public final int blockMask;
    @Nullable
    public final BlockFilter blockFilter;
    public final EntityTarget[] entities;
    public final ColliderTarget[] colliders;
    public final Stats stats;
    public final boolean hasTriggers;
    /** Some entity or collider moves: a body standing still can still be hit. */
    public final boolean hasMovingTargets;

    public CollisionScene(@Nullable BlockBoxSource blocks, int blockMask, @Nullable BlockFilter blockFilter,
                          EntityTarget[] entities, ColliderTarget[] colliders, Stats stats) {
        this.blocks = blocks;
        this.blockMask = blockMask;
        this.blockFilter = blockFilter;
        this.entities = entities;
        this.colliders = colliders;
        this.stats = stats;
        boolean triggers = false, moving = false;
        for (var entity : entities) {
            triggers |= entity.trigger();
            moving |= entity.motionX() != 0 || entity.motionY() != 0 || entity.motionZ() != 0;
        }
        for (var collider : colliders) {
            triggers |= collider.trigger;
            moving |= collider.mx != 0 || collider.my != 0 || collider.mz != 0;
        }
        this.hasTriggers = triggers;
        this.hasMovingTargets = moving;
    }

    @Nullable
    public EntityTarget entity(int id) {
        for (var entity : entities) if (entity.id() == id) return entity;
        return null;
    }

    @Nullable
    public ColliderTarget collider(long index) {
        for (var collider : colliders) if (collider.index == index) return collider;
        return null;
    }

    /** The entity or collider behind a {@link Contact#identity()}, if it is in this scene. */
    @Nullable
    public Object targetOf(long identity) {
        long key = identity >> 2;
        int kind = (int) (identity & 3);
        if (kind == Contact.ENTITY) {
            for (var entity : entities) if (entity.id() == key) return entity.entity();
        } else if (kind == Contact.COLLIDER) {
            for (var collider : colliders) if (collider.index == key) return collider.source;
        }
        return null;
    }

    /** Counters, safe to bump from particle workers. */
    public static final class Stats {
        public final LongAdder casts = new LongAdder();
        public final LongAdder boxTests = new LongAdder();
        public final LongAdder contacts = new LongAdder();
        public final LongAdder depenetrations = new LongAdder();
        public final LongAdder misses = new LongAdder();

        public void reset() {
            casts.reset();
            boxTests.reset();
            contacts.reset();
            depenetrations.reset();
            misses.reset();
        }
    }
}
