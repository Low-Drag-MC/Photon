package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Tracks the vanilla {@code ParticleEngine} for Photon: level changes discard all particles without
 * notifying them, so bump the {@link VanillaParticleHost} wipe generation (feeds
 * {@code FXRuntime.isValid()}) and drop the executor caches; the tick counter is the heartbeat side
 * of the same validity check.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    /**
     * the engine silently drops every particle on level change: invalidate + clear effect caches.
     */
    @Inject(method = "setLevel",
            at = @At(value = "RETURN"))
    private void photon$injectSetLevel(ClientLevel level, CallbackInfo ci) {
        VanillaParticleHost.onWipe();
        EntityEffectExecutor.CACHE.clear();
        BlockEffectExecutor.CACHE.clear();
    }

    /**
     * heartbeat for {@code FXRuntime.isValid()}: advances only when particles actually tick.
     */
    @Inject(method = "tick",
            at = @At(value = "HEAD"))
    private void photon$injectTick(CallbackInfo ci) {
        VanillaParticleHost.onEngineTick();
    }
}

