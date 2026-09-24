package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.PhotonShaderSources;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Serves the adapted custom-shader sources of {@link PhotonShaderSources}. */
@Mixin(ShaderManager.class)
public class ShaderManagerMixin {

    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void photon$provideAdaptedShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (PhotonShaderSources.isAdapted(id)) {
            var source = PhotonShaderSources.get(id, type);
            if (source != null) {
                cir.setReturnValue(source);
            }
        }
    }
}
