package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One user-defined "custom data" stream on an emitter (Unity ParticleSystem's Custom Data). Uploaded
 * per-instance as a single {@code vec4} on both GPU paths (custom-shader vertex attributes and the
 * shadergraph {@code PhotonCustomData} buffer texture); channels beyond the stream's size read 0.
 *
 * <ul>
 *   <li><b>VECTOR</b>: {@code channelCount} (1..4) independent {@link NumberFunction}s, each sampled
 *   by the particle's normalized lifetime {@code T} into a component; unused components are 0. Each
 *   channel carries an optional display name (double-click to rename) — purely cosmetic, for sharing
 *   projects.</li>
 *   <li><b>COLOR</b>: a single color {@link NumberFunction} (gradient/color) sampled by {@code T} to
 *   an ARGB int, unpacked to {@code vec4} rgba in 0..1.</li>
 * </ul>
 *
 * Persisted manually (see {@link AdditionalGPUDataSetting#serializeAdditionalNBT}) through
 * {@link NumberFunction}'s CODEC wrapper, so the concrete function type round-trips.
 */
public class CustomData {

    public enum Type { VECTOR, COLOR }

    /**
     * Which normalized time value the stream's functions are sampled by:
     * <ul><li>{@code EMITTER} — the emitter's t (all kinds).</li>
     * <li>{@code SELF} — the particle's own t (tile: particle lifetime; beam: beam lifetime;
     * trail/ara: the segment's normalized life).</li>
     * <li>{@code LENGTH} — position along the trail (trail/ara only).</li></ul>
     */
    public enum TSource { EMITTER, SELF, LENGTH }

    public static final int MAX_CHANNELS = 4;

    private Type type = Type.VECTOR;
    /** The sampling-t source (see {@link TSource}); defaults to SELF (the per-particle/segment t). */
    private TSource tSource = TSource.SELF;
    private int channelCount = 1;
    /** VECTOR: {@code channelCount} scalar functions; COLOR: a single color function. */
    private final List<NumberFunction> channels = new ArrayList<>();
    /** Per-channel display names (parallel to {@link #channels}); blank = fall back to {@code [i]}. */
    private final List<String> channelNames = new ArrayList<>();

    public CustomData() {
        this.channels.add(NumberFunction.constant(0));
        this.channelNames.add("");
    }

    private CustomData(Type type, int channelCount, List<NumberFunction> channels, List<String> names) {
        this.type = type;
        this.channelCount = Math.clamp(channelCount, 1, MAX_CHANNELS);
        this.channels.addAll(channels);
        this.channelNames.addAll(names);
        resizeNames();
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        if (this.type == type) return;
        this.type = type;
        channels.clear();
        if (type == Type.COLOR) {
            channels.add(NumberFunction.color(-1));
        } else {
            for (int i = 0; i < channelCount; i++) {
                channels.add(NumberFunction.constant(0));
            }
        }
        resizeNames();
    }

    public TSource getTSource() {
        return tSource;
    }

    public void setTSource(TSource tSource) {
        this.tSource = tSource == null ? TSource.SELF : tSource;
    }

    public int getChannelCount() {
        return channelCount;
    }

    /** VECTOR only: resize the channel list, preserving existing functions/names. */
    public void setChannelCount(int count) {
        this.channelCount = Math.clamp(count, 1, MAX_CHANNELS);
        if (type != Type.VECTOR) return;
        while (channels.size() > channelCount) {
            channels.removeLast();
        }
        while (channels.size() < channelCount) {
            channels.add(NumberFunction.constant(0));
        }
        resizeNames();
    }

    /** The editable functions (size == channelCount for VECTOR, 1 for COLOR). */
    public List<NumberFunction> getChannels() {
        return channels;
    }

    /** Display name of the {@code i}-th channel, falling back to {@code [i]} when unnamed. */
    public String getChannelName(int i) {
        if (i >= 0 && i < channelNames.size()) {
            var name = channelNames.get(i);
            if (name != null && !name.isBlank()) return name;
        }
        return "[" + i + "]";
    }

    public void setChannelName(int i, String name) {
        if (i < 0 || i >= MAX_CHANNELS) return;
        while (channelNames.size() <= i) {
            channelNames.add("");
        }
        channelNames.set(i, name == null ? "" : name);
    }

    /** Keep {@link #channelNames} the same length as {@link #channels}. */
    private void resizeNames() {
        while (channelNames.size() > channels.size()) {
            channelNames.removeLast();
        }
        while (channelNames.size() < channels.size()) {
            channelNames.add("");
        }
    }

    /**
     * Sample this stream for one particle into the four floats of its vec4 slot. {@code out} must
     * have length >= 4 and is fully written (unused components zeroed).
     */
    public void sampleInto(float[] out, float t, Supplier<Float> lerp) {
        sampleInto(out, t, lerp, null);
    }

    /**
     * As {@link #sampleInto(float[], float, Supplier)} but each channel's function may be swapped by
     * {@code resolver} (a per-emitter runtime override) before sampling — it maps the channel index +
     * config function to the effective function; returning the config function keeps the authored value.
     * Structure (type / channelCount) always stays from this config stream, so the GPU layout / batching
     * is unaffected.
     */
    public void sampleInto(float[] out, float t, Supplier<Float> lerp, @Nullable ChannelResolver resolver) {
        out[0] = out[1] = out[2] = out[3] = 0f;
        if (type == Type.COLOR) {
            if (channels.isEmpty()) return;
            var fn = channels.getFirst();
            if (resolver != null) fn = resolver.resolve(0, fn);
            int argb = fn.get(t, lerp).intValue();
            out[0] = ((argb >> 16) & 0xFF) / 255f; // r
            out[1] = ((argb >> 8) & 0xFF) / 255f;  // g
            out[2] = (argb & 0xFF) / 255f;         // b
            out[3] = ((argb >>> 24) & 0xFF) / 255f; // a
        } else {
            int n = Math.min(channelCount, channels.size());
            for (int c = 0; c < n && c < MAX_CHANNELS; c++) {
                var fn = channels.get(c);
                if (resolver != null) fn = resolver.resolve(c, fn);
                out[c] = fn.get(t, lerp).floatValue();
            }
        }
    }

    /**
     * Per-emitter override hook for {@link #sampleInto(float[], float, Supplier, ChannelResolver)}: maps
     * a channel's config function to the effective (possibly runtime-overridden) function.
     */
    @FunctionalInterface
    public interface ChannelResolver {
        NumberFunction resolve(int channelIndex, NumberFunction configFn);
    }

    public CustomData copy() {
        var copied = new ArrayList<NumberFunction>(channels.size());
        for (var channel : channels) {
            copied.add(channel.copy());
        }
        var copy = new CustomData(type, channelCount, copied, channelNames);
        copy.tSource = tSource;
        return copy;
    }

    public CompoundTag toNBT() {
        var tag = new CompoundTag();
        tag.putString("type", type.name());
        tag.putString("tSource", tSource.name());
        tag.putInt("channelCount", channelCount);
        var list = new ListTag();
        for (var channel : channels) {
            list.add(channel.serializeWrapper());
        }
        tag.put("channels", list);
        var names = new ListTag();
        for (var name : channelNames) {
            names.add(StringTag.valueOf(name == null ? "" : name));
        }
        tag.put("names", names);
        return tag;
    }

    public static CustomData fromNBT(CompoundTag tag) {
        Type type = Type.VECTOR;
        try {
            type = Type.valueOf(tag.getStringOr("type", ""));
        } catch (IllegalArgumentException ignored) {
        }
        int channelCount = Math.clamp(tag.getIntOr("channelCount", 0), 1, MAX_CHANNELS);
        var channels = new ArrayList<NumberFunction>();
        var list = tag.getListOrEmpty("channels");
        for (int i = 0; i < list.size(); i++) {
            channels.add(NumberFunction.deserializeWrapper(list.getCompoundOrEmpty(i)));
        }
        if (channels.isEmpty()) {
            channels.add(type == Type.COLOR ? NumberFunction.color(-1) : NumberFunction.constant(0));
        }
        var names = new ArrayList<String>();
        var nameList = tag.getListOrEmpty("names");
        for (int i = 0; i < nameList.size(); i++) {
            names.add(nameList.getStringOr(i, ""));
        }
        var result = new CustomData(type, channelCount, channels, names);
        // absent tSource (legacy data) → SELF, matching the original per-particle sampling
        try {
            if (tag.contains("tSource")) {
                result.tSource = TSource.valueOf(tag.getStringOr("tSource", ""));
            }
        } catch (IllegalArgumentException ignored) {
        }
        return result;
    }
}
