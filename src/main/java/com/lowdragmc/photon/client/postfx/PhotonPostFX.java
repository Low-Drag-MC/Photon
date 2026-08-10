package com.lowdragmc.photon.client.postfx;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;

/**
 * The public entry point for Photon's custom post-processing effects. Effects are per-frame
 * requests: call {@link #submit} every frame the effect should apply (from your render/tick hook);
 * stop calling and it stops next frame. Multiple requests for the same effect blend by weight.
 *
 * <p>Also owns the frame boundary hook and the {@code /photonfx} debug loop.</p>
 */
@OnlyIn(Dist.CLIENT)
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
    public static java.util.List<String> listEffectPaths() {
        var result = new java.util.ArrayList<String>();
        for (var entry : com.lowdragmc.photon.gui.editor.resource.RenderGraphResource.INSTANCE
                .getResourceInstance().listAllResources()) {
            result.add(entry.getKey().getPathWithType());
        }
        for (var entry : com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource.INSTANCE
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
     * The standalone consumption fallback (RenderLevelStageEvent.AFTER_PARTICLES): when effects are
     * requested but no Photon particle queued this frame, the particle-pipeline seam
     * ({@code RenderPassPipeline.afterRendering}) never ran — post-processing must not depend on
     * particles existing, so run the chain here over a copy of the main target instead. Builtin bloom
     * stays out of this path on purpose: it only ever applies when Photon content rendered.
     */
    public static void onLevelStageAfterParticles() {
        // Under a shader pack this stage is far too early: the pack's deferred/composite/final chain
        // has not run, so the main target does not hold the frame yet and anything we did here would
        // be re-exposed and re-tonemapped by the pack. onLevelRenderComplete() takes over.
        if (IrisCompat.isUsingShaderPack()) return;
        // Same reasoning for a parked FXCompositeMode.LATE layer: the FX are not in the frame yet, so
        // effects run here would simply not see them. onLevelRenderComplete() takes over.
        if (RenderPassPipeline.isLateLayerPending()) return;
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
     * @see com.lowdragmc.photon.core.mixins.GameRendererMixin
     */
    public static void onLevelRenderComplete() {
        boolean shaderPack = IrisCompat.isUsingShaderPack();
        boolean hadPendingLayer = RenderPassPipeline.isLateLayerPending();
        RenderPassPipeline.compositePendingLateLayer();
        if (shaderPack) {
            if (!PhotonConfig.INSTANCE.enableCustomEffectsWithShaderPack.get()) return;
        } else if (!hadPendingLayer) {
            // nothing was deferred, so the chain already ran at AFTER_PARTICLES
            return;
        }
        runChainOverMainTarget();
    }

    private static void runChainOverMainTarget() {
        var stack = PostEffectStack.GLOBAL;
        var previewTarget = UISurface.currentTarget();
        if (!stack.isConsumedThisFrame()) {
            // no effects ran yet this frame — the main target IS the clean scene
            com.lowdragmc.photon.client.postfx.runtime.PostFXPreview.captureIfRequested(previewTarget);
        }
        if (!stack.hasPending() || stack.isConsumedThisFrame()) return;
        var mainTarget = UISurface.currentTarget();
        var chain = PostFXTargetPool.acquire(mainTarget.width, mainTarget.height);
        chain.copyColorFrom(mainTarget);
        var output = stack.consumeAndExecute(chain, false, mainTarget.getDepthTextureId());
        if (output != chain) {
            SceneBlit.writeBack(output, mainTarget);
        }
        PostFXTargetPool.release(chain);
        // copyColorFrom / the chain leave other framebuffers bound — everything after this stage
        // (weather, first-person hand, HUD) must land in the main target again
        mainTarget.bindWrite(true);
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
        // mask textures are per-frame — a no-particle frame must not reuse last frame's mask
        RenderPassPipeline.clearFrameMask();
        if (testEffect != null) {
            PostEffectStack.GLOBAL.submit(testEffect.path(), Map.of(), testEffect.weight());
        }
    }
}
