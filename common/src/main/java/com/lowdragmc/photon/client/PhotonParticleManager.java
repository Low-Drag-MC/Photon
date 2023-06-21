package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.val;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;

import java.util.Arrays;

/**
 * @author KilaBash
 * @date 2023/6/10
 * @implNote SimulatedParticleManager
 */
@Environment(EnvType.CLIENT)
public class PhotonParticleManager extends ParticleManager {

    private final long[] lastCPUTimes = new long[20];
    private int tickIndex = 0;

    @Override
    public void render(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks) {
        super.render(pMatrixStack, pActiveRenderInfo, pPartialTicks);
        PhotonParticleRenderType.finishRender();
    }

    @Override
    public void tick() {
        val startTime = System.nanoTime();
        super.tick();
        lastCPUTimes[tickIndex] = System.nanoTime() - startTime;
        tickIndex = (tickIndex + 1) % lastCPUTimes.length;
    }

    public long getCPUTime() {
        return (long) Arrays.stream(lastCPUTimes).average().orElse(0)  / 1000;
    }

}
