package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.val;
import net.minecraft.client.particle.ParticleRenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;

import java.util.Arrays;
import java.util.function.Predicate;

@OnlyIn(Dist.CLIENT)
public class PhotonParticleManager extends ParticleManager {

    private final long[] lastCPUTimes = new long[60];
    private int tickIndex = 0;

    private final long[] lastFrameTimes = new long[60];
    private int frameIndex = 0;

    @Override
    public void render(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks, Predicate<ParticleRenderType> renderTypeFilter) {
        val startTime = System.nanoTime();
        super.render(pMatrixStack, pActiveRenderInfo, pPartialTicks, renderTypeFilter);
        lastFrameTimes[frameIndex] = System.nanoTime() - startTime;
        frameIndex = (frameIndex + 1) % lastFrameTimes.length;
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
