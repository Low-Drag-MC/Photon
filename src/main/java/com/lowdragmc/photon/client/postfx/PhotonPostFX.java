package com.lowdragmc.photon.client.postfx;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.PostFXPreview;
import com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool;
import com.lowdragmc.photon.client.postfx.runtime.RenderGraphExecutor;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import com.lowdragmc.photon.client.render.PhotonDeferredLayer;
import com.lowdragmc.photon.client.render.PhotonMaskTarget;
import com.lowdragmc.photon.client.render.PhotonRenderOutput;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.RenderGraphResource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The public entry point for Photon's custom post-processing effects. Effects are per-frame
 * requests: call {@link #submit} every frame the effect should apply (from your render/tick hook);
 * stop calling and it stops next frame. Multiple requests for the same effect blend by weight.
 *
 * <p>Also owns the frame boundary hook and the {@code /photonfx} debug loop.</p>
 */
public final class PhotonPostFX {

    private record TestEffect(IResourcePath path, float weight) {}

    @Nullable
    private static TestEffect testEffect;

    private PhotonPostFX() {}

    /**
     * Request {@code effect} for the current frame. {@code params} override the effect's exposed
     * parameters by display name (Float / Vector2f/3f/4f / Integer color / Boolean...); {@code weight}
     * in (0..1] drives parameter blending and the automatic fade mix.
     */
    public static void submit(@Nullable IResourcePath effect, Map<String, Object> params, float weight) {
        PostEffectStack.GLOBAL.submit(effect, params, weight);
    }

    /** Parse a user-facing effect path: full {@code type(path)} form, else a builtin resource name.
     *  Builtin names go through the provider type's path factory — stored builtin keys carry the
     *  provider prefix ({@code built-in:invert}), which it normalizes for us. */
    @Nullable
    public static IResourcePath parsePath(String text) {
        if (IResourcePath.PATH_WITH_TYPE_PATTERN.matcher(text.trim()).matches()) {
            return IResourcePath.parse(text);
        }
        return BuiltinResourceProvider.TYPE.createFullPath(text.trim());
    }

    /** Every requestable effect path (render graphs + bare fullscreen graphs), exactly as
     *  {@code /photonfx test} accepts them — the {@code /photonfx list} backing. */
    public static List<String> listEffectPaths() {
        var result = new ArrayList<String>();
        for (var entry : RenderGraphResource.INSTANCE
                .getResourceInstance().listAllResources()) {
            result.add(entry.getKey().getPathWithType());
        }
        for (var entry : FullscreenShaderGraphResource.INSTANCE
                .getResourceInstance().listAllResources()) {
            result.add(entry.getKey().getPathWithType() + " (fullscreen)");
        }
        return result;
    }

    // ---- debug loop (/photonfx) ------------------------------------------------------------------

    /** Keep re-submitting {@code path} every frame until {@link #clearTestEffect()} — the smoke-test
     *  loop behind {@code /photonfx test}. */
    public static void setTestEffect(@Nullable IResourcePath path, float weight) {
        testEffect = path == null ? null : new TestEffect(path, weight);
    }

    public static void clearTestEffect() {
        testEffect = null;
    }

    /**
     * The preview capture fallback (RenderLevelStageEvent.AFTER_PARTICLES). The chain itself no longer
     * needs a standalone path: the world's drain runs it whenever a request is pending, whether or not
     * a single particle queued — see {@code PhotonWorldRenderState.drain}. What is left is the editor
     * preview's clean-scene copy for the frames where the drain never got that far (effects disabled,
     * or the HDR target unavailable).
     */
    public static void onLevelStageAfterParticles() {
        // Under a shader pack this stage is far too early: the pack's deferred/composite/final chain
        // has not run, so the main target does not hold the frame yet and anything we did here would
        // be re-exposed and re-tonemapped by the pack. onLevelRenderComplete() takes over.
        if (IrisCompat.isUsingShaderPack()) return;
        // Same reasoning for a parked FXCompositeMode.LATE layer: the FX are not in the frame yet, so
        // effects run here would simply not see them. onLevelRenderComplete() takes over.
        if (PhotonDeferredLayer.isPending()) return;
        runChainOverMainTarget();
    }

    /**
     * The slot for compositing a deferred FX layer, and for the custom effect chain whenever one was
     * deferred with it. Sits after {@code LevelRenderer.renderLevel} has returned — i.e. after the
     * clouds and weather, after Fabulous' transparency chain, and (under a pack) after Iris'
     * {@code finalizeLevelRendering()} but before its colour-space conversion. Either way the main
     * render target holds a finished frame.
     *
     * <p>Two kinds of layer are parked for here, and both want the same treatment: composite first,
     * effects second, so effects operate on an image that contains the FX.
     *
     * <ul>
     *   <li>{@code IrisCompositeMode.AFTER_PACK} — packs whose particle program writes encoded
     *       gbuffer data rather than colour.</li>
     *   <li>{@code FXCompositeMode.LATE} — the plain path, where waiting until here is what keeps the
     *       clouds from painting over the FX.</li>
     * </ul>
     *
     * @see com.lowdragmc.photon.client.PhotonClientListeners#onRenderLevelStageAfterLevel
     */
    public static void onLevelRenderComplete() {
        boolean shaderPack = IrisCompat.isUsingShaderPack();
        boolean hadPendingLayer = PhotonDeferredLayer.isPending();
        PhotonDeferredLayer.compositePending();
        if (shaderPack) {
            if (!PhotonConfig.INSTANCE.enableCustomEffectsWithShaderPack.get()) return;
        } else if (!hadPendingLayer) {
            // nothing was deferred, so the chain already ran at AFTER_PARTICLES
            return;
        }
        runChainOverMainTarget();
    }

    /**
     * Run the effect chain over the frame's output target.
     *
     * <p>Normally the chain runs inside the drain of {@link com.lowdragmc.photon.client.render.PhotonStage#LAST},
     * over Photon's HDR target — that is cheaper and higher precision, and it is why 26.1 dropped the
     * standalone path this method used to be. It comes back for exactly one case: a deferred layer is
     * merged AFTER that drain, so a chain that already ran would not contain the deferred FX. The drain
     * therefore steps aside when it can see deferred jobs still queued
     * ({@code PhotonWorldRenderState.drain}), and the chain runs here instead, over a frame that now has
     * them in it.
     *
     * <p>Precision note: at this point the picture lives in the engine's RGBA8 target, so anything the
     * FX pushed above 1.0 is already clamped. That is inherent to compositing late and is the same
     * bargain 1.21 made.
     */
    private static void runChainOverMainTarget() {
        var stack = PostEffectStack.GLOBAL;
        // the surface being drawn into, not the game window: with the editor hosted off-screen (a PIP
        // visual layer, or a UI in its own OS window) those differ, and writing the chain back to the
        // window would put it somewhere nobody is looking
        var color = PhotonRenderOutput.color();
        var depth = PhotonRenderOutput.depth();
        if (!stack.isConsumedThisFrame()) {
            // no chain ran this frame — the output target IS the clean scene
            PostFXPreview.captureIfRequested(color, depth);
        }
        // isConsumedThisFrame is the load-bearing half: under a shader pack the LAST drain already ran
        // the chain (nothing defers there), and consumeAndExecute would no-op anyway — this just says so.
        if (color == null || stack.isConsumedThisFrame() || !stack.wantsExecution()) return;
        var inputs = RenderGraphExecutor.FrameInputs
                .of(color, depth)
                .withSampleableDepth();
        // The CustomMask this frame's drains already wrote. Without it a mask-reading effect (the
        // outline pass, anything with a MaskFilter) resolves to "no mask" and silently does nothing —
        // the drain-side chain passes it, so this path has to as well.
        var mask = PhotonMaskTarget.writtenThisFrame(
                color.getWidth(0), color.getHeight(0));
        if (mask != null) {
            inputs = inputs.withMask(mask.colorView(), mask.depthView());
        }
        // no bloom step: bloom is an HDR operation and already ran on the layer, where the overbright
        // still existed. Passing it here would bloom an image that has been clamped to 1.
        var output = stack.consumeAndExecute(inputs, null);
        if (output != null) {
            SceneBlit.writeBack(output, color);
        }
    }

    /**
     * The frame boundary (RenderFrameEvent.Post — fires for every render frame, in-world and in the
     * editor screen alike): recycle stack outputs, drop stale requests, advance the pool clock (which
     * keys the once-per-frame consumption guard), then queue next frame's test request.
     */
    public static void onFrameEnd() {
        PostEffectStack.GLOBAL.onFrameEnd();
        PostEffectStack.EDITOR_SCENE.onFrameEnd();
        PostFXTargetPool.endFrame();
        if (testEffect != null) {
            PostEffectStack.GLOBAL.submit(testEffect.path(), Map.of(), testEffect.weight());
        }
    }
}
