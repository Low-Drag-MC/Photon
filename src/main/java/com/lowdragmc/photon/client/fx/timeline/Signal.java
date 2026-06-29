package com.lowdragmc.photon.client.fx.timeline;

import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A single point-event on a {@link SignalTrack}: a {@code name} + arbitrary {@code data} fired when the
 * master clock reaches {@link #time}. Multiple signals may share a name. The owning track's display name
 * is the "channel"; see {@link TimelinePlayer} for dispatch and {@link PhotonSignals} for listeners.
 */
@OnlyIn(Dist.CLIENT)
public class Signal {
    private double time;
    private String name = "signal";
    private CompoundTag data = new CompoundTag();

    public Signal() {
    }

    public Signal(double time, String name) {
        this.time = time;
        this.name = name;
    }

    public double time() {
        return time;
    }

    public Signal time(double time) {
        this.time = time;
        return this;
    }

    public String name() {
        return name;
    }

    public Signal name(String name) {
        this.name = name;
        return this;
    }

    public CompoundTag data() {
        return data;
    }

    public Signal data(CompoundTag data) {
        this.data = data;
        return this;
    }

    /** An independent value copy of this signal. */
    public Signal copy() {
        return new Signal(time, name).data(data.copy());
    }

    public CompoundTag writeData() {
        var tag = new CompoundTag();
        tag.putDouble("time", time);
        tag.putString("name", name);
        tag.put("data", data.copy());
        return tag;
    }

    public static Signal readData(CompoundTag tag) {
        var signal = new Signal(tag.getDouble("time"), tag.getString("name"));
        signal.data(tag.getCompound("data").copy());
        return signal;
    }
}
