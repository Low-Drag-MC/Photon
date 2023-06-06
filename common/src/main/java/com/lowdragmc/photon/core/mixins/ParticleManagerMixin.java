package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.fx.BlockEffect;
import com.lowdragmc.photon.client.fx.EntityEffect;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote ParticleManagerMixin
 */
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Inject(method = "render",
            at = @At(value = "RETURN"), remap = false)
    private void injectRenderReturn(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks, CallbackInfo ci) {
        PhotonParticleRenderType.renderBloom();
    }

    @Inject(method = "setLevel",
            at = @At(value = "RETURN"), remap = false)
    private void injectSetLevel(Level level, CallbackInfo ci) {
        EntityEffect.CACHE.clear();
        BlockEffect.CACHE.clear();
    }
}
