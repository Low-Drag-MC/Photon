package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

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
@OnlyIn(Dist.CLIENT)
public class CustomData {

    public enum Type { VECTOR, COLOR }

    public static final int MAX_CHANNELS = 4;

    private Type type = Type.VECTOR;
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
        out[0] = out[1] = out[2] = out[3] = 0f;
        if (type == Type.COLOR) {
            if (channels.isEmpty()) return;
            int argb = channels.getFirst().get(t, lerp).intValue();
            out[0] = ((argb >> 16) & 0xFF) / 255f; // r
            out[1] = ((argb >> 8) & 0xFF) / 255f;  // g
            out[2] = (argb & 0xFF) / 255f;         // b
            out[3] = ((argb >>> 24) & 0xFF) / 255f; // a
        } else {
            int n = Math.min(channelCount, channels.size());
            for (int c = 0; c < n && c < MAX_CHANNELS; c++) {
                out[c] = channels.get(c).get(t, lerp).floatValue();
            }
        }
    }

    public CustomData copy() {
        var copied = new ArrayList<NumberFunction>(channels.size());
        for (var channel : channels) {
            copied.add(channel.copy());
        }
        return new CustomData(type, channelCount, copied, channelNames);
    }

    public CompoundTag toNBT() {
        var tag = new CompoundTag();
        tag.putString("type", type.name());
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
            type = Type.valueOf(tag.getString("type"));
        } catch (IllegalArgumentException ignored) {
        }
        int channelCount = Math.clamp(tag.getInt("channelCount"), 1, MAX_CHANNELS);
        var channels = new ArrayList<NumberFunction>();
        var list = tag.getList("channels", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            channels.add(NumberFunction.deserializeWrapper(list.getCompound(i)));
        }
        if (channels.isEmpty()) {
            channels.add(type == Type.COLOR ? NumberFunction.color(-1) : NumberFunction.constant(0));
        }
        var names = new ArrayList<String>();
        var nameList = tag.getList("names", Tag.TAG_STRING);
        for (int i = 0; i < nameList.size(); i++) {
            names.add(nameList.getString(i));
        }
        return new CustomData(type, channelCount, channels, names);
    }
}
