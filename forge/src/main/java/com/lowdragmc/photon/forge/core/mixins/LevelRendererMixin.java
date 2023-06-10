package com.lowdragmc.photon.forge.core.mixins;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author KilaBash
 * @date 2023/6/10
 * @implNote LevelRendererMixin
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Shadow private Frustum cullingFrustum;

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Final private RenderBuffers renderBuffers;

    /**
     * inject opaque layer being rendered
     */
    @Inject(
            method = {"renderLevel"},
            at = {@At(
                    value = "CONSTANT",
                    args = {"stringValue=entities"},
                    shift = At.Shift.BEFORE,
                    by = 1
            )}
    )
    private void prepareForParticleRendering(PoseStack poseStack, float partialTick, long finishNanoTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        PhotonParticleRenderType.prepareForParticleRendering(cullingFrustum);
        if (!Photon.isShaderModInstalled()) { // if we install shader mod, it will help to do it.
            MultiBufferSource.BufferSource bufferSource = this.renderBuffers.bufferSource();
            this.minecraft.particleEngine.render(poseStack, bufferSource, lightTexture, camera, partialTick, cullingFrustum);
        }
    }
}
