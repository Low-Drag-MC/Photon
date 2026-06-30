package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.FXObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;

/**
 * A registerable kind of animatable value on an {@link FXObject} (registry {@code photon:animated_property}).
 * Stateless singleton: each concrete type declares a {@code @LDLRegisterClient}-annotated
 * {@code public static final} instance registered directly into the registry (no creator/cache). It
 * describes how many channels it has, how to read the target's current value ({@link #capture}) and how
 * to write a sampled value back ({@link #apply}). Which types a given object supports is declared by its
 * {@code FXObjectType#animatableProperties()}.
 * <p>
 * The type is the authority for the lifecycle of an {@link AnimatedProperty}: it {@link #create}s,
 * {@link #serialize}s/{@link #deserialize}s and optionally {@link #inspect}s it. Per-type extra state
 * (e.g. rotation's interp mode) lives on an {@link AnimatedProperty} subclass that the type builds.
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

    /** Clamp a keyframe value to this type's valid range (e.g. speed {@code >= 0}). Default: no clamp. */
    default float clampValue(float value) {
        return value;
    }

    /** A pinned lower bound for the curve's display range (e.g. speed's 0): the bottom bound can't be
     *  scrolled or edited below it and the range editor scales only the top. Null = free range. */
    @Nullable
    default Float fixedRangeMin() {
        return null;
    }

    /** Sample all channels at {@code time} (ticks). Default = raw per-channel curve sampling. */
    default float[] sample(AnimatedProperty property, float time) {
        var values = new float[channelCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = AnimatedProperty.sampleChannel(property.channel(i), time);
        }
        return values;
    }

    // ------------------------------------------------------------------ lifecycle (creation / IO / inspect)

    /** Create a property seeded from the target's current value (one keyframe per channel at that value). */
    default AnimatedProperty create(FXObject target) {
        var base = capture(target);
        var channels = AnimatedProperty.seedChannels(base);
        var range = defaultRange(base);
        return new AnimatedProperty(this, base.clone(), channels, range[0], range[1]);
    }

    /** Serialize a property of this type. Writes {@code type}/{@code base}/{@code channels}/range; override
     *  (super + extra) for per-type data. */
    default CompoundTag serialize(HolderLookup.Provider provider, AnimatedProperty property) {
        var tag = new CompoundTag();
        tag.putString("type", name());
        tag.put("base", AnimatedProperty.floatsToTag(property.base()));
        var chs = new ListTag();
        for (int i = 0; i < channelCount(); i++) {
            chs.add(property.channel(i).serializeNBT(provider));
        }
        tag.put("channels", chs);
        tag.putFloat("rangeMin", property.rangeMin());
        tag.putFloat("rangeMax", property.rangeMax());
        return tag;
    }

    /** Deserialize a property of this type (counterpart of {@link #serialize}). */
    default AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var base = AnimatedProperty.readFloatsFromTag(tag.getList("base", Tag.TAG_FLOAT));
        var channels = AnimatedProperty.readChannels(provider, tag, channelCount());
        // tolerate a base shorter/longer than the current channel count (type changed)
        var fixedBase = new float[channelCount()];
        System.arraycopy(base, 0, fixedBase, 0, Math.min(base.length, fixedBase.length));
        return new AnimatedProperty(this, fixedBase, channels, tag.getFloat("rangeMin"), tag.getFloat("rangeMax"));
    }

    /** Build an inspector configurator for {@code property} (e.g. rotation interp mode), or {@code null}.
     *  {@code onChanged} should be invoked after each mutation (to refresh preview + record undo). */
    @Nullable
    default IConfigurable inspect(AnimatedProperty property, Runnable onChanged) {
        return null;
    }
}
