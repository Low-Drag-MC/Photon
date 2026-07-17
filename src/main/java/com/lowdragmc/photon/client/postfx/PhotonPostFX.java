package com.lowdragmc.photon.client.postfx;

import com.lowdragmc.lowdraglib2.client.utils.ShaderUtils;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

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
        var stack = PostEffectStack.GLOBAL;
        var previewTarget = Minecraft.getInstance().getMainRenderTarget();
        if (!stack.isConsumedThisFrame()) {
            // no effects ran yet this frame — the main target IS the clean scene
            com.lowdragmc.photon.client.postfx.runtime.PostFXPreview.captureIfRequested(previewTarget);
        }
        if (!stack.hasPending() || stack.isConsumedThisFrame()) return;
        // Iris keeps its own framebuffers; discovering the right one outside the particle draw is
        // unverified (plan risk R3) — gated off under shader packs until Phase 4 adds the config.
        if (Photon.isUsingShaderPack()) return;
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        var chain = PostFXTargetPool.acquire(mainTarget.width, mainTarget.height);
        chain.copyColorFrom(mainTarget);
        var output = stack.consumeAndExecute(chain, false, mainTarget.getDepthTextureId());
        if (output != chain) {
            ShaderUtils.fastBlit(output, mainTarget);
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
        if (testEffect != null) {
            PostEffectStack.GLOBAL.submit(testEffect.path(), Map.of(), testEffect.weight());
        }
    }
}
