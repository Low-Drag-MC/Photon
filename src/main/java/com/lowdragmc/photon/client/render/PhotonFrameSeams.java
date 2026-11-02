package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two points of a view's render where no pass is open, and the bookkeeping that ties the view's frames to them.
 * 26.3 renders the level (and an LDLib2 scene) inside its owner's render pass, where Photon's stages can't open passes,
 * copy or clear, so the mixins on {@code LevelRenderer} and {@code WorldSceneRenderer} split that pass where Photon has
 * work:
 * <ul>
 *   <li>{@link #afterOpaque}: the opaque FX, then in the world the dynamic lights and the opaque depth snapshot; with
 *       improved transparency, what the OIT draws bind ({@link PhotonOit});</li>
 *   <li>{@link #afterTranslucent}: past every translucent draw, clouds, weather and the OIT composite included — the
 *       translucent FX, the FX layer and the post-effect chain.</li>
 * </ul>
 * A view's frames are the ones prepared between its {@link #beginPrepare} and {@link #endPrepare}. Render thread only.
 */
public final class PhotonFrameSeams {

    /** One view: the frames prepared for it this frame, and the surface it draws into (null: the game's frame). */
    public static final class View {
        private final List<PhotonWorldRenderState.Frame> frames = new ArrayList<>();
        private final boolean world;
        @Nullable
        private final GpuTextureView color;
        @Nullable
        private final GpuTextureView depth;
        private boolean preparing;
        /** A scene drawn through the OIT phases (LDLib2's {@code WorldSceneRenderer#isDrawingWithOit}). */
        private boolean oit;

        private View(boolean world, @Nullable GpuTextureView color, @Nullable GpuTextureView depth) {
            this.world = world;
            this.color = color;
            this.depth = depth;
        }

        private PhotonRenderOutput.Scope output() {
            return world || color == null ? PhotonRenderOutput.world() : PhotonRenderOutput.push(color, depth);
        }
    }

    private static final View WORLD = new View(true, null, null);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<View> SCENES = new ArrayDeque<>();

    private PhotonFrameSeams() {
    }

    public static View world() {
        return WORLD;
    }

    /** Whether the frames being prepared now join the OIT passes: the world's under improved transparency, a scene's drawn with it. */
    public static boolean isPreparingOit() {
        if (WORLD.preparing) {
            return PhotonOit.active();
        }
        var scene = SCENES.peek();
        return scene != null && scene.preparing && scene.oit && PhotonOit.enabled();
    }

    /** An LDLib2 scene renders into {@code color}/{@code depth} until the scope closes. Nests. */
    public static PhotonRenderOutput.Scope beginScene(GpuTextureView color, @Nullable GpuTextureView depth) {
        var view = new View(false, color, depth);
        SCENES.push(view);
        var output = PhotonRenderOutput.push(color, depth);
        return () -> {
            output.close();
            endView(view);
            SCENES.remove(view);
        };
    }

    /** The innermost scene being rendered, or null. */
    @Nullable
    public static View currentScene() {
        return SCENES.peek();
    }

    public static void beginPrepare(View view) {
        beginPrepare(view, false);
    }

    public static void beginPrepare(View view, boolean oit) {
        view.frames.clear();
        view.preparing = true;
        view.oit = oit;
    }

    public static void endPrepare(View view) {
        view.preparing = false;
    }

    /** The view has finished rendering: a frame nothing drained (a seam skipped) must not leak into the next. */
    public static void endView(View view) {
        view.preparing = false;
        view.frames.clear();
    }

    /** Called as {@code frame} is prepared: whether a view drains it. A frame no view claims is not drawn. */
    public static boolean claim(PhotonWorldRenderState.Frame frame) {
        View view = WORLD.preparing ? WORLD : null;
        if (view == null) {
            var scene = SCENES.peek();
            if (scene != null && scene.preparing) view = scene;
        }
        if (view == null) {
            return false;
        }
        if (!view.frames.contains(frame)) {
            view.frames.add(frame);
        }
        return true;
    }

    /** Whether {@link #afterOpaque} has anything to do — a pass spanning the point is split only then. */
    public static boolean wantsAfterOpaque(View view) {
        for (var frame : view.frames) {
            if (frame.hasWork(PhotonStage.AFTER_OPAQUE_FEATURES)) return true;
        }
        return view.world && (DynamicLightRenderer.wantsAfterOpaque() || OpaqueDepthCapture.isDemanded());
    }

    /** Whether {@link #afterTranslucent} has anything to do. */
    public static boolean wantsAfterTranslucent(View view) {
        for (var frame : view.frames) {
            if (frame.hasWork(PhotonStage.LAST)) return true;
        }
        return view.world && (PhotonPostFX.wantsStandaloneExecution() || DynamicLightRenderer.wantsAfterParticles());
    }

    /** No pass is open. */
    public static void afterOpaque(View view) {
        guarded(() -> {
            try (var ignored = view.output()) {
                IrisCompat.runWithoutPackPrograms(() -> {
                    for (var frame : view.frames) {
                        frame.execute(PhotonStage.AFTER_OPAQUE_FEATURES);
                    }
                    if (view.world) {
                        // the opaque scene the lights light includes the opaque FX
                        DynamicLightRenderer.afterOpaque();
                        OpaqueDepthCapture.capture();
                    }
                    // with improved transparency the OIT passes come next, and nothing can be made inside them
                    for (var frame : view.frames) {
                        frame.prepareOit();
                    }
                });
            }
        });
    }

    /** No pass is open. */
    public static void afterTranslucent(View view) {
        guarded(() -> {
            try (var ignored = view.output()) {
                IrisCompat.runWithoutPackPrograms(() -> {
                    for (var frame : view.frames) {
                        frame.execute(PhotonStage.LAST);
                    }
                    if (view.world) {
                        // the chain of a frame without Photon particles, and the editor preview's clean scene
                        PhotonPostFX.onLevelStageAfterParticles();
                        DynamicLightRenderer.afterParticles();
                    }
                });
            }
        });
    }

    /** Logs a failure of Photon's own work once and lets the owner's frame go on. */
    private static void guarded(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            if (REPORTED.add(String.valueOf(e.getMessage()) + e.getClass())) {
                Photon.LOGGER.error("Photon failed drawing at a frame seam", e);
            }
        }
    }

    /**
     * Runs {@link #afterOpaque} in the middle of {@code pass} (drawing into {@code color}/{@code depth}) and returns the pass
     * to continue in — {@code pass} itself when there is nothing to do.
     */
    public static RenderPass splitAfterOpaque(View view, RenderPass pass, GpuTextureView color,
                                              @Nullable GpuTextureView depth, String label) {
        if (!wantsAfterOpaque(view)) return pass;
        pass.close();
        afterOpaque(view);
        return reopen(color, depth, label);
    }

    /** {@link #afterTranslucent} in the middle of {@code pass}; see {@link #splitAfterOpaque}. */
    public static RenderPass splitAfterTranslucent(View view, RenderPass pass, GpuTextureView color,
                                                   @Nullable GpuTextureView depth, String label) {
        if (!wantsAfterTranslucent(view)) return pass;
        pass.close();
        afterTranslucent(view);
        return reopen(color, depth, label);
    }

    /** A pass continuing an owner's: the same targets, nothing cleared, the default uniforms bound. */
    public static RenderPass reopen(GpuTextureView color, @Nullable GpuTextureView depth, String label) {
        var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> label, color, Optional.empty(), depth, OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(pass);
        return pass;
    }
}
