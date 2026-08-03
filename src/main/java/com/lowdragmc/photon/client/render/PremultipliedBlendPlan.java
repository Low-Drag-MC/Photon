package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import org.jetbrains.annotations.Nullable;

/**
 * Translates an authored blend into the blend Photon must use while accumulating into a
 * <b>transparent premultiplied layer</b>, so that one {@code ONE / ONE_MINUS_SRC_ALPHA} composite
 * reproduces the authored look.
 *
 * <p>Two things change relative to drawing straight onto the scene:
 *
 * <ul>
 *   <li>The <b>alpha half</b> must carry <i>coverage</i>. Photon's authored default overwrites alpha,
 *       which is harmless when the destination alpha is ignored and fatal here, because the composite
 *       reads it back as "how much of the backdrop did we hide".</li>
 *   <li>Blends that <b>read the destination colour</b> (multiply, min/max, anything with a
 *       {@code DST_*} factor) cannot be reproduced at all: our destination is transparent black, not
 *       the scene.</li>
 * </ul>
 *
 * <p>Additive is the case worth getting right, because it is what most VFX use: coverage must stay
 * untouched ({@code ZERO / ONE}) so glow adds to the backdrop instead of hiding it.
 *
 * <p><b>26.1 shape.</b> 1.21 applied this as imperative {@code RenderSystem.blendFuncSeparate} calls
 * per material per draw. Here blending is a property of the {@code RenderPipeline}, so the translation
 * rewrites the {@link PhotonPipelines.ParticlePipelineKey} instead — the premultiplied variant is
 * simply another key, which gets its own cached pipeline for free and costs no extra state changes at
 * draw time.
 *
 * <p>The two accumulation paths differ in what they do about the unreproducible blends.
 * {@link FXCompositeMode#LATE} routes those jobs back to the in-place path via {@link #isLayerSafe} —
 * nothing is approximated, they simply keep the vanilla artefacts. A shader pack has no such choice
 * (there is no in-place path under Iris), so there they are approximated as additive and reported
 * through {@link IrisCompat#degrade}.
 */
public final class PremultipliedBlendPlan {

    /** {@code GL14.GL_FUNC_REVERSE_SUBTRACT} — the op behind {@code BlendMode.BlendFuc.REVERSE_SUB}. */
    public static final int BLEND_EQUATION_REVERSE_SUBTRACT = 32779;

    /**
     * Whether draws for {@code stage} go into a standalone layer rather than onto the frame.
     *
     * <p>Two independent reasons, and they land at DIFFERENT stages:
     * <ul>
     *   <li>{@link PhotonStage#AFTER_LEVEL} — {@link FXCompositeMode#LATE}, merged after the clouds;</li>
     *   <li><b>every</b> world stage under a shader pack — opaque included. There is no in-place path
     *       there at all: the frame belongs to the pack, and anything Photon composites onto MC's main
     *       target is overwritten by the pack's final pass. Both stages accumulate into one layer, which
     *       is merged at {@link PhotonStage#LAST} — it has to be merged inside the level render, because
     *       Iris runs its composite and final passes at the end of {@code renderLevel}, i.e. before the
     *       post-level seam. (1.21 likewise pushed both particle queues at the Iris target.)</li>
     * </ul>
     */
    public static boolean isLayerStage(PhotonStage stage) {
        if (stage == PhotonStage.DEFERRED) return true;
        return IrisCompat.isUsingShaderPack()
                && !PhotonParticleManager.isEditorSceneRendering();
    }

    /** True while a bake is generating geometry for a standalone layer. @see #accumulating() */
    private static boolean accumulating;

    /**
     * Whether pipeline keys built right now must accumulate into a transparent layer.
     *
     * <p>Read by {@code MaterialSetting.pipelineKey} rather than passed down, for the reason 1.21 gave
     * when it read the same answer off the active pipeline: a new renderer cannot forget to opt in and
     * silently punch a hole in the layer's coverage. Render thread only, set around a bake.
     */
    public static boolean accumulating() {
        return accumulating;
    }

    public static void setAccumulating(boolean value) {
        accumulating = value;
    }

    private PremultipliedBlendPlan() {
    }

    /**
     * Whether {@code key} survives {@link #premultiply} unchanged — i.e. the authored look is
     * reproduced exactly, not approximated. False for anything that reads the destination colour.
     *
     * <p>Mirrors the branch structure of {@link #premultiply}; the two must stay in sync, and this is
     * the routing predicate the drain uses to decide which jobs may join a late-composited layer.
     */
    public static boolean isLayerSafe(PhotonPipelines.ParticlePipelineKey key) {
        var blend = key.blend();
        // Blending off means "replace the destination". In a standalone layer the destination is
        // transparent black and the alpha channel is coverage, so a fragment whose shader alpha is
        // below 1 ends up SEMI-TRANSPARENT against the scene instead of opaque — and no fixed-function
        // blend state can pin coverage to 1 independently of the source alpha. Draw these in place
        // rather than quietly change how they look. (Iris has no in-place path, so premultiply still
        // handles the case for it.)
        if (blend == null) return false;
        if (!isDestinationFree(blend.sourceColor())) return false;
        var dst = blend.destColor();
        if (key.blendEquation() == PhotonPipelines.BLEND_EQUATION_ADD) {
            return dst == DestFactor.ZERO || dst == DestFactor.ONE_MINUS_SRC_ALPHA || dst == DestFactor.ONE;
        }
        if (key.blendEquation() == BLEND_EQUATION_REVERSE_SUBTRACT) {
            return dst == DestFactor.ONE;
        }
        return false;
    }

    /** Whether every key of a job is layer-safe (drawing nothing is trivially safe). */
    public static boolean areLayerSafe(Iterable<PhotonPipelines.ParticlePipelineKey> keys) {
        for (var key : keys) {
            if (!isLayerSafe(key)) return false;
        }
        return true;
    }

    /**
     * {@code key} rewritten for premultiplied accumulation. Returns an equal key when it already
     * accumulates correctly, so an unaffected pipeline is not duplicated in the cache.
     */
    public static PhotonPipelines.ParticlePipelineKey premultiply(PhotonPipelines.ParticlePipelineKey key) {
        var blend = key.blend();
        if (blend == null) {
            // "Opaque": the fragment replaces whatever was there, and covers the backdrop fully.
            // Colour factors ONE/ZERO make this identical to blending being off.
            return withBlend(key, new BlendFunction(SourceFactor.ONE, DestFactor.ZERO,
                    SourceFactor.ONE, DestFactor.ZERO), PhotonPipelines.BLEND_EQUATION_ADD);
        }

        var srcColor = blend.sourceColor();
        var dstColor = blend.destColor();
        var equation = key.blendEquation();

        if (isDestinationFree(srcColor)) {
            if (equation == PhotonPipelines.BLEND_EQUATION_ADD) {
                if (dstColor == DestFactor.ZERO) { // replace
                    return withBlend(key, new BlendFunction(srcColor, DestFactor.ZERO,
                            SourceFactor.ONE, DestFactor.ZERO), equation);
                }
                if (dstColor == DestFactor.ONE_MINUS_SRC_ALPHA) {
                    // "over": coverage accumulates the same way the colour does
                    return withBlend(key, new BlendFunction(srcColor, DestFactor.ONE_MINUS_SRC_ALPHA,
                            SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA), equation);
                }
                if (dstColor == DestFactor.ONE) {
                    return coverageFree(key, srcColor, equation);
                }
            } else if (equation == BLEND_EQUATION_REVERSE_SUBTRACT && dstColor == DestFactor.ONE) {
                // pure subtractive: dst - f(src). Also coverage-free, and exact because the
                // accumulator is float — the negative contribution survives to the composite.
                return coverageFree(key, srcColor, equation);
            }
        }

        // Everything else needs the real backdrop, which a transparent accumulator does not have.
        // Only reachable under a shader pack: the late path filters these out with isLayerSafe and
        // draws them in place instead. Constant message on purpose — degrade() dedups, but building a
        // string here would allocate on a path walked per material.
        IrisCompat.degrade("BLEND", "a material blends against the destination colour (multiply, "
                + "min/max, or a DST_* factor), which a shader pack's separate FX layer cannot "
                + "reproduce — it is approximated as additive");
        return coverageFree(key, isDestinationFree(srcColor) ? srcColor : SourceFactor.SRC_ALPHA,
                PhotonPipelines.BLEND_EQUATION_ADD);
    }

    /** Light that adds to the backdrop must not claim coverage, or it would hide what it lights. */
    private static PhotonPipelines.ParticlePipelineKey coverageFree(
            PhotonPipelines.ParticlePipelineKey key, SourceFactor srcColor, int equation) {
        return withBlend(key, new BlendFunction(srcColor, DestFactor.ONE,
                SourceFactor.ZERO, DestFactor.ONE), equation);
    }

    private static PhotonPipelines.ParticlePipelineKey withBlend(
            PhotonPipelines.ParticlePipelineKey key, @Nullable BlendFunction blend, int equation) {
        return new PhotonPipelines.ParticlePipelineKey(blend, equation, key.cull(), key.depthTest(),
                key.depthMask(), key.mode(), key.wireframe());
    }

    private static boolean isDestinationFree(SourceFactor factor) {
        return switch (factor) {
            case DST_COLOR, ONE_MINUS_DST_COLOR, DST_ALPHA, ONE_MINUS_DST_ALPHA -> false;
            default -> true;
        };
    }
}
