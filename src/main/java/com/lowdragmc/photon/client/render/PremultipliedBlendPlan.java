package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;

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
 * <p>The translation rewrites only the alpha half of the {@link PhotonPipelines.ParticlePipelineKey}'s blend, so
 * the premultiplied variant is just another cached pipeline.
 *
 * <p>The two accumulation paths differ in what they do about the unreproducible blends.
 * {@link FXCompositeMode#LATE} routes those jobs back to the in-place path via {@link #isLayerSafe} —
 * nothing is approximated, they simply keep the vanilla artefacts. A shader pack has no such choice
 * (there is no in-place path under Iris), so there they are approximated as additive and reported
 * through {@link IrisCompat#degrade}.
 */
public final class PremultipliedBlendPlan {

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
        var color = blend.color();
        if (!isDestinationFree(color.sourceFactor())) return false;
        var dst = color.destFactor();
        return switch (color.op()) {
            case ADD -> dst == BlendFactor.ZERO || dst == BlendFactor.ONE_MINUS_SRC_ALPHA || dst == BlendFactor.ONE;
            case REVERSE_SUBTRACT -> dst == BlendFactor.ONE;
            default -> false;
        };
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
            return key.withBlend(new BlendFunction(BlendFactor.ONE, BlendFactor.ZERO,
                    BlendFactor.ONE, BlendFactor.ZERO));
        }

        var color = blend.color();
        var srcColor = color.sourceFactor();
        var dstColor = color.destFactor();
        var op = color.op();

        if (isDestinationFree(srcColor)) {
            if (op == BlendOp.ADD) {
                if (dstColor == BlendFactor.ZERO) { // replace
                    return key.withBlend(new BlendFunction(color,
                            new BlendEquation(BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD)));
                }
                if (dstColor == BlendFactor.ONE_MINUS_SRC_ALPHA) {
                    // "over": coverage accumulates the same way the colour does
                    return key.withBlend(new BlendFunction(color,
                            new BlendEquation(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendOp.ADD)));
                }
                if (dstColor == BlendFactor.ONE) {
                    return coverageFree(key, color);
                }
            } else if (op == BlendOp.REVERSE_SUBTRACT && dstColor == BlendFactor.ONE) {
                // pure subtractive: dst - f(src). Also coverage-free, and exact because the
                // accumulator is float — the negative contribution survives to the composite.
                return coverageFree(key, color);
            }
        }

        // Everything else needs the real backdrop, which a transparent accumulator does not have.
        // Only reachable under a shader pack: the late path filters these out with isLayerSafe and
        // draws them in place instead. Constant message on purpose — degrade() dedups, but building a
        // string here would allocate on a path walked per material.
        IrisCompat.degrade("BLEND", "a material blends against the destination colour (multiply, "
                + "min/max, or a DST_* factor), which a shader pack's separate FX layer cannot "
                + "reproduce — it is approximated as additive");
        return coverageFree(key, new BlendEquation(isDestinationFree(srcColor) ? srcColor : BlendFactor.SRC_ALPHA,
                BlendFactor.ONE, BlendOp.ADD));
    }

    /** Additive light must not claim coverage: alpha keeps the destination ({@code ZERO / ONE}). */
    private static PhotonPipelines.ParticlePipelineKey coverageFree(
            PhotonPipelines.ParticlePipelineKey key, BlendEquation color) {
        return key.withBlend(new BlendFunction(color,
                new BlendEquation(BlendFactor.ZERO, BlendFactor.ONE, BlendOp.ADD)));
    }

    private static boolean isDestinationFree(BlendFactor factor) {
        return switch (factor) {
            case DST_COLOR, ONE_MINUS_DST_COLOR, DST_ALPHA, ONE_MINUS_DST_ALPHA -> false;
            default -> true;
        };
    }
}
