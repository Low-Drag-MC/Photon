package com.lowdragmc.photon.client.fx.timeline;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Signal track. Like {@link ControlTrack} the header has no bound target — instead the track's
 * {@link #displayName()} is a "channel" and the lane holds a list of {@link Signal} point-events that
 * fire (once, on live forward playback) when the master clock crosses their time. Signals carry a
 * {@code name + data (NBT)} and may repeat. The track has no clips.
 */
public class SignalTrack extends Track {
    private final List<Signal> signals = new ArrayList<>();

    public SignalTrack() {
    }

    public List<Signal> signals() {
        return signals;
    }

    /** Content lasts until the last signal fires (the track has no clips). */
    @Override
    public double contentEnd() {
        double end = super.contentEnd();
        for (var signal : signals) {
            end = Math.max(end, signal.time());
        }
        return end;
    }

    @Override
    public void insertTime(double atTick, double deltaTicks) {
        super.insertTime(atTick, deltaTicks);
        for (var signal : signals) {
            if (signal.time() >= atTick) {
                signal.time(signal.time() + deltaTicks);
            }
        }
    }

    @Override
    protected void copyExtra(Track target) {
        if (target instanceof SignalTrack signalTrack) {
            for (var signal : signals) {
                signalTrack.signals.add(signal.copy());
            }
        }
    }

    @Override
    protected void writeExtra(CompoundTag tag, HolderLookup.Provider provider) {
        var list = new ListTag();
        for (var signal : signals) {
            list.add(signal.writeData());
        }
        tag.put("signals", list);
    }

    @Override
    protected void readExtra(CompoundTag tag, HolderLookup.Provider provider) {
        signals.clear();
        for (var t : tag.getListOrEmpty("signals")) {
            if (t instanceof CompoundTag c) {
                signals.add(Signal.readData(c));
            }
        }
    }
}
