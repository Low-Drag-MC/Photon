package com.lowdragmc.photon.client.collision;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * A particle's contact with the world, as reported to sub-emitters and collision listeners. Immutable: it
 * outlives the step that produced it (listeners run on the game thread at the end of the emitter's tick).
 *
 * @param kind   one of {@link Contact#BLOCK}, {@link Contact#FLUID}, {@link Contact#ENTITY}, {@link Contact#COLLIDER}
 * @param target the block state, the entity or the collider object that was hit
 * @param key    which target: a block position ({@code BlockPos.asLong}), an entity id or a collider's id
 * @param speed  how fast the particle was moving into the surface, blocks per tick (0 for triggers and deaths)
 */
public record CollisionEvent(Type type, Vector3f position, Vector3f normal, byte kind, @Nullable Object target, long key,
                             float speed) {

    public enum Type {
        /** hit a solid surface it was not already resting on */
        IMPACT,
        /** removed by a collision rule (removed when collided, kill speeds or impacts, kill on trigger enter) */
        DEATH,
        TRIGGER_ENTER,
        TRIGGER_EXIT
    }

    public static CollisionEvent of(Type type, Contact contact, double speed) {
        return new CollisionEvent(type, new Vector3f((float) contact.px, (float) contact.py, (float) contact.pz),
                new Vector3f((float) contact.nx, (float) contact.ny, (float) contact.nz), contact.kind,
                contact.payload, contact.key, (float) speed);
    }

    /** One value per target, comparable across ticks and with {@link Contact#identity()}. */
    public long identity() {
        return key * 4 + kind;
    }

    public Vector3f position() {
        return new Vector3f(position);
    }

    public Vector3f normal() {
        return new Vector3f(normal);
    }
}
