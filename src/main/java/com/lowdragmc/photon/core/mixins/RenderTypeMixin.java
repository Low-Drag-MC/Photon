package com.lowdragmc.photon.core.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds Photon's {@code PhotonMaterial} uniform block during {@code RenderType.draw} — vanilla only
 * binds default uniforms + DynamicTransforms + RenderSetup textures, so the per-material UBO must be
 * bound here (same injection point as KilaGraph's RenderTypeMixin: right before {@code drawIndexed},
 * after the RenderSetup texture loop). No-op for non-Photon render types; the buffers are immutable
 * and pre-uploaded, so there is no HEAD prepare step.
 */
@Mixin(RenderType.class)
public class RenderTypeMixin {

    @Inject(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void photon$bindMaterialUniforms(MeshData mesh, CallbackInfo ci, @Local(name = "renderPass") RenderPass renderPass) {
        var slice = PhotonMaterialUniforms.sliceFor((RenderType) (Object) this);
        if (slice != null) {
            renderPass.setUniform("PhotonMaterial", slice);
        }
        var engineSlice = PhotonEngineUniforms.sliceFor((RenderType) (Object) this);
        if (engineSlice != null) {
            renderPass.setUniform("PhotonEngine", engineSlice);
        }
        var customSlice = com.lowdragmc.photon.client.render.PhotonCustomUniforms.sliceFor((RenderType) (Object) this);
        if (customSlice != null) {
            renderPass.setUniform("PhotonCustomMaterial", customSlice);
        }
    }
}
