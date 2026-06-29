package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.level.Level;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote IEffect
 */
@OnlyIn(Dist.CLIENT)
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
        return getLevel().random;
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
}
