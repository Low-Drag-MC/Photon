package com.lowdragmc.photon.client.light;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * A region of fog the dynamic lights scatter in: a box or a sphere of unit size at {@link #position}, stretched and
 * turned by {@link #axes}. Mutable, so its owner can move it every frame.
 */
@OnlyIn(Dist.CLIENT)
public class FogVolume {
    public enum Shape { BOX, SPHERE }

    public Shape shape = Shape.BOX;
    /** World position of the centre. */
    public final Vector3d position = new Vector3d();
    /** The shape's local axes in world blocks: rotation times size. */
    public final Matrix3f axes = new Matrix3f();
    /** How thick the fog is: 1 is a light mist. */
    public float density = 1f;
    /** The share of the shape, from its surface inwards, over which the fog thins out to nothing. */
    public float edgeFalloff = 0.3f;
    /** The colour the fog scatters light with. */
    public final Vector3f color = new Vector3f(1f, 1f, 1f);
    /** How much the fog also hides what lies behind it, 0 to 1. */
    public float absorption = 0.5f;
    /** Light the fog gives off on its own, scaled by its density. */
    public final Vector3f emission = new Vector3f();
    /** How much moving noise breaks the fog up, 0 to 1, and the size of its blobs in blocks. */
    public float noise = 0f;
    public float noiseScale = 2f;
    /** How far the noise has drifted, in world blocks. */
    public final Vector3f noiseOffset = new Vector3f();
    public boolean enabled = true;

    public FogVolume at(double x, double y, double z) {
        position.set(x, y, z);
        return this;
    }

    /** An unrotated box or sphere of this size in blocks. */
    public FogVolume size(float x, float y, float z) {
        axes.scaling(x, y, z);
        return this;
    }

    /** Turns the shape about its centre, after {@link #size}. */
    public FogVolume rotate(Quaternionfc rotation) {
        axes.rotateLocal(rotation);
        return this;
    }

    public FogVolume shape(Shape shape) {
        this.shape = shape;
        return this;
    }

    public FogVolume density(float density) {
        this.density = density;
        return this;
    }

    public FogVolume color(float r, float g, float b) {
        color.set(r, g, b);
        return this;
    }

    public FogVolume absorption(float absorption) {
        this.absorption = absorption;
        return this;
    }

    public FogVolume emission(float r, float g, float b) {
        emission.set(r, g, b);
        return this;
    }

    public FogVolume edgeFalloff(float edgeFalloff) {
        this.edgeFalloff = edgeFalloff;
        return this;
    }

    /** Breaks the fog up into blobs about {@code scale} blocks across; move {@link #noiseOffset} to make it drift. */
    public FogVolume noise(float strength, float scale) {
        this.noise = strength;
        this.noiseScale = scale;
        return this;
    }

    public FogVolume set(FogVolume other) {
        shape = other.shape;
        position.set(other.position);
        axes.set(other.axes);
        density = other.density;
        edgeFalloff = other.edgeFalloff;
        color.set(other.color);
        absorption = other.absorption;
        emission.set(other.emission);
        noise = other.noise;
        noiseScale = other.noiseScale;
        noiseOffset.set(other.noiseOffset);
        enabled = other.enabled;
        return this;
    }

    public FogVolume reset() {
        shape = Shape.BOX;
        position.set(0);
        axes.identity();
        density = 1f;
        edgeFalloff = 0.3f;
        color.set(1f);
        absorption = 0.5f;
        emission.set(0f);
        noise = 0f;
        noiseScale = 2f;
        noiseOffset.set(0f);
        enabled = true;
        return this;
    }
}
