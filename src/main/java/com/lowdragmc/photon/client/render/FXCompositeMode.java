package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.PhotonConfig;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.Locale;

/**
 * When — and against which depth — Photon's translucent FX are merged into the frame.
 *
 * <p>Photon's translucent stage draws after the translucent world: in 26.3 after the translucent chunk layer (water,
 * glass, ice — all of which leave their depth behind, written by themselves or by the OIT composite), the clouds and
 * the weather ({@code PhotonFrameSeams}). Anything drawn there depth-tests against the nearest translucent surface:
 * water and glass hard-clip FX behind them. (26.2 drew before the clouds, which then painted over FX that never wrote
 * the depth that would reject them; that artefact is gone with the later seam.)
 *
 * <p>Draws that join the OIT passes under improved transparency ({@link PhotonOit}) are resolved per pixel with the
 * translucent world instead, whichever mode they have.
 */
public enum FXCompositeMode implements TranslatableEnum {
    /**
     * Per-emitter only: use whatever {@link PhotonConfig#fxCompositeMode} says. Never a resolved
     * value.
     */
    INHERIT,
    /**
     * Draw onto the frame after the translucent world, depth-testing against the live depth buffer — the nearest
     * translucent surface, so water and glass hide what is behind them.
     */
    VANILLA,
    /**
     * Accumulate the FX into a standalone premultiplied layer, depth-test it against a snapshot of
     * the <b>opaque-only</b> depth taken before the translucent world, and blend that layer onto the
     * frame after {@code LevelRenderer.render} has finished — after the clouds, the weather and (with
     * improved transparency) the OIT composite.
     *
     * <p>Trade-off: FX are drawn <i>over</i> translucent surfaces rather than depth-sorted with
     * them, so a distant effect renders in front of a cloud it is actually behind. This is the same
     * bargain Unity/Unreal transparent-queue VFX make, and it is far less objectionable than an
     * effect being erased by a cloud or sliced by a water surface.
     *
     * <p>Custom post-effects still apply: the drain that would normally run the chain steps aside
     * whenever a layer exists, and {@code PhotonPostFX.onLevelRenderComplete} runs it after the merge
     * instead — so effects grade a frame that contains these FX. The one cost is precision: that late
     * run works on the engine's RGBA8 target, where anything above 1.0 has already been clamped (bloom
     * is unaffected — it runs on the HDR layer itself, before the merge).
     *
     * <p>Emitters whose materials cannot be expressed in a premultiplied layer (multiply, min/max, any
     * {@code DST_*} factor) silently stay on the {@link #VANILLA} path — see
     * {@code RendererSetting.Runtime#effectiveStage}.
     *
     * <p>Under a shader pack this mode makes no difference: <i>every</i> emitter is layered there, and
     * the layer is merged into the pack's own target inside the level render rather than after it (Iris
     * consumes that buffer before the post-level seam).
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
