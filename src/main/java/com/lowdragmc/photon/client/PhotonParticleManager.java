package com.lowdragmc.photon.client;

import com.lowdragmc.kilagraph.rendertype.runtime.KGEngineUniforms;
import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib2.client.scene.SceneCameraContext;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.photon.client.fx.ParticleTickHost;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.render.IPhotonFXCollector;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonStage;
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
    /** The collector this scene's particles submitted into this frame (its {@code SubmitNodeStorage}). */
    @Nullable
    private IPhotonFXCollector collector;

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
     *   <li>publish this scene's {@link PhotonViewSettings} on the collector its particles submit
     *       into, so the deferred bake honours the {@link FXSceneOptions} toggles (wireframe/shaded/
     *       bloom) for THIS view only — the world keeps drawing the same emitters plainly.</li>
     * </ul>
     */
    @Override
    public void render(SubmitNodeStorage storage,
                       CameraRenderState cameraRenderState,
                       Camera camera,
                       Frustum frustum,
                       float partialTicks) {
        var frameStart = System.nanoTime();
        collector = storage instanceof IPhotonFXCollector fx ? fx : null;
        if (collector != null) {
            var drawMode = options.getDrawMode();
            collector.photonViewSettings(new PhotonViewSettings(
                    drawMode != SceneView.DrawMode.WIREFRAME,
                    drawMode != SceneView.DrawMode.DRAW,
                    options.isBloomEnabled(),
                    // the editor's own request stack: a timeline post-process clip previewed
                    // here must not tint the world, nor the world's effects this panel. An embedded
                    // preview wants the same isolation, so it is the stack for EVERY Photon scene.
                    PostEffectStack.EDITOR_SCENE,
                    options.isEffectsEnabled()));
            if (options.isMaskViewEnabled()) {
                // top-bar debug toggle: show the CustomMask contents instead of the scene this frame.
                // Submitting it like any other request is what makes the mask sub-pass run at all —
                // the sub-pass is demand-driven on there being a pending mask consumer.
                PostEffectStack.EDITOR_SCENE.submit(
                        BuiltinResourceProvider.TYPE
                                .createFullPath("show_mask"),
                        Map.of(), 1f);
            }
        }
        // The size of the target this scene draws into — the PIP texture (sized to the widget's rect x
        // guiScale), not the window. Everything that turns gl_FragCoord into a scene-capture UV has to
        // divide by THIS, or it samples a corner of the capture and the result shifts when the panel is
        // resized. Published twice: to Photon's own U_ViewPort, and to KilaGraph's KG_ScreenSize (which
        // shadergraph screen-space nodes use, and which otherwise reads the game window).
        var sceneTarget = RenderSystem.outputColorTextureOverride;
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        var targetWidth = sceneTarget != null ? sceneTarget.getWidth(0) : mainTarget.width;
        var targetHeight = sceneTarget != null ? sceneTarget.getHeight(0) : mainTarget.height;
        KGEngineUniforms
                .setScreenSizeOverride(targetWidth, targetHeight);
        // This scene's shaders run on the TIMELINE's clock, not the world's — the 1.21
        // setShaderGameTime(getRealTime(), isPlaying ? partial : 0) semantics, which is what makes a
        // paused timeline freeze time-driven shaders too (getRealTime already zeroes the partial when
        // paused). Published twice, because there are two blocks carrying a clock: Minecraft's Globals,
        // which PhotonGlobals substitutes for Photon's own draws, and KilaGraph's KG_Globals, which its
        // Time / Game Time nodes read and only KilaGraph can rewrite.
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
            // LDLib2's buildCameraRenderState() fills only pos/blockPos, so `initialized` is false and it
            // carries no matrices. The scene publishes its real ones here instead (LDLib2 26.1.2.30 widened
            // that scope to cover submit + afterRender for exactly this) — without them U_Inverse*Matrix
            // stayed on the WORLD camera, so depth->world reconstruction (scan's ring) was wrong in scenes.
            PhotonEngineUniforms.update(
                    SceneCameraContext.projection(),
                    SceneCameraContext.viewRotation(),
                    eye, targetWidth, targetHeight);
        } else {
            // No camera to publish, but the viewport is knowable regardless — and it must be right, or
            // every scene-capture sample divides gl_FragCoord by the wrong size (debugger-verified: this
            // used to hold the world frame's 3840x2054 while the PIP texture was 2340x1308).
            PhotonEngineUniforms.updateViewport(targetWidth, targetHeight);
        }
        editorSceneRendering = true;
        try {
            super.render(storage, cameraRenderState, camera, frustum, isPlaying ? partialTicks : 0);
        } finally {
            // NOT cleared here: the bake and the drain both happen in afterRender(), and they are what
            // the Iris resolver and effectiveStage() need to see as "this is a scene, not the world".
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

    /**
     * The scene's Photon draw slot: runs in {@code WorldSceneRenderer.drawWorld}'s finally, after
     * its translucent particles, still inside the FBO output-override scope. Both stages drain here,
     * in order, rather than at the scene's per-stage hooks — {@code drawWorld} has no post-solid
     * hook at all, and {@code afterTranslucentDispatch} is already owned by LDLib2's {@code Scene}
     * (it renders the editor overlay there). Draining both at the end keeps the two layers ordered
     * against each other and depth-tested against the finished scene; the only thing a scene-side
     * opaque hook would add is letting scene TRANSLUCENT geometry blend over opaque fx, which needs
     * a new LDLib2 hook to express.
     */
    @Override
    public void afterRender() {
        try {
            if (collector != null) {
                collector.drain(PhotonStage.AFTER_OPAQUE_FEATURES);
                collector.drain(PhotonStage.AFTER_TRANSLUCENT_PARTICLES);
                collector = null;
            }
        } finally {
            editorSceneRendering = false;
        }
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
