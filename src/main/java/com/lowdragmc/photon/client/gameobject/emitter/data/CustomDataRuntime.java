package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;

/**
 * Per-{@code emitter}-instance runtime override for an emitter's custom-data <b>values</b>. It lives on
 * the emitter runtime layer (e.g. {@code ParticleRuntime}) alongside the other {@link RuntimeValue}
 * slots and is written by the timeline / game code, then read per-particle by
 * {@link AdditionalGPUDataSetting#uploadCustomRecord}/{@link AdditionalGPUDataSetting#uploadAttribs}.
 * <p>
 * It overrides only the per-channel sampled {@link NumberFunction}s — never the stream structure
 * (count / type / channelCount stay from the immutable config), so the GPU layout and the batching key
 * are untouched. A channel with no override falls back to the config stream's function. Reads are direct
 * indexed field access on the render thread (no map / reflection); the reusable resolver avoids per-frame
 * allocation.
 */
public class CustomDataRuntime implements CustomData.ChannelResolver {
    private final AdditionalGPUDataSetting setting;
    /** Lazily-created override slots [stream][channel]; the fixed bounds keep this tiny + index-stable. */
    @SuppressWarnings("unchecked")
    private final RuntimeValue<NumberFunction>[][] slots =
            new RuntimeValue[AdditionalGPUDataSetting.MAX_CUSTOM_DATA][CustomData.MAX_CHANNELS];
    /** Scratch: the stream currently being sampled through {@link #resolverFor} (render-thread only). */
    private int resolveStream;

    public CustomDataRuntime(AdditionalGPUDataSetting setting) {
        this.setting = setting;
    }

    /**
     * The override slot for stream {@code stream}, channel {@code channel} (lazily created). Writing it
     * (via {@link RuntimeValue#set}) overrides the sampled function; a cleared slot falls back to the
     * authored config function. Program code / timeline drive this.
     */
    public RuntimeValue<NumberFunction> slot(int stream, int channel) {
        var existing = slots[stream][channel];
        if (existing != null) return existing;
        var created = new RuntimeValue<NumberFunction>(() -> configChannel(stream, channel));
        slots[stream][channel] = created;
        return created;
    }

    /** The authored config function for a stream/channel (fallback for an un-overridden slot). */
    private NumberFunction configChannel(int stream, int channel) {
        var list = setting.customDataList();
        if (stream < 0 || stream >= list.size()) return NumberFunction.constant(0);
        var channels = list.get(stream).getChannels();
        if (channel < 0 || channel >= channels.size()) return NumberFunction.constant(0);
        return channels.get(channel);
    }

    /** Whether stream {@code stream} has at least one channel currently overridden (per-particle guard). */
    public boolean hasOverride(int stream) {
        if (stream < 0 || stream >= slots.length) return false;
        for (var slot : slots[stream]) {
            if (slot != null && slot.isOverridden()) return true;
        }
        return false;
    }

    /** Bind this reusable resolver to {@code stream} and return it (render-thread, allocation-free). */
    public CustomData.ChannelResolver resolverFor(int stream) {
        this.resolveStream = stream;
        return this;
    }

    @Override
    public NumberFunction resolve(int channelIndex, NumberFunction configFn) {
        if (channelIndex < 0 || channelIndex >= slots[resolveStream].length) return configFn;
        var slot = slots[resolveStream][channelIndex];
        return (slot != null && slot.isOverridden()) ? slot.get() : configFn;
    }

    /** Drop every override (fall back to authored config). Called on emitter reset. */
    public void clear() {
        for (var row : slots) {
            for (var slot : row) {
                if (slot != null) slot.clear();
            }
        }
    }
}
