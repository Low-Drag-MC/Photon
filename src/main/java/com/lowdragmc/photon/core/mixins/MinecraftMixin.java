package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.compat.iris.IrisCompat;
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
     * Check resie to clear the draw buffer
     */
    @Inject(method = "resizeDisplay",
            at = @At(value = "RETURN"))
    private void photon$resizeDisplay(CallbackInfo ci) {
        RenderPassPipeline.markDrawTargetDirty();
        PostFXTargetPool.invalidateAll();
        // a resize recreates every Iris render target, and GL happily reuses the old texture names —
        // the resolved layout and our composite framebuffer must not survive it
        IrisCompat.invalidate();
    }
}
