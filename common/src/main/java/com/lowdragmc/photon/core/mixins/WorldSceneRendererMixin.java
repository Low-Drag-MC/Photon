package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.lowdraglib.client.scene.CameraEntity;
import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * @author KilaBash
 * @date 2023/6/10
 * @implNote WorldSceneRendererMixin
 */
@Mixin(WorldSceneRenderer.class)
public class WorldSceneRendererMixin {
    @Shadow(remap = false) protected ParticleManager particleManager;
    @Shadow(remap = false) protected CameraEntity cameraEntity;

    @Shadow(remap = false) protected Camera camera;

    @Inject(
            method = {"renderTESR"},
            at = {@At(value = "RETURN")},
            remap = false
    )
    private void injectRenderTESR(Collection<BlockPos> poses, PoseStack matrixStack, MultiBufferSource.BufferSource buffers, float partialTicks, CallbackInfo ci) {
        if (this.particleManager != null) {
            PhotonParticleRenderType.prepareForParticleRendering(null);
            PoseStack poseStack = new PoseStack();
            poseStack.setIdentity();
            poseStack.translate(this.cameraEntity.getX(), this.cameraEntity.getY(), this.cameraEntity.getZ());
            particleManager.render(poseStack, this.camera, partialTicks);
        }
    }
}
