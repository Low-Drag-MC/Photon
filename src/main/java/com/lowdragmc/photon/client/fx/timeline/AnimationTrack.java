package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.gameobject.FXObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Animation track. The header binds one target object ({@link #targetId()}, like {@link
 * ActivatorTrack}); it has no clips. Instead it holds a list of {@link AnimatedProperty} that
 * keyframe-animate the target's local transform over the master-clock timeline. The bound channels
 * are driven absolutely (the curves own them); see {@link AnimatedProperty} for the value model.
 */
@OnlyIn(Dist.CLIENT)
public class AnimationTrack extends Track {
    private final List<AnimatedProperty> properties = new ArrayList<>();

    public AnimationTrack() {
    }

    public List<AnimatedProperty> properties() {
        return properties;
    }

    /** The property of the given type (matched by registry name), or {@code null}. */
    @Nullable
    public AnimatedProperty property(AnimatedPropertyType type) {
        for (var property : properties) {
            if (property.type().name().equals(type.name())) {
                return property;
            }
        }
        return null;
    }

    /** Drive {@code target}'s local transform from every property sampled at {@code time}. */
    public void sampleInto(FXObject target, double time) {
        for (var property : properties) {
            property.apply(target, time);
        }
    }

    /** Restore the authored pose of every property (used on remove/mute). */
    public void restoreBase(FXObject target) {
        for (var property : properties) {
            property.restoreBase(target);
        }
    }

    @Override
    protected void copyExtra(Track target) {
        if (target instanceof AnimationTrack animation) {
            for (var property : properties) {
                animation.properties.add(property.copy());
            }
        }
    }

    @Override
    protected void writeExtra(CompoundTag tag, HolderLookup.Provider provider) {
        var list = new ListTag();
        for (var property : properties) {
            list.add(property.serializeNBT(provider));
        }
        tag.put("properties", list);
    }

    @Override
    protected void readExtra(CompoundTag tag, HolderLookup.Provider provider) {
        properties.clear();
        for (var t : tag.getList("properties", Tag.TAG_COMPOUND)) {
            if (t instanceof CompoundTag c) {
                var property = AnimatedProperty.deserialize(provider, c);
                if (property != null) { // null = unknown property type (skipped + warned)
                    properties.add(property);
                }
            }
        }
    }
}
