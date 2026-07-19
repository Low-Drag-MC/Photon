package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.photon.client.fx.ParticleTickHost;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;

import javax.annotation.Nullable;
import java.util.Arrays;

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

    // TODO(M4): the 1.21 render() override (drawMode/bloom staging, editor-scene PostEffectStack
    // routing, shader game-time swap, standalone effect consumption with the scissor dance) sat on
    // the old immediate ParticleManager.render(PoseStack, Camera, ...) hook. The 26.1 LDLib2
    // ParticleManager is extract/submit-based (render(SubmitNodeStorage, CameraRenderState, ...)),
    // so the editor wiring returns with the M3 postfx executor + M4 editor milestone.

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
