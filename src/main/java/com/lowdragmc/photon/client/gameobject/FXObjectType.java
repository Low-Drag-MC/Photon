package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ColorPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

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
                    return base.decode(ops, (T) fixData(tag, tag.getIntOr("version", 0)));
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
    // memoized so every call returns the SAME instances: record mode keys its reference map by the
    // property-type instance, and config types (fromBinding) would otherwise be rebuilt fresh each call.
    private List<AnimatedPropertyType> animatablePropertiesCache;

    public List<AnimatedPropertyType> animatableProperties() {
        if (animatablePropertiesCache == null) {
            var list = new ArrayList<AnimatedPropertyType>();
            for (var id : new String[]{"position", "rotation", "scale"}) {
                var type = PhotonRegistries.ANIMATED_PROPERTIES.get(id);
                if (type != null) {
                    list.add(type);
                }
            }
            // config values backed by named runtime slots become parameterized property types
            for (var b : runtimeBindings()) {
                list.add(b.type == ConfigValueType.COLOR
                        ? ColorPropertyType.fromBinding(b)
                        : ConfigPropertyType.fromBinding(b));
            }
            animatablePropertiesCache = List.copyOf(list);
        }
        return animatablePropertiesCache;
    }

    /**
     * The timeline-animatable config values of this object kind, each bound to a named runtime slot.
     * Base = none; object kinds with a runtime layer (e.g. ParticleEmitter) override to declare theirs.
     * The timeline writes/clears these slots directly (no map, no reflection).
     */
    public List<RuntimeBinding> runtimeBindings() {
        return List.of();
    }

    /**
     * The animatable properties for a SPECIFIC object instance — the type-level {@link #animatableProperties()}
     * plus any per-instance/per-config dynamic ones (e.g. a ParticleEmitter's user custom-data channels,
     * whose count varies per config). Base = type-level only. Used by the add-property menu.
     */
    public List<AnimatedPropertyType> animatableProperties(FXObject target) {
        return animatableProperties();
    }

    /**
     * Resolve the runtime slot binding for {@code path} on {@code target}. Base = search the static
     * {@link #runtimeBindings()}; object kinds with DYNAMIC bindings (custom-data channels) override to
     * synthesize one for their paths. Returns {@code null} if the path is unknown to this type.
     */
    @Nullable
    public RuntimeBinding resolveRuntimeBinding(FXObject target, String path) {
        for (var b : runtimeBindings()) {
            if (b.path.equals(path)) {
                return b;
            }
        }
        return null;
    }
}
