package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.PhotonInstancedDrawState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 1.21 divisor-attribute restoration hook (approved plan C1): 26.1's vertex system has no
 * per-instance attributes, so right after the engine binds the VAO for one of Photon's dedicated
 * instanced vertex formats, the 1.21 instance-attribute layout is applied on top (no-op unless a
 * Photon instanced draw is in flight; the dedicated formats guarantee the VAO is exclusively ours).
 */
@Mixin(targets = {
        "com.mojang.blaze3d.opengl.VertexArrayCache$Emulated",
        "com.mojang.blaze3d.opengl.VertexArrayCache$Separate"
})
public class VertexArrayCacheMixin {

    @Inject(method = "bindVertexArray", at = @At("TAIL"))
    private void photon$applyInstanceAttributes(CallbackInfo ci) {
        PhotonInstancedDrawState.apply();
    }
}
