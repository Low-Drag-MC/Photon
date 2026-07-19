package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * The context an {@link com.lowdragmc.photon.client.fx.FXRuntime} plays inside: supplies the level
 * and receives per-tick / per-frame callbacks from every emitted {@link IFXObject}.
 * <p>
 * Implementations anchor the FX to something and keep it in sync (or kill it): e.g.
 * {@link EntityEffectExecutor} follows an entity, {@link BlockEffectExecutor} a block, and the
 * editor's {@code FXProjectEffectExecutor} just provides a level + seeded RNG for previews.
 */
public interface IEffectExecutor {

    Level getLevel();

    /**
     * update each FX objects during their duration, per tick. Execute low frequency logic here.
     * <br>
     * e.g., kill particle
     * @param fxObject fx object
     */
    default void updateFXObjectTick(IFXObject fxObject) {
    }

    /**
     * update each FX objects during rendering, per frame. Execute high frequency logic here.
     * <br>
     * e.g., update emitter position, rotation, scale
     * @param fxObject fx object
     * @param partialTicks partialTicks
     */
    default void updateFXObjectFrame(IFXObject fxObject, float partialTicks) {

    }

    default RandomSource getRandomSource() {
        return getLevel().getRandom();
    }

    /**
     * Called when a timeline {@code signal} track fires a signal during live forward playback. Override
     * to react per-effect; global subscribers can also listen via {@code PhotonSignals}.
     *
     * @param channel the signal track's display name (channel)
     * @param name    the signal's name (may repeat across signals)
     * @param data    the signal's custom data
     * @param time    the master-clock tick at which it fired
     */
    default void onTimelineSignal(String channel, String name, CompoundTag data, double time) {
    }

    /** The post-effect request sink this execution context feeds — timeline PostProcess clips
     *  submit here every frame. The editor executor overrides to its isolated scene stack. */
    default com.lowdragmc.photon.client.postfx.runtime.PostEffectStack postEffectSink() {
        return com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.GLOBAL;
    }
}
