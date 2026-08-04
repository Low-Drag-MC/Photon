package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRColorFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * A {@link ConfigValueType#COLOR} animatable config value: a gradient of ARGB stops over the timeline
 * (see {@link ColorAnimatedProperty}). Like {@link ConfigPropertyType} it is bound to a named runtime
 * slot via a {@link RuntimeBinding} (resolved by config-relative {@code storeKey}); every instance reports
 * the registry name {@code "config"} and is dispatched on load by {@link ConfigPropertyTypeDispatcher}.
 * <p>
 * {@link #applyColor} writes the sampled color as a <b>static</b> {@code NumberFunction.color(argb)} into
 * the slot (a {@code RuntimeValue<NumberFunction>}), so per-particle reads never re-sample a gradient;
 * {@link #restore} clears the slot so the authored config value takes over again.
 */
public class ColorPropertyType implements AnimatedPropertyType {

    /** Config-relative dotted path (the {@link RuntimeBinding} identity, e.g. {@code "startColor"}). */
    private final String storeKey;
    private final String labelKey;
    // resolved against the current target's FXObjectType; re-resolved when the target type changes so a
    // re-bound track never dereferences the old type's binding (whose slot lambda hard-casts).
    @Nullable
    private RuntimeBinding binding;
    @Nullable
    private FXObjectType boundType;

    public ColorPropertyType(String storeKey, String labelKey) {
        this.storeKey = storeKey;
        this.labelKey = labelKey;
    }

    /** Build a property bound to a named runtime slot (the timeline drives the slot directly). */
    public static ColorPropertyType fromBinding(RuntimeBinding binding) {
        return new ColorPropertyType(binding.path, binding.labelKey);
    }

    @Nullable
    private RuntimeBinding resolveBinding(FXObject target) {
        var type = target.getFXObjectType();
        if (boundType != type) {
            boundType = type;
            // resolveRuntimeBinding also synthesizes dynamic bindings (e.g. custom-data COLOR streams)
            binding = type.resolveRuntimeBinding(target, storeKey);
        }
        return binding;
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
    public int channelCount() {
        return 0;
    }

    @Override
    public String channelKey(int channel) {
        return "";
    }

    @Override
    public float[] capture(FXObject target) {
        return new float[0];
    }

    /** The target's current authored color (the config {@code NumberFunction} sampled at t=0), or white. */
    public int captureColor(FXObject target) {
        return captureHDRColor(target).toARGB();
    }

    /**
     * As {@link #captureColor} but keeping the HDR range: an {@link HDRColorFunction} is sampled
     * unclamped and split back into a base color + intensity, anything else keeps intensity 1.
     */
    public HDRColor captureHDRColor(FXObject target) {
        var binding = resolveBinding(target);
        var value = binding != null ? binding.slot(target).get() : null;
        try {
            if (value instanceof HDRColorFunction hdr) {
                var sample = new Vector4f();
                hdr.sampleHDR(0f, () -> 0f, sample);
                return HDRColor.fromPremultiplied(sample);
            }
            if (value instanceof NumberFunction function) {
                return HDRColor.fromARGB(function.get(0f, () -> 0f).intValue());
            }
        } catch (Exception ignored) {
        }
        return HDRColor.white();
    }

    /**
     * Whether the bound slot's <b>authored</b> value is an HDR color function. Read from
     * {@code authored()} rather than {@code get()} so it isn't confused by the LDR/HDR function this
     * very property may have already written into the slot as an override.
     */
    public boolean isHDR(FXObject target) {
        var binding = resolveBinding(target);
        return binding != null && binding.slot(target).authored() instanceof HDRColorFunction;
    }

    @Override
    public void apply(FXObject target, float[] values) {
        // color is driven via ColorAnimatedProperty#apply(target, time) -> applyColor; nothing to do here
    }

    /** Write the sampled {@link NumberFunction} into the runtime slot (a static color from the stops, or a
     *  gradient clip's real {@code Gradient}; see {@link ColorAnimatedProperty#sampleFunction}). */
    public void applyFunction(FXObject target, NumberFunction function) {
        var binding = resolveBinding(target);
        if (binding != null) {
            binding.slot(target).setRaw(function);
        }
    }

    @Override
    public void restore(FXObject target, AnimatedProperty property) {
        var binding = resolveBinding(target);
        if (binding != null) {
            binding.slot(target).clear();
        }
    }

    @Override
    public AnimatedProperty create(FXObject target) {
        var property = new ColorAnimatedProperty(this);
        property.setHDR(isHDR(target));
        var captured = captureHDRColor(target);
        property.addStop(0, captured.baseARGB(), captured.getIntensity());
        return property;
    }

    @Override
    public CompoundTag serialize(HolderLookup.Provider provider, AnimatedProperty property) {
        var tag = new CompoundTag();
        tag.putString("type", name());
        tag.putString("path", storeKey);
        tag.putString("valueType", ConfigValueType.COLOR.name());
        tag.putString("label", labelKey);
        var stops = new ListTag();
        if (property instanceof ColorAnimatedProperty color) {
            if (color.isHDR()) {
                tag.putBoolean("hdr", true);
            }
            for (var stop : color.stops()) {
                var t = new CompoundTag();
                t.put("tick", FloatTag.valueOf(stop.tick));
                t.put("argb", IntTag.valueOf(stop.argb));
                // omitted for LDR stops so pre-HDR saves round-trip byte-for-byte
                if (stop.intensity != 1f) {
                    t.put("intensity", FloatTag.valueOf(stop.intensity));
                }
                stops.add(t);
            }
        }
        tag.put("stops", stops);
        var clips = new ListTag();
        if (property instanceof ColorAnimatedProperty color) {
            for (var clip : color.gradientClips()) {
                var t = new CompoundTag();
                t.putDouble("start", clip.start());
                t.putDouble("duration", clip.duration());
                if (clip.gradient() != null) {
                    t.put("gradient", clip.gradient().serializeNBT(provider));
                }
                clips.add(t);
            }
        }
        tag.put("gradientClips", clips);
        return tag;
    }

    @Override
    public AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var property = new ColorAnimatedProperty(this);
        property.setHDR(tag.getBoolean("hdr")); // absent in pre-HDR saves, which are all LDR
        var stops = tag.getList("stops", Tag.TAG_COMPOUND);
        for (int i = 0; i < stops.size(); i++) {
            var t = stops.getCompound(i);
            property.addStop(t.getFloat("tick"), t.getInt("argb"),
                    t.contains("intensity") ? t.getFloat("intensity") : 1f);
        }
        var clips = tag.getList("gradientClips", Tag.TAG_COMPOUND);
        for (int i = 0; i < clips.size(); i++) {
            var t = clips.getCompound(i);
            var gc = new com.lowdragmc.lowdraglib2.math.GradientColor();
            if (t.contains("gradient")) {
                gc.deserializeNBT(provider, t.getCompound("gradient"));
            }
            property.gradientClips().add(new com.lowdragmc.photon.client.fx.timeline.GradientClip(
                    t.getDouble("start"), t.getDouble("duration"), gc));
        }
        return property;
    }
}
