package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    /**
     * Window resize → drop Photon's cached off-screen targets. 26.1 renamed resizeDisplay to
     * resizeGui. TODO(M3): when the Photon targets return, consider allocating them from the frame
     * graph instead (transient targets resize for free) and deleting this mixin.
     */
    @Inject(method = "resizeGui",
            at = @At(value = "RETURN"))
    private void photon$resizeGui(CallbackInfo ci) {
        RenderPassPipeline.markDrawTargetDirty();
        PostFXTargetPool.invalidateAll();
    }
}
