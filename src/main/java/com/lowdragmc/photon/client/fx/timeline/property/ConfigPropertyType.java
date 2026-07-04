package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.CurveClip;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3Config;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

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
    // runtime slot binding: resolved against the current target's FXObjectType. Re-resolved whenever the
    // target's type changes, so a property whose track is re-bound to a different fx-object type never
    // dereferences the old type's binding (whose slot lambda hard-casts) -> no ClassCastException.
    @Nullable
    private RuntimeBinding binding;
    @Nullable
    private FXObjectType boundType;

    /** Minimal constructor (deserialization): the binding is resolved from the target on first use. */
    public ConfigPropertyType(String storeKey, ConfigValueType valueType, String labelKey) {
        this.storeKey = storeKey;
        this.valueType = valueType;
        this.labelKey = labelKey;
    }

    /** Build a property bound to a named runtime slot (the timeline drives the slot directly). */
    public static ConfigPropertyType fromBinding(RuntimeBinding binding) {
        return new ConfigPropertyType(binding.path, binding.type, binding.labelKey);
    }

    /** The runtime slot binding for {@code target}, resolved against its {@code FXObjectType} (re-resolved
     *  when the target type changes; {@code null} if that type has no binding for this {@link #storeKey}). */
    @Nullable
    private RuntimeBinding resolveBinding(FXObject target) {
        var type = target.getFXObjectType();
        if (boundType != type) {
            boundType = type;
            binding = null;
            for (var b : type.runtimeBindings()) {
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

    // lazily-resolved @NumberFunctionConfig of the backing config field (for editing a curve clip's Curve
    // with the field's real value range/axes). Coupled to ParticleConfig since config properties are
    // ParticleEmitter-only today; returns null (→ caller uses a generic default) if not resolvable.
    @Nullable
    private NumberFunctionConfig numberFunctionConfig;
    private boolean nfConfigResolved;

    /** The backing config field's {@link NumberFunctionConfig} (a {@link NumberFunction3Config}'s common
     *  config for NF3 fields), or {@code null} if it can't be resolved from the {@link #storeKey} path. */
    @Nullable
    public NumberFunctionConfig numberFunctionConfig() {
        if (!nfConfigResolved) {
            nfConfigResolved = true;
            numberFunctionConfig = resolveNumberFunctionConfig();
        }
        return numberFunctionConfig;
    }

    @Nullable
    private NumberFunctionConfig resolveNumberFunctionConfig() {
        Class<?> cls = ParticleConfig.class;
        Field field = null;
        for (var segment : storeKey.split("\\.")) {
            field = findField(cls, segment);
            if (field == null) return null;
            cls = field.getType();
        }
        if (field == null) return null;
        var nf = field.getAnnotation(NumberFunctionConfig.class);
        if (nf != null) return nf;
        var nf3 = field.getAnnotation(NumberFunction3Config.class);
        return nf3 != null ? nf3.common() : null;
    }

    @Nullable
    private static Field findField(Class<?> cls, String name) {
        for (var c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
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
        return toChannels(binding != null ? binding.slot(target).get() : null);
    }

    @Override
    public float[] captureLive(FXObject target) {
        var binding = resolveBinding(target);
        return toChannels(binding != null ? binding.slot(target).authored() : null);
    }

    /** Convert a slot value (constant / function / boxed scalar) to this type's channel floats. */
    private float[] toChannels(Object value) {
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
            case COLOR -> throw new IllegalStateException("COLOR is handled by ColorPropertyType");
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
    public AnimatedProperty create(FXObject target) {
        var base = capture(target);
        var channels = AnimatedProperty.seedChannels(base);
        var range = defaultRange(base);
        return new ConfigAnimatedProperty(this, base.clone(), channels, range[0], range[1]);
    }

    @Override
    public void apply(FXObject target, float[] values) {
        var binding = resolveBinding(target);
        if (binding != null) {
            binding.slot(target).setRaw(buildEffective(values));
        }
    }

    /** Write a per-channel {@link NumberFunction} array into the slot (used by curve clips): a single
     *  {@code NUMBER_FUNCTION} channel writes {@code fns[0]}, a {@code NUMBER_FUNCTION3} writes a
     *  {@code NumberFunction3} of the three channels. Non-function value types are ignored. */
    public void applyFunctions(FXObject target, NumberFunction[] fns) {
        var binding = resolveBinding(target);
        if (binding == null) {
            return;
        }
        Object effective = switch (valueType) {
            case NUMBER_FUNCTION -> fns[0];
            case NUMBER_FUNCTION3 -> new NumberFunction3(fns[0], fns[1], fns[2]);
            default -> null;
        };
        if (effective != null) {
            binding.slot(target).setRaw(effective);
        }
    }

    /** The effective override object written into the runtime slot (built once per apply). */
    private Object buildEffective(float[] v) {
        return switch (valueType) {
            case INT, FLOAT -> v[0];
            case BOOL -> v[0] >= 0.5f;
            case NUMBER_FUNCTION -> NumberFunction.constant(v[0]);
            case NUMBER_FUNCTION3 -> new NumberFunction3(v[0], v[1], v[2]);
            case COLOR -> throw new IllegalStateException("COLOR is handled by ColorPropertyType");
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
        if (property instanceof ConfigAnimatedProperty cfg) {
            var channels = new ListTag();
            for (int axis = 0; axis < property.channelCount(); axis++) {
                var clips = new ListTag();
                for (var clip : cfg.curveClips(axis)) {
                    var c = new CompoundTag();
                    c.putDouble("start", clip.start());
                    c.putDouble("duration", clip.duration());
                    if (clip.curve() != null) {
                        c.put("curve", clip.curve().serializeWrapper());
                    }
                    clips.add(c);
                }
                channels.add(clips);
            }
            tag.put("curveClips", channels);
        }
        return tag;
    }

    @Override
    public AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var base = AnimatedProperty.readFloatsFromTag(tag.getList("base", Tag.TAG_FLOAT));
        var channels = AnimatedProperty.readChannels(provider, tag, channelCount());
        var fixedBase = new float[channelCount()];
        System.arraycopy(base, 0, fixedBase, 0, Math.min(base.length, fixedBase.length));
        var property = new ConfigAnimatedProperty(this, fixedBase, channels, tag.getFloat("rangeMin"), tag.getFloat("rangeMax"));
        AnimatedProperty.readExprClips(tag, property);
        if (tag.contains("curveClips", Tag.TAG_LIST)) {
            var channelsTag = tag.getList("curveClips", Tag.TAG_LIST);
            for (int axis = 0; axis < channelCount() && axis < channelsTag.size(); axis++) {
                var clips = channelsTag.getList(axis);
                for (int i = 0; i < clips.size(); i++) {
                    var c = clips.getCompound(i);
                    var curve = c.contains("curve") ? NumberFunction.deserializeWrapper(c.getCompound("curve")) : new Curve();
                    property.curveClips(axis).add(new CurveClip(c.getDouble("start"), c.getDouble("duration"), curve));
                }
            }
        }
        return property;
    }
}
