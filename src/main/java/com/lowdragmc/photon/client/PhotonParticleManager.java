package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.photon.client.fx.ParticleTickHost;
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
public class PhotonParticleManager extends ParticleManager implements ParticleTickHost {
    public final SceneView sceneView;
    /** {@link ParticleTickHost} heartbeat. NOT {@link #time}: that is the timeline clock and resets
     *  in {@link #clear()}, while this must stay monotonic for {@code FXRuntime.isValid()}. */
    private long tickCounter = 0;
    /** {@link ParticleTickHost} wipe generation, bumped in {@link #clear()}. */
    private int generation = 0;
    // runtime
    @Nullable
    @Getter
    private static SceneView.DrawMode drawMode = null;
    /**
     * Whether the editor scene should run the bloom post-processing pass. Default {@code true} keeps
     * in-game particle bloom following the mod config; the editor's top-bar toggle relays its
     * {@link SceneView#isBloomEnabled()} here only for the duration of its own render.
     */
    @Getter
    private static boolean sceneBloomEnabled = true;
    /**
     * True while a timeline seek replays ticks that will never be rendered: particles may skip
     * pure per-tick visual recomputes (color/rotation/light — see TileParticle.updateChanges).
     * Volatile: read by parallelUpdate worker threads during the replayed ticks.
     */
    @Getter
    private static volatile boolean fastSimulation = false;
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

    public static void setFastSimulation(boolean value) {
        fastSimulation = value;
    }

    public long getRealTime() {
        return time + timeOffset;
    }

    public float getRealTime(float pPartialTicks) {
        return getRealTime() + (isPlaying ? pPartialTicks : 0);
    }

    /**
     * Timeline (0-based) time in ticks, plus the intra-tick partial while playing. Unlike
     * {@link #getRealTime()} this EXCLUDES the seed-derived {@link #timeOffset} (which only shifts
     * shader game time so noise varies per seed). The timeline ruler/playhead/preview and frame
     * animation must read this, otherwise a large random seed makes the timeline time astronomical.
     */
    public float getTime(float pPartialTicks) {
        return time + (isPlaying ? pPartialTicks : 0);
    }

    @Override
    public void render(PoseStack pMatrixStack, Camera pActiveRenderInfo, float pPartialTicks, Predicate<ParticleRenderType> renderTypeFilter) {
        drawMode = sceneView.getDrawMode();
        sceneBloomEnabled = sceneView.isBloomEnabled();
        // route post-effect submission/consumption to the isolated editor-scene stack
        com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.setEditorSceneRendering(true);
        com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.EDITOR_SCENE
                .setEffectsEnabled(sceneView.isEffectsEnabled());
        if (sceneView.isMaskViewEnabled()) {
            // top-bar debug toggle: show the CustomMask contents instead of the scene this frame
            com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.EDITOR_SCENE.submit(
                    com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider.TYPE.createFullPath("show_mask"),
                    java.util.Map.of(), 1f);
        }
        RenderSystem.setShaderGameTime(getRealTime(), isPlaying ? pPartialTicks : 0);

        var startTime = System.nanoTime();
        GlStateManager._disableScissorTest();
        super.render(pMatrixStack, pActiveRenderInfo, isPlaying ? pPartialTicks : 0, renderTypeFilter);
        consumeEditorEffectsWithoutParticles(renderTypeFilter);
        GlStateManager._enableScissorTest();
        lastFrameTimes[frameIndex] = System.nanoTime() - startTime;
        frameIndex = (frameIndex + 1) % lastFrameTimes.length;

        // roll back to previous game time
        if (Minecraft.getInstance().level != null) {
            RenderSystem.setShaderGameTime(Minecraft.getInstance().level.getGameTime(), pPartialTicks);
        }
        drawMode = null;
        sceneBloomEnabled = true;
        com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.setEditorSceneRendering(false);
    }

    /**
     * Editor-scene standalone fallback: when the scene has pending post effects but no Photon
     * particle rendered this frame, the pipeline seam ({@code RenderPassPipeline.afterRendering})
     * never ran — run the chain here over a copy of the main target so scene effects don't require
     * particles on screen (mirrors {@code PhotonPostFX.onLevelStageAfterParticles} for the world,
     * plus the sub-viewport scissor dance from {@code afterRendering}).
     */
    private void consumeEditorEffectsWithoutParticles(Predicate<ParticleRenderType> renderTypeFilter) {
        // render() runs TWICE per scene frame (WorldSceneRenderer: opaque filter then translucent).
        // Only the translucent (last) call may consume standalone — running on the first call
        // preempted the pipeline: consumedFrame got marked with doBloom=false, so the real
        // particle build passed through and silently dropped BLOOM and all effects.
        if (!renderTypeFilter.test(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT)) return;
        var stack = com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.EDITOR_SCENE;
        if (!stack.hasPending() || stack.isConsumedThisFrame()) return;
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        var chain = com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool
                .acquire(mainTarget.width, mainTarget.height);
        chain.copyColorFrom(mainTarget);
        var output = stack.consumeAndExecute(chain, false, mainTarget.getDepthTextureId());
        if (output != chain) {
            // the scene lives in a sub-viewport — the write-back must not touch the UI around it
            int[] scissorBox = new int[4];
            org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_SCISSOR_BOX, scissorBox);
            RenderSystem.enableScissor(viewportX, viewportY, viewportWidth, viewportHeight);
            com.lowdragmc.lowdraglib2.client.utils.ShaderUtils.fastBlit(output, mainTarget);
            RenderSystem.disableScissor();
            GlStateManager._scissorBox(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        }
        com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool.release(chain);
        // fastBlit/chain leave other framebuffers bound + bindWrite resets the viewport
        mainTarget.bindWrite(true);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
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
        tickCounter++;
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
        generation++; // mass-discard: cached FXRuntimes emitted into this manager turn invalid
        clearAllParticles();
        time = 0;
    }

    @Override
    public long tickCount() {
        return tickCounter;
    }

    @Override
    public int generation() {
        return generation;
    }
}
