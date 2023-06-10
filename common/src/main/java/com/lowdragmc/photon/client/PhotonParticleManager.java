package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;

/**
 * @author KilaBash
 * @date 2023/6/10
 * @implNote SimulatedParticleManager
 */
@Environment(EnvType.CLIENT)
public class PhotonParticleManager extends ParticleManager {
    @Override
    public void render(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks) {
        super.render(pMatrixStack, pActiveRenderInfo, pPartialTicks);
        PhotonParticleRenderType.finishRender();
    }
}
