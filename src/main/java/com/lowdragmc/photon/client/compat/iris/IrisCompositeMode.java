package com.lowdragmc.photon.client.compat.iris;

/**
 * How Photon hands its finished FX image back to the shader pack.
 */
public enum IrisCompositeMode {
    /**
     * Let the resolver classify the pack. Only ever a <i>configured</i> value — resolution never
     * returns it.
     */
    AUTO,
    /**
     * FX are accumulated into a transparent-black HDR buffer and blended into the pack's
     * draw-buffer-0 target with {@code ONE / ONE_MINUS_SRC_ALPHA}. This is the only mode that is
     * correct for packs whose particle program writes a separate premultiplied translucent layer
     * (colortex13 on Photon/SixthSurge, gbuffers_*_translucent on Kappa/Bliss/Nostalgia), and it
     * happens to also be correct when that target IS the scene colour.
     */
    PREMULTIPLIED_ACCUM,
    /**
     * The pack's particle program writes <b>encoded gbuffer data</b>, not colour — it declares
     * {@code blend.<program> = off} for a target that is not the scene colour, then decodes and
     * shades it in a later pass (Kappa, KappaPT, iterationRP). There is no way to express an
     * arbitrary emissive VFX layer in that encoding, so the FX layer is held back and composited
     * onto the finished frame after the pack's own composite/final passes instead.
     *
     * <p>FX then miss the pack's bloom/DOF/fog, which is the honest trade against writing garbage
     * into a buffer the pack is about to interpret as material data.
     */
    AFTER_PACK,
    /**
     * Debug/opt-out override: composite onto the pack's scene-colour target
     * ({@code defaultFB(Alt)} attachment 0) instead of the program's own draw buffer 0.
     */
    SCENE_REPLACE,
    /**
     * The pack's layout could not be resolved into something we can safely write to — Photon skips
     * rendering rather than corrupting the frame.
     */
    DISABLED
}
