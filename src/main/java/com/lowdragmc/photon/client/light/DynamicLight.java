package com.lowdragmc.photon.client.light;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * A world-space light. Colour is linear and scaled by {@link #intensity}; the light fades to zero at
 * {@link #range} blocks. Mutable, so its owner can move it every frame.
 */
@OnlyIn(Dist.CLIENT)
public class DynamicLight {
    public enum Type { POINT, SPOT }

    public Type type = Type.POINT;
    public final Vector3d position = new Vector3d();
    public final Vector3f color = new Vector3f(1f, 1f, 1f);
    public float intensity = 8f;
    public float range = 10f;
    /** Spot only: the cone's world-space direction, any length. */
    public final Vector3f direction = new Vector3f(0f, -1f, 0f);
    /** Spot only: half-angles in degrees, full brightness inside the inner one and none past the outer. */
    public float innerAngle = 20f;
    public float outerAngle = 30f;
    /** Radius of the emitting sphere in blocks: 0 casts hard shadows, larger values softer ones. */
    public float sourceRadius = 0.15f;
    public boolean castShadows = true;
    public boolean enabled = true;

    public DynamicLight at(double x, double y, double z) {
        position.set(x, y, z);
        return this;
    }

    public DynamicLight color(float r, float g, float b) {
        color.set(r, g, b);
        return this;
    }

    public DynamicLight intensity(float intensity) {
        this.intensity = intensity;
        return this;
    }

    public DynamicLight range(float range) {
        this.range = range;
        return this;
    }

    public DynamicLight sourceRadius(float sourceRadius) {
        this.sourceRadius = sourceRadius;
        return this;
    }

    public DynamicLight shadows(boolean castShadows) {
        this.castShadows = castShadows;
        return this;
    }

    public DynamicLight point() {
        this.type = Type.POINT;
        return this;
    }

    public DynamicLight spot(float innerAngle, float outerAngle) {
        this.type = Type.SPOT;
        this.innerAngle = innerAngle;
        this.outerAngle = outerAngle;
        return this;
    }

    public DynamicLight direction(float x, float y, float z) {
        direction.set(x, y, z);
        return this;
    }

    public DynamicLight set(DynamicLight other) {
        type = other.type;
        position.set(other.position);
        color.set(other.color);
        intensity = other.intensity;
        range = other.range;
        direction.set(other.direction);
        innerAngle = other.innerAngle;
        outerAngle = other.outerAngle;
        sourceRadius = other.sourceRadius;
        castShadows = other.castShadows;
        enabled = other.enabled;
        return this;
    }

    public DynamicLight reset() {
        type = Type.POINT;
        position.set(0);
        color.set(1f);
        intensity = 8f;
        range = 10f;
        direction.set(0f, -1f, 0f);
        innerAngle = 20f;
        outerAngle = 30f;
        sourceRadius = 0.15f;
        castShadows = true;
        enabled = true;
        return this;
    }
}
