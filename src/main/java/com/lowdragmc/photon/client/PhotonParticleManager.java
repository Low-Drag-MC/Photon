package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.photon.client.fx.ParticleTickHost;
import com.lowdragmc.photon.client.render.PhotonEditorRenderState;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;

import java.util.Arrays;

public class PhotonParticleManager extends ParticleManager implements ParticleTickHost {
    public final SceneView sceneView;
    /** {@link ParticleTickHost} heartbeat. NOT {@link #time}: that is the timeline clock and resets
     *  in {@link #clear()}, while this must stay monotonic for {@code FXRuntime.isValid()}. */
    private long tickCounter = 0;
    /** {@link ParticleTickHost} wipe generation, bumped in {@link #clear()}. */
    private int generation = 0;
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

    // TODO(M4): the 1.21 render() override also staged bloom / editor-scene PostEffectStack routing /
    // shader game-time swap / standalone effect consumption — those return with the M3 postfx
    // executor + M4 editor milestone. The pieces below are the extraction-critical subset.

    /**
     * Extraction-time overrides (the 1.21 render() line 103 semantics):
     * <ul>
     *   <li>freeze the intra-tick partial while the timeline is paused — the raw game partial keeps
     *       sawtoothing 0→1 every game tick, which made {@code extractFrame}'s deltaTime oscillate
     *       (pause flicker) and per-frame interpolation jitter;</li>
     *   <li>stage the SceneView draw-mode flags for this scene's extraction (wireframe toggle).</li>
     * </ul>
     */
    @Override
    public void render(net.minecraft.client.renderer.SubmitNodeStorage storage,
                       net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState,
                       net.minecraft.client.Camera camera,
                       net.minecraft.client.renderer.culling.Frustum frustum,
                       float partialTicks) {
        var frameStart = System.nanoTime();
        if (sceneView != null) {
            PhotonEditorRenderState.drawShaded = sceneView.getDrawMode() != SceneView.DrawMode.WIREFRAME;
            PhotonEditorRenderState.drawWireframe = sceneView.getDrawMode() != SceneView.DrawMode.DRAW;
            PhotonEditorRenderState.bloomEnabled = sceneView.isBloomEnabled();
        }
        if (cameraRenderState != null && cameraRenderState.initialized) {
            // scene-local engine uniforms (U_* block) for custom shaders drawn in this scene.
            // U_ViewPort = the actual render target of this scene (the PIP/FBO texture the output
            // override points at during the scene render), not the main window.
            var sceneTarget = RenderSystem.outputColorTextureOverride;
            var mainTarget = Minecraft.getInstance().getMainRenderTarget();
            PhotonEngineUniforms.update(
                    cameraRenderState.projectionMatrix, cameraRenderState.viewRotationMatrix,
                    new Vector3f((float) cameraRenderState.pos.x,
                            (float) cameraRenderState.pos.y, (float) cameraRenderState.pos.z),
                    sceneTarget != null ? sceneTarget.getWidth(0) : mainTarget.width,
                    sceneTarget != null ? sceneTarget.getHeight(0) : mainTarget.height);
        }
        try {
            super.render(storage, cameraRenderState, camera, frustum, isPlaying ? partialTicks : 0);
        } finally {
            // extraction is the editor scene's CPU-side render cost — keep the F3-style stat fed
            lastFrameTimes[frameIndex] = System.nanoTime() - frameStart;
            frameIndex = (frameIndex + 1) % lastFrameTimes.length;
        }
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

    /** The editor scene's Photon draw slot: runs in the scene renderer's finally, after its
     *  translucent particles, still inside the FBO output-override scope. */
    @Override
    public void afterRender() {
        PhotonWorldRenderState.drainEditor();
        super.afterRender();
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
