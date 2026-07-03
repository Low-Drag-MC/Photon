package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Invariant, type-level definition for a kind of {@link IFXObject}: it is the single authority for the
 * things that may differ between object kinds but never change at runtime — object creation, editor icon,
 * supported {@link AnimatedPropertyType animatable properties}, serialization, and version management.
 * <p>
 * Abstract: each fx-object class declares a concrete subclass instance as a {@code @LDLRegisterClient}
 * static {@code TYPE} field (registered into {@code photon:fx_object}) and returns it from
 * {@link IFXObject#getFXObjectType()}. Subclass to customize per object kind.
 */
public abstract class FXObjectType {

    /** Create a new (blank) object of this type. */
    public abstract IFXObject create();

    /** The editor icon for this object kind. */
    public abstract IGuiTexture icon();

    /** Current serialization version of this object kind (bump when its persisted format changes). */
    public int version() {
        return 0;
    }

    /** This type's registry name (reverse-looked-up from the singleton registry). */
    public String name() {
        return PhotonRegistries.FX_OBJECTS.getKey(this);
    }

    /**
     * Upgrade an object's persisted data from {@code oldVersion} to {@link #version()}. Default no-op;
     * override to migrate this kind's format across versions.
     */
    public CompoundTag fixData(CompoundTag data, int oldVersion) {
        return data;
    }

    /**
     * The codec for an object of this type — owns its serialization: it stamps {@link #version()} into
     * the data on write, and applies {@link #fixData} (by the read-back version) before decoding. The
     * polymorphic {@link IFXObject#CODEC} dispatches to this.
     */
    public Codec<IFXObject> codec() {
        var base = PersistedParser.createCodec((Supplier<IFXObject>) this::create);
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<IFXObject, T>> decode(DynamicOps<T> ops, T input) {
                if (input instanceof CompoundTag tag) {
                    return base.decode(ops, (T) fixData(tag, tag.getInt("version")));
                }
                return base.decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(IFXObject input, DynamicOps<T> ops, T prefix) {
                return base.encode(input, ops, prefix).map(result -> {
                    if (result instanceof CompoundTag tag) {
                        tag.putInt("version", version());
                    }
                    return result;
                });
            }
        };
    }

    /**
     * The animatable property types this object kind supports. Base = the local-transform properties
     * (position/rotation/scale); override (call {@code super} and append) to add object-exclusive ones.
     */
    public List<AnimatedPropertyType> animatableProperties() {
        var list = new ArrayList<AnimatedPropertyType>();
        for (var id : new String[]{"position", "rotation", "scale"}) {
            var type = PhotonRegistries.ANIMATED_PROPERTIES.get(id);
            if (type != null) {
                list.add(type);
            }
        }
        return list;
    }

    /**
     * The timeline-animatable config values of this object kind, each bound to a named runtime slot.
     * Base = none; object kinds with a runtime layer (e.g. ParticleEmitter) override to declare theirs.
     * The timeline writes/clears these slots directly (no map, no reflection).
     */
    public List<RuntimeBinding> runtimeBindings() {
        return List.of();
    }
}
