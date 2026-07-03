package com.lowdragmc.photon.client.gameobject;

import java.util.function.Supplier;

/**
 * A single named runtime slot on a per-object runtime layer (e.g. {@code ParticleRuntime}). The timeline
 * writes the slot at runtime via {@link #set}; if no value has been set, {@link #get} falls back to the
 * authored config value. This is <b>direct field access</b> — no map lookup and no reflection on the hot
 * read path.
 * <p>
 * Config stays immutable pure data; the runtime object owns these slots and the value-use behaviour.
 *
 * @param <T> the value type (e.g. {@code NumberFunction}, {@code NumberFunction3}, {@code Integer}).
 */
public class RuntimeValue<T> {
    private final Supplier<T> configFallback;
    private T override;

    public RuntimeValue(Supplier<T> configFallback) {
        this.configFallback = configFallback;
    }

    /** The effective value: the timeline override if set, otherwise the authored config value. */
    public T get() {
        return override != null ? override : configFallback.get();
    }

    /** Set by the timeline each tick (a sampled constant / gradient color / boxed scalar). */
    public void set(T value) {
        this.override = value;
    }

    /** Set from an untyped sampled value (used by the generic timeline apply path). */
    @SuppressWarnings("unchecked")
    public void setRaw(Object value) {
        this.override = (T) value;
    }

    /** Clear the override so {@link #get} falls back to the authored config value (property removed/muted). */
    public void clear() {
        this.override = null;
    }

    public boolean isOverridden() {
        return override != null;
    }
}
