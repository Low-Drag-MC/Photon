package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Predicate;

@OnlyIn(Dist.CLIENT)
public class PhotonParticleManager extends ParticleManager {
    public final SceneView sceneView;
    // runtime
    @Nullable
    @Getter
    private static SceneView.DrawMode drawMode = null;
    @Getter @Setter
    private long time = 0;
    @Getter @Setter
    private long timeOffset = 0;
    @Getter
    private boolean isPlaying;
    private final long[] lastCPUTimes = new long[60];
    private int tickIndex = 0;

    private final long[] lastFrameTimes = new long[60];
    private int frameIndex = 0;

    public PhotonParticleManager(SceneView sceneView) {
        this.sceneView = sceneView;
    }

    public long getRealTime() {
        return time + timeOffset;
    }

    public float getRealTime(float pPartialTicks) {
        return getRealTime() + (isPlaying ? pPartialTicks : 0);
    }

    @Override
    public void render(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks, Predicate<ParticleRenderType> renderTypeFilter) {
        drawMode = sceneView.getDrawMode();
        RenderSystem.setShaderGameTime(getRealTime(), isPlaying ? pPartialTicks : 0);

        var startTime = System.nanoTime();
        GlStateManager._disableScissorTest();
        super.render(pMatrixStack, pActiveRenderInfo, isPlaying ? pPartialTicks : 0, renderTypeFilter);
        GlStateManager._enableScissorTest();
        lastFrameTimes[frameIndex] = System.nanoTime() - startTime;
        frameIndex = (frameIndex + 1) % lastFrameTimes.length;

        // roll back to previous game time
        if (Minecraft.getInstance().level != null) {
            RenderSystem.setShaderGameTime(Minecraft.getInstance().level.getGameTime(), pPartialTicks);
        }
        drawMode = null;
    }

    @Override
    public void tick() {
        if (!isPlaying) {
            lastCPUTimes[tickIndex] = 0;
            tickIndex = (tickIndex + 1) % lastCPUTimes.length;
            return;
        }
        var startTime = System.nanoTime();
        tickInternal();
        lastCPUTimes[tickIndex] = System.nanoTime() - startTime;
        tickIndex = (tickIndex + 1) % lastCPUTimes.length;
    }

    public void tickInternal() {
        super.tick();
        time++;
    }

    public long getCPUTime() {
        return (long) Arrays.stream(lastCPUTimes).average().orElse(0)  / 1000;
    }

    public long getFrameTime() {
        return (long) Arrays.stream(lastFrameTimes).average().orElse(0) / 1000;
    }

    public void play() {
        if (isPlaying) return;
        isPlaying = true;
    }

    public void pause() {
        if (!isPlaying) return;
        isPlaying = false;
    }

    public void clear() {
        clearAllParticles();
        time = 0;
    }

}
