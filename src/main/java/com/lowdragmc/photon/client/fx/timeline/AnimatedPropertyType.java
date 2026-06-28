package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.FXObject;

/**
 * A registerable kind of animatable value on an {@link FXObject} (registry {@code photon:animated_property}).
 * Stateless singleton: each concrete type declares a {@code @LDLRegisterClient}-annotated
 * {@code public static final} instance registered directly into the registry (no creator/cache). It
 * describes how many channels it has, how to read the target's current value ({@link #capture}) and how
 * to write a sampled value back ({@link #apply}). Which types a given object supports is declared by its
 * {@code FXObjectType#animatableProperties()}.
 * <p>
 * An {@link AnimatedProperty} instance references its type by registry {@link #name()} for serialization.
 */
public interface AnimatedPropertyType {
    /** This type's registry name (reverse-looked-up from the singleton registry). */
    default String name() {
        return PhotonRegistries.ANIMATED_PROPERTIES.getKey(this);
    }

    /** Number of independently-keyframed channels (e.g. 3 for an xyz vector, 1 for a scalar). */
    int channelCount();

    /** Short sub-label for a channel (e.g. "x"); used for the sub-property rows. */
    String channelKey(int channel);

    /** Read the target's current value for this property (length {@link #channelCount()}). */
    float[] capture(FXObject target);

    /** Write a sampled value (length {@link #channelCount()}) onto the target. */
    void apply(FXObject target, float[] values);

    /** Default editor display range given the captured base; default spans the base values ±1. */
    default float[] defaultRange(float[] base) {
        var lo = Float.MAX_VALUE;
        var hi = -Float.MAX_VALUE;
        for (var v : base) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new float[]{lo - 1, hi + 1};
    }

    /** Whether values are angles in degrees (enables the rotation interpolation mode). */
    default boolean angular() {
        return false;
    }

    /** Sample all channels at {@code time} (ticks). Default = raw per-channel curve sampling. */
    default float[] sample(AnimatedProperty property, float time) {
        var values = new float[channelCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = AnimatedProperty.sampleChannel(property.channel(i), time);
        }
        return values;
    }
}
