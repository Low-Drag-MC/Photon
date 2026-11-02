package com.lowdragmc.photon.client.gameobject;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Supplier;

/**
 * A single named runtime slot on a per-object runtime layer (e.g. {@code ParticleRuntime}). The timeline
 * writes the slot at runtime via {@link #set}; if no value has been set, {@link #get} falls back to the
 * authored config value. This is <b>direct field access</b> — no map lookup and no reflection on the hot
 * read path.
 * <p>
 * Config stays immutable pure data; the runtime object owns these slots and the value-use behaviour.
 * <p>
 * The config value is resolved once per {@link #advance epoch} (an object's tick, a bake) and reused: a slot is read
 * for every particle, and each slot's fallback is a lambda of its own, so the call to it never inlines.
 *
 * @param <T> the value type (e.g. {@code NumberFunction}, {@code NumberFunction3}, {@code Integer}).
 */
public class RuntimeValue<T> {
    private static final VarHandle CACHED_EPOCH;

    static {
        try {
            CACHED_EPOCH = MethodHandles.lookup().findVarHandle(RuntimeValue.class, "cachedEpoch", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int epoch;

    private final Supplier<T> configFallback;
    private T override;
    private T cached;
    @SuppressWarnings("unused") // through CACHED_EPOCH: parallel particle updates read the cache too
    private int cachedEpoch = -1;

    public RuntimeValue(Supplier<T> configFallback) {
        this.configFallback = configFallback;
    }

    /** Config values are resolved again from now on: before an object ticks and before a bake. */
    public static void advance() {
        epoch = epoch == Integer.MAX_VALUE ? 0 : epoch + 1;
    }

    /** The effective value: the timeline override if set, otherwise the authored config value. */
    public T get() {
        var value = override;
        if (value != null) {
            return value;
        }
        var now = epoch;
        if ((int) CACHED_EPOCH.getAcquire(this) == now) {
            return cached;
        }
        value = configFallback.get();
        cached = value;
        CACHED_EPOCH.setRelease(this, now);
        return value;
    }

    /** The authored config value, ignoring any timeline override (used by record mode so a live
     *  inspector edit is visible even while the slot is overridden). */
    public T authored() {
        return configFallback.get();
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
