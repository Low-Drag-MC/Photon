package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
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

    private final long[] lastCPUTimes = new long[60];
    private int tickIndex = 0;

    private final long[] lastFrameTimes = new long[60];
    private int frameIndex = 0;

    @Override
    public void render(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks) {
        val startTime = System.nanoTime();
        super.render(pMatrixStack, pActiveRenderInfo, pPartialTicks);
        PhotonParticleRenderType.finishRender();
        lastFrameTimes[frameIndex] = System.nanoTime() - startTime;
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

    public long getFrameTime() {
        return (long) Arrays.stream(lastFrameTimes).average().orElse(0) / 1000;
    }

}
