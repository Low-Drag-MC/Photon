package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * A parameterized {@link AnimatedPropertyType} bound to a single timeline-animatable config value,
 * identified by a <b>config-relative dotted key</b> ({@code "maxParticles"}, {@code "physics.friction"}).
 * Every instance reports the registry name {@code "config"} (dispatched on load by
 * {@link ConfigPropertyTypeDispatcher}); its unique {@link #path()} is {@code "config." + storeKey}.
 * <p>
 * {@link #apply} writes the built effective value into the target's named runtime slot (resolved via the
 * {@link RuntimeBinding} for this path); it never mutates the authored config. Discrete (int/bool) values
 * use step sampling + quantization; a {@code NumberFunction} is one channel and a {@code NumberFunction3}
 * is three channels (x/y/z).
 */
public class ConfigPropertyType implements AnimatedPropertyType {
    private static final String[] XYZ = {"x", "y", "z"};

    /** Config-relative dotted path (the {@link RuntimeBinding} identity, e.g. {@code "physics.friction"}). */
    private final String storeKey;
    private final ConfigValueType valueType;
    private final String labelKey;
    // runtime slot binding: resolved lazily against the target's FXObjectType (or set by fromBinding)
    @Nullable
    private RuntimeBinding binding;
    private boolean bindingResolved;

    /** Minimal constructor (deserialization): the binding is resolved from the target on first use. */
    public ConfigPropertyType(String storeKey, ConfigValueType valueType, String labelKey) {
        this.storeKey = storeKey;
        this.valueType = valueType;
        this.labelKey = labelKey;
    }

    /** Build a property bound to a named runtime slot (the timeline drives the slot directly). */
    public static ConfigPropertyType fromBinding(RuntimeBinding binding) {
        var type = new ConfigPropertyType(binding.path, binding.type, binding.labelKey);
        type.binding = binding;
        type.bindingResolved = true;
        return type;
    }

    /** The runtime slot binding for {@code target}, resolved once against its {@code FXObjectType}. */
    @Nullable
    private RuntimeBinding resolveBinding(FXObject target) {
        if (!bindingResolved) {
            bindingResolved = true;
            for (var b : target.getFXObjectType().runtimeBindings()) {
                if (b.path.equals(storeKey)) {
                    binding = b;
                    break;
                }
            }
        }
        return binding;
    }

    public String storeKey() {
        return storeKey;
    }

    public ConfigValueType valueType() {
        return valueType;
    }

    @Override
    public String name() {
        return "config";
    }

    @Override
    public String path() {
        return "config." + storeKey;
    }

    @Override
    public String displayNameKey() {
        return labelKey;
    }

    @Override
    public boolean stepped() {
        return valueType.discrete();
    }

    @Override
    public int channelCount() {
        return valueType.channelCount();
    }

    @Override
    public String channelKey(int channel) {
        return channelCount() > 1 ? XYZ[channel] : "";
    }

    @Override
    public float clampValue(float value) {
        return switch (valueType) {
            case INT -> Math.round(value);
            case BOOL -> value >= 0.5f ? 1 : 0;
            default -> value;
        };
    }

    @Nullable
    @Override
    public Float fixedRangeMin() {
        return valueType == ConfigValueType.BOOL ? 0f : null;
    }

    @Override
    public float[] defaultRange(float[] base) {
        if (valueType == ConfigValueType.BOOL) {
            return new float[]{0, 1};
        }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (var v : base) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (lo > hi) {
            lo = 0;
            hi = 1;
        }
        var pad = Math.max(1f, Math.max(Math.abs(lo), Math.abs(hi)) * 0.5f);
        return new float[]{lo - pad, hi + pad};
    }

    @Override
    public float[] capture(FXObject target) {
        var binding = resolveBinding(target);
        var value = binding != null ? binding.slot(target).get() : null;
        if (value == null) {
            return new float[channelCount()];
        }
        return switch (valueType) {
            case INT, FLOAT -> new float[]{((Number) value).floatValue()};
            case BOOL -> new float[]{((Boolean) value) ? 1 : 0};
            case NUMBER_FUNCTION -> new float[]{sampleSeed((NumberFunction) value)};
            case NUMBER_FUNCTION3 -> {
                var nf3 = (NumberFunction3) value;
                yield new float[]{sampleSeed(nf3.x), sampleSeed(nf3.y), sampleSeed(nf3.z)};
            }
        };
    }

    /** A representative scalar to seed the initial keyframe from a function (its value at t=0). */
    private static float sampleSeed(NumberFunction function) {
        try {
            return function.get(0f, () -> 0f).floatValue();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void apply(FXObject target, float[] values) {
        var binding = resolveBinding(target);
        if (binding != null) {
            binding.slot(target).setRaw(buildEffective(values));
        }
    }

    /** The effective override object written into the runtime slot (built once per apply). */
    private Object buildEffective(float[] v) {
        return switch (valueType) {
            case INT, FLOAT -> v[0];
            case BOOL -> v[0] >= 0.5f;
            case NUMBER_FUNCTION -> NumberFunction.constant(v[0]);
            case NUMBER_FUNCTION3 -> new NumberFunction3(v[0], v[1], v[2]);
        };
    }

    @Override
    public void restore(FXObject target, AnimatedProperty property) {
        // clear the runtime slot so it falls back to the authored config value
        var binding = resolveBinding(target);
        if (binding != null) {
            binding.slot(target).clear();
        }
    }

    @Override
    public float[] sample(AnimatedProperty property, float time) {
        var values = new float[channelCount()];
        for (int i = 0; i < values.length; i++) {
            if (valueType.discrete()) {
                values[i] = clampValue(property.sampleChannelStepped(i, time));
            } else {
                values[i] = property.sampleChannelValue(i, time);
            }
        }
        return values;
    }

    @Override
    public CompoundTag serialize(HolderLookup.Provider provider, AnimatedProperty property) {
        var tag = AnimatedPropertyType.super.serialize(provider, property);
        tag.putString("path", storeKey);
        tag.putString("valueType", valueType.name());
        tag.putString("label", labelKey);
        return tag;
    }
}
