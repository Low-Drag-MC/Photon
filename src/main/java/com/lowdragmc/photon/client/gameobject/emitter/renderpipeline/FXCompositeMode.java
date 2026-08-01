package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.photon.PhotonConfig;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.Locale;

/**
 * When — and against which depth — Photon's translucent FX are merged into the frame.
 *
 * <p>Vanilla draws its particle pass in the middle of the frame: <b>after</b> the translucent chunk
 * layer (water, glass, ice — all of which write depth) and <b>before</b> the clouds
 * ({@code RenderType.CLOUDS}, {@code LEQUAL} + {@code COLOR_DEPTH_WRITE}). Anything drawn there
 * inherits two artefacts:
 *
 * <ul>
 *   <li>clouds paint over FX, because the FX never wrote the depth that would reject them — vanilla
 *       particles dodge this only because <i>every</i> {@code ParticleRenderType} begins with
 *       {@code RenderSystem.depthMask(true)} and relies on the shader's alpha discard to decide
 *       which texels are solid enough to occlude;</li>
 *   <li>water hard-clips FX behind its surface, because the depth buffer the FX test against already
 *       contains it.</li>
 * </ul>
 */
public enum FXCompositeMode implements TranslatableEnum {
    /**
     * Per-emitter only: use whatever {@link PhotonConfig#fxCompositeMode} says. Never a resolved
     * value.
     */
    INHERIT,
    /**
     * Draw straight onto the frame during the vanilla particle pass, depth-testing against the live
     * depth buffer. Exactly what Photon has always done — both artefacts above included.
     */
    VANILLA,
    /**
     * Accumulate the FX into a standalone premultiplied layer, depth-test it against a snapshot of
     * the <b>opaque-only</b> depth taken before the translucent chunk layer, and blend that layer
     * onto the frame after {@code LevelRenderer.renderLevel} has finished — i.e. after clouds and
     * weather, and after Fabulous' {@code transparencyChain.process()}.
     *
     * <p>Trade-off: FX are drawn <i>over</i> translucent surfaces rather than depth-sorted with
     * them, so a distant effect renders in front of a cloud it is actually behind. This is the same
     * bargain Unity/Unreal transparent-queue VFX make, and it is far less objectionable than an
     * effect being erased by a cloud or sliced by a water surface.
     */
    LATE;

    /** Readable name in NeoForge's config screen, which otherwise prints the raw constant. The
     *  editor's own selector is untranslated by design ({@code EnumAccessor.getEnumName}), like every
     *  other Photon enum. */
    @Override
    public Component getTranslatedName() {
        return Component.translatable("photon.enum.fx_composite_mode." + name().toLowerCase(Locale.ROOT));
    }

    /** Collapse {@link #INHERIT} against the global config; never returns {@code INHERIT}. */
    public FXCompositeMode resolve() {
        if (this != INHERIT) return this;
        var global = PhotonConfig.INSTANCE.fxCompositeMode.get();
        // a hand-edited config could name INHERIT, which would mean nothing at global scope
        return global == INHERIT ? VANILLA : global;
    }
}
