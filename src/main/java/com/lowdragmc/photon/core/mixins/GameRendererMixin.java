package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Records the projection the level is drawn with: the camera's, times the view bob and nausea skew. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private Matrix4f photon$levelProjection(Matrix4f projection) {
        PhotonCameraUtils.setLevelProjection(projection);
        return projection;
    }
}
