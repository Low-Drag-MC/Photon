package com.lowdragmc.photon.client.fx;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Base {@link IFXEffectExecutor}: holds the FX definition, the level, the pre-start configuration
 * (offset/rotation/scale/delay/forcedDeath/allowMulti) and, once {@code start()} ran, the live
 * {@link FXRuntime}. Subclasses implement {@code start()} and the anchor-tracking callbacks.
 */
public abstract class FXEffectExecutor implements IFXEffectExecutor {
    @Getter
    public final FX fx;
    @Getter
    public final Level level;
    @Setter
    protected Vector3f offset = new Vector3f();
    @Setter
    protected Quaternionf rotation = new Quaternionf();
    @Setter
    protected Vector3f scale = new Vector3f(1, 1, 1);
    @Setter
    protected int delay;
    @Setter
    protected boolean forcedDeath;
    @Setter
    protected boolean allowMulti;

    //runtime
    @Getter
    @Nullable
    protected FXRuntime runtime;
    /** Optional callback fired once when this executor's runtime ends (finished, destroyed, or
     *  discarded by the engine). Set before {@link #start()}. */
    @Setter
    @Nullable
    protected Consumer<FXRuntime> onFinished;
    private boolean finishedNotified = false;

    protected FXEffectExecutor(FX fx, Level level) {
        this.fx = fx;
        this.level = level;
    }

    /** Fire {@link #onFinished} exactly once (re-armed by the next {@code start()}). */
    protected void notifyFinished() {
        if (!finishedNotified && runtime != null) {
            finishedNotified = true;
            if (onFinished != null) {
                onFinished.accept(runtime);
            }
        }
    }

    /** Re-arm {@link #notifyFinished()}; call at the start of {@code start()}. */
    protected void resetFinishedNotification() {
        finishedNotified = false;
    }

    /**
     * Shared {@code !allowMulti} dedup for the executor caches ({@code EntityEffectExecutor.CACHE} /
     * {@code BlockEffectExecutor.CACHE}): prunes executors whose runtime ended — finished naturally
     * OR silently discarded by the particle engine ({@code !isValid()}, e.g. after a level change) —
     * then reports whether an equivalent effect (same {@link FX} instance or same fx location) is
     * still running, in which case this {@code start()} must be skipped.
     */
    protected boolean shouldSkipStart(List<? extends FXEffectExecutor> effects) {
        if (allowMulti) {
            return false;
        }
        var iter = effects.iterator();
        while (iter.hasNext()) {
            var effect = iter.next();
            if (effect.runtimeEnded()) {
                effect.notifyFinished();
                iter.remove();
                continue;
            }
            if (effect.fx.equals(fx) || Objects.equals(effect.fx.getFxLocation(), fx.getFxLocation())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retire this executor: fire {@link #notifyFinished()} once and drop it from its cache bucket
     * (removing an emptied bucket, so dead keys don't accumulate). Called from the subclasses' root
     * tick when the runtime ended, and right after destroying it on anchor loss.
     */
    protected <K> void retire(Map<K, ? extends List<? extends FXEffectExecutor>> cache, K key) {
        notifyFinished();
        var effects = cache.get(key);
        if (effects != null) {
            effects.remove(this);
            if (effects.isEmpty()) {
                cache.remove(key);
            }
        }
    }

    /** Whether this executor's runtime ended (finished naturally or discarded by the engine). */
    protected boolean runtimeEnded() {
        return runtime != null && (runtime.isFinished() || !runtime.isValid());
    }
}
