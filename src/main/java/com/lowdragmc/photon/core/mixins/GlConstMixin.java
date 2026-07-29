package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.PhotonInstancedDrawState;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The 1.21 RGBA32F point-buffer restoration hook (approved plan C2): 26.1 texel buffers have no
 * float formats, so Photon's instanced pipelines declare PhotonPoints/PhotonData as RGBA8 and this
 * hook maps the declared format to GL_RGBA32F while a Photon instanced draw is in flight — the
 * buffers carry raw floats and {@code particle.glsl}'s 1.21 {@code texelFetch} lines stay verbatim.
 * Scoped tightly: no texture is ever created inside the draw window, only the texel-buffer respec.
 */
@Mixin(GlConst.class)
public class GlConstMixin {

    @Inject(method = "toGlInternalId(Lcom/mojang/blaze3d/textures/TextureFormat;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void photon$floatTexelBuffers(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (PhotonInstancedDrawState.active && format == TextureFormat.RGBA8) {
            cir.setReturnValue(GL30.GL_RGBA32F);
        }
    }
}
