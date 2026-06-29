package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Global registry of timeline-signal listeners. A {@code signal} track fires through both the originating
 * {@link IEffectExecutor#onTimelineSignal} hook (per-effect) and every listener registered here (global).
 * Signals only fire on live forward playback (see {@link TimelinePlayer}).
 */
@OnlyIn(Dist.CLIENT)
public final class PhotonSignals {
    private PhotonSignals() {
    }

    /** A global timeline-signal listener. */
    public interface Listener {
        /**
         * @param effect  the effect that fired the signal (may be null in unusual editor paths)
         * @param channel the signal track's display name (channel)
         * @param name    the signal's name
         * @param data    the signal's custom data
         * @param time    the master-clock tick at which it fired
         */
        void onSignal(@Nullable IEffectExecutor effect, String channel, String name, CompoundTag data, double time);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void register(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void unregister(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Fire a signal to every registered listener (called by {@link TimelinePlayer}). */
    public static void fire(@Nullable IEffectExecutor effect, String channel, String name, CompoundTag data, double time) {
        for (var listener : LISTENERS) {
            try {
                listener.onSignal(effect, channel, name, data, time);
            } catch (Throwable t) {
                Photon.LOGGER.error("Timeline signal listener threw", t);
            }
        }
    }
}
