package com.lowdragmc.photon.client.collision;

import org.jetbrains.annotations.Nullable;

/** Where and what a cast hit. Mutable scratch filled by {@link CollisionQuery}. */
public final class Contact {
    public static final byte BLOCK = 0;
    public static final byte FLUID = 1;
    public static final byte ENTITY = 2;
    public static final byte COLLIDER = 3;

    /** Fraction of the cast motion travelled before the contact, in [0, 1]. */
    public double t;
    /** Unit surface normal, facing the body. */
    public double nx, ny, nz;
    /** The contact point on the surface, world space. */
    public double px, py, pz;
    /** How fast the surface itself moves, blocks per tick (a moving entity or collider; 0 for blocks). */
    public double vx, vy, vz;
    public byte kind;
    /** Identifies the target: a packed block position, an entity id, or a collider index. */
    public long key;
    /** The block state, the entity, or the collider that was hit. */
    @Nullable
    public Object payload;
    /** The target only reports overlaps and does not block the body. */
    public boolean trigger;

    /** One identity per target, comparable across ticks. */
    public long identity() {
        return key * 4 + kind;
    }
}
