package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.List;

/**
 * Translates an authored {@link BlendMode} into the blend state Photon must use while accumulating
 * into a transparent premultiplied buffer, so that a single {@code ONE / ONE_MINUS_SRC_ALPHA}
 * composite reproduces the authored look.
 *
 * <p>Two things change relative to drawing straight onto the scene:
 *
 * <ul>
 *   <li>The <b>alpha half</b> must carry <i>coverage</i>. Photon's authored default is
 *       {@code ONE / ZERO} on alpha, which overwrites it — harmless when the destination alpha is
 *       ignored, fatal here, because the composite reads it back as "how much of the backdrop did
 *       we hide". This is the failure {@code SceneBlit}'s javadoc already describes, promoted from
 *       cosmetic to load-bearing.</li>
 *   <li>Blends that <b>read the destination colour</b> (multiply, min/max, anything with a
 *       {@code DST_*} factor) cannot be reproduced at all: our destination is transparent black,
 *       not the scene.</li>
 * </ul>
 *
 * <p>Additive is the case worth getting right, because it is what most VFX use: coverage must stay
 * untouched ({@code ZERO / ONE}) so glow adds to the backdrop instead of hiding it.
 *
 * <p>The two accumulation paths differ in what they do about the unreproducible blends.
 * {@link FXCompositeMode#LATE} routes those passes back to the in-place path via
 * {@link #isLayerSafe} — nothing is approximated, they simply keep the vanilla artefacts. A shader
 * pack has no such choice (there is no in-place path under Iris), so there they are approximated as
 * additive and reported through {@link IrisCompat#degrade}.
 */
public final class PremultipliedBlendPlan {

    private PremultipliedBlendPlan() {
    }

    /**
     * Whether {@code mode} survives {@link #applyPremultiplied} unchanged — i.e. the authored look
     * is reproduced exactly, not approximated. False for anything that reads the destination colour.
     *
     * <p>Mirrors the branch structure of {@link #applyPremultiplied}; the two must stay in sync, and
     * this is the routing predicate {@link RenderPassPipeline} uses to decide which passes may join
     * a late-composited layer.
     */
    public static boolean isLayerSafe(BlendMode mode) {
        // Blending off means "replace the destination". In a standalone layer the destination is
        // transparent black and the alpha channel is coverage, so a fragment whose shader alpha is
        // below 1 ends up SEMI-TRANSPARENT against the scene instead of opaque — and no fixed-function
        // blend state can pin coverage to 1 independently of the source alpha. Draw these in place
        // rather than quietly change how they look. (Iris has no in-place path, so
        // applyPremultiplied still handles the case for it.)
        if (!mode.isEnableBlend()) return false;
        if (!isDestinationFree(mode.getSrcColorFactor())) return false;
        var dstColor = mode.getDstColorFactor();
        return switch (mode.getBlendFunc()) {
            case ADD -> dstColor == DestFactor.ZERO
                    || dstColor == DestFactor.ONE_MINUS_SRC_ALPHA
                    || dstColor == DestFactor.ONE;
            case REVERSE_SUB -> dstColor == DestFactor.ONE;
            default -> false;
        };
    }

    /** Whether every material of a pass is layer-safe (an empty list draws nothing, so it is). */
    public static boolean areLayerSafe(List<MaterialSetting> materials) {
        for (var material : materials) {
            if (!isLayerSafe(material.getBlendMode())) return false;
        }
        return true;
    }

    /**
     * Set the blend state for {@code mode} translated into premultiplied accumulation. Applies
     * directly rather than returning a plan object — this runs per material per draw.
     */
    public static void applyPremultiplied(BlendMode mode) {
        if (!mode.isEnableBlend()) {
            // "Opaque": the fragment replaces whatever was there, and covers the backdrop fully.
            // Colour factors ONE/ZERO make this identical to blending being off.
            apply(SourceFactor.ONE, DestFactor.ZERO, SourceFactor.ONE, DestFactor.ZERO,
                    BlendMode.BlendFuc.ADD.op);
            return;
        }

        var srcColor = mode.getSrcColorFactor();
        var dstColor = mode.getDstColorFactor();
        var equation = mode.getBlendFunc();

        if (isDestinationFree(srcColor)) {
            if (equation == BlendMode.BlendFuc.ADD) {
                if (dstColor == DestFactor.ZERO) {
                    // replace
                    apply(srcColor, DestFactor.ZERO, SourceFactor.ONE, DestFactor.ZERO, equation.op);
                    return;
                }
                if (dstColor == DestFactor.ONE_MINUS_SRC_ALPHA) {
                    // "over": coverage accumulates the same way the colour does
                    apply(srcColor, DestFactor.ONE_MINUS_SRC_ALPHA,
                            SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA, equation.op);
                    return;
                }
                if (dstColor == DestFactor.ONE) {
                    applyCoverageFree(srcColor, equation.op);
                    return;
                }
            } else if (equation == BlendMode.BlendFuc.REVERSE_SUB && dstColor == DestFactor.ONE) {
                // pure subtractive: dst - f(src). Also coverage-free, and exact because the
                // accumulator is float — the negative contribution survives to the composite.
                applyCoverageFree(srcColor, equation.op);
                return;
            }
        }

        // Everything else needs the real backdrop, which a transparent accumulator does not have.
        // Only reachable under a shader pack: the late path filters these out with isLayerSafe and
        // draws them in place instead. Constant message on purpose — this runs per material per
        // draw, and string building here would allocate on the hot path even though degrade() dedups.
        IrisCompat.degrade("BLEND", "a material blends against the destination colour (multiply, "
                + "min/max, or a DST_* factor), which a shader pack's separate FX layer cannot "
                + "reproduce — it is approximated as additive");
        applyCoverageFree(isDestinationFree(srcColor) ? srcColor : SourceFactor.SRC_ALPHA,
                BlendMode.BlendFuc.ADD.op);
    }

    /** Light that adds to the backdrop must not claim coverage, or it would hide what it lights. */
    private static void applyCoverageFree(SourceFactor srcColor, int equation) {
        apply(srcColor, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE, equation);
    }

    private static void apply(SourceFactor srcColor, DestFactor dstColor,
                              SourceFactor srcAlpha, DestFactor dstAlpha, int equation) {
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(equation);
        RenderSystem.blendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
    }

    private static boolean isDestinationFree(SourceFactor factor) {
        return switch (factor) {
            case DST_COLOR, ONE_MINUS_DST_COLOR, DST_ALPHA, ONE_MINUS_DST_ALPHA -> false;
            default -> true;
        };
    }
}
