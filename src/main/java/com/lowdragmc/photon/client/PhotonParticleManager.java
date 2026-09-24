package com.lowdragmc.photon.client;

import com.lowdragmc.kilagraph.rendertype.runtime.KGEngineUniforms;
import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib2.client.scene.SceneCameraContext;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.photon.client.fx.ParticleTickHost;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonTime;
import com.lowdragmc.photon.client.render.PhotonViewSettings;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Map;

public class PhotonParticleManager extends ParticleManager implements ParticleTickHost {
    /** The per-frame render switches; {@link FXSceneOptions#DEFAULT} for an embedded preview. */
    public final FXSceneOptions options;
    /**
     * The FX editor's scene view when this manager belongs to it, {@code null} for an embedded
     * preview. Only kept for callers that already had it; the render path goes through
     * {@link #options}.
     */
    @Nullable
    public final SceneView sceneView;
    /** {@link ParticleTickHost} heartbeat. NOT {@link #time}: that is the timeline clock and resets
     *  in {@link #clear()}, while this must stay monotonic for {@code FXRuntime.isValid()}. */
    private long tickCounter = 0;
    /** {@link ParticleTickHost} wipe generation, bumped in {@link #clear()}. */
    private int generation = 0;
    /**
     * True while an FX scene is the thing being rendered, rather than the world.
     * <p>
     * The shader-pack path is a world-only concern: a pack's colortex layout has nothing to do with the
     * editor's picture-in-picture target, and resolving one there would composite the scene's FX into
     * the world's gbuffer. {@code IrisTargetResolver} asks this to stand down. Deliberately a plain
     * static rather than something derived from the collector — the resolver runs deep inside a draw
     * and needs the answer without a reference to anything.
     */
    private static boolean editorSceneRendering = false;
    /**
     * The editor-scene manager {@link #editorSceneRendering} is true for, so a clock read from deep
     * inside a draw can reach the timeline that owns it — see {@link #editorAnimationSeconds()}.
     * Set and cleared in lockstep with the flag, which means it spans the bake and the drain too, not
     * just extraction: an animated model is posed on whichever of those first asks for its mesh.
     */
    @Nullable
    private static PhotonParticleManager renderingManager = null;
    /** The partial tick the current frame is being drawn at, for clocks read mid-render. */
    private float lastPartialTick;

    public static boolean isEditorSceneRendering() {
        return editorSceneRendering;
    }

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
    /**
     * {@code options} is the editor's {@link SceneView} when this manager belongs to it, and anything
     * else — {@link FXSceneOptions#DEFAULT} will do — for an FX preview embedded in another mod's
     * LDLib2 scene. One constructor for both: {@code SceneView} implements the interface, so a
     * dedicated overload would only repeat the {@code instanceof} below.
     */
    public PhotonParticleManager(FXSceneOptions options) {
        this.options = options;
        this.sceneView = options instanceof SceneView view ? view : null;
    }

    public static void setFastSimulation(boolean value) {
        fastSimulation = value;
    }

    public long getRealTime() {
        return time + timeOffset;
    }

    /**
     * Seconds on the <b>timeline's</b> clock while an editor scene is the thing being rendered, or a
     * negative number when it is not (i.e. in the world).
     *
     * <p>What an animated model poses itself at in the editor, and deliberately the timeline's time
     * rather than the world's: scrubbing the playhead has to scrub the animation with it, or the author
     * is looking at a pose that belongs to no frame they can reach. {@link #getTime(float)} already
     * freezes the partial while paused, so a paused scene holds its pose.</p>
     */
    public static float editorAnimationSeconds() {
        var manager = renderingManager;
        return manager == null ? -1f : manager.getTime(manager.lastPartialTick) / 20f;
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

    /**
     * Extraction-time overrides (the 1.21 render() line 103 semantics):
     * <ul>
     *   <li>freeze the intra-tick partial while the timeline is paused — the raw game partial keeps
     *       sawtoothing 0→1 every game tick, which made {@code extractFrame}'s deltaTime oscillate
     *       (pause flicker) and per-frame interpolation jitter;</li>
     *   <li>submit this scene's particles with its own {@link PhotonViewSettings}.</li>
     * </ul>
     */
    @Override
    public void render(SubmitNodeStorage storage,
                       CameraRenderState cameraRenderState,
                       Camera camera,
                       Frustum frustum,
                       float partialTicks) {
        var frameStart = System.nanoTime();
        var drawMode = options.getDrawMode();
        var settings = new PhotonViewSettings(
                drawMode != SceneView.DrawMode.WIREFRAME,
                drawMode != SceneView.DrawMode.DRAW,
                options.isBloomEnabled(),
                // isolated from the world's stack
                PostEffectStack.EDITOR_SCENE,
                options.isEffectsEnabled());
        if (options.isMaskViewEnabled()) {
            // debug toggle: show the CustomMask contents
            PostEffectStack.EDITOR_SCENE.submit(
                    BuiltinResourceProvider.TYPE
                            .createFullPath("show_mask"),
                    Map.of(), 1f);
        }
        // the scene's own target size, for U_ViewPort and KG_ScreenSize
        var sceneTarget = RenderSystem.outputColorTextureOverride;
        var mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var targetWidth = sceneTarget != null ? sceneTarget.getWidth(0) : mainTarget.width;
        var targetHeight = sceneTarget != null ? sceneTarget.getHeight(0) : mainTarget.height;
        KGEngineUniforms
                .setScreenSizeOverride(targetWidth, targetHeight);
        // scene shaders run on the timeline's clock (Globals via PhotonGlobals, KG_Globals via KilaGraph)
        var sceneTime = getRealTime(partialTicks);
        PhotonTime.setOverride(sceneTime);
        KGEngineUniforms.setTimeOverride(sceneTime);
        var eye = cameraRenderState != null
                ? new Vector3f((float) cameraRenderState.pos.x,
                        (float) cameraRenderState.pos.y, (float) cameraRenderState.pos.z)
                : new Vector3f();
        if (cameraRenderState != null && cameraRenderState.initialized) {
            PhotonEngineUniforms.update(
                    cameraRenderState.projectionMatrix, cameraRenderState.viewRotationMatrix,
                    eye, targetWidth, targetHeight);
        } else if (SceneCameraContext.isActive()) {
            // LDLib2's camera render state carries no matrices; the scene camera context does
            PhotonEngineUniforms.update(
                    SceneCameraContext.projection(),
                    SceneCameraContext.viewRotation(),
                    eye, targetWidth, targetHeight);
        } else {
            PhotonEngineUniforms.updateViewport(targetWidth, targetHeight);
        }
        editorSceneRendering = true;
        renderingManager = this;
        lastPartialTick = partialTicks;
        try {
            PhotonViewSettings.withCurrent(settings, () ->
                    super.render(storage, cameraRenderState, camera, frustum, isPlaying ? partialTicks : 0));
        } finally {
            // scene flags stay set until afterRender(): the dispatcher still prepares and executes after this
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

    /** Runs after the scene's dispatcher executed every phase. */
    @Override
    public void afterRender() {
        editorSceneRendering = false;
        renderingManager = null;
        // the scene's target and clock are gone — anything drawn after this is the world's again
        KGEngineUniforms.clearScreenSizeOverride();
        KGEngineUniforms.clearTimeOverride();
        PhotonTime.clearOverride();
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
