package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * @author KilaBash
 * @date 2022/05/02
 * @implNote ParticleEngineMixin, inject particle postprocessing
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    /**
     * clear effect cache while level changes.
     */
    @Inject(method = "setLevel",
            at = @At(value = "RETURN"))
    private void photon$injectSetLevel(ClientLevel level, CallbackInfo ci) {
        EntityEffectExecutor.CACHE.clear();
        BlockEffectExecutor.CACHE.clear();
    }
}

