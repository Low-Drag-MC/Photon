package com.lowdragmc.photon.client.render;

import javax.annotation.Nullable;

/**
 * The FX layer a drain has finished with but deliberately <b>not</b> merged yet, and the one place
 * that knows it is still owed to the frame.
 *
 * <p>Two unrelated features park a layer here, and both want the identical treatment — hold it until
 * {@code LevelRenderer.renderLevel} has returned, then blend it onto the finished frame:
 *
 * <ul>
 *   <li>{@link FXCompositeMode#LATE} — waiting until after the clouds and the weather is precisely
 *       what stops them painting over FX that never wrote depth;</li>
 *   <li>{@code IrisCompositeMode.AFTER_PACK} — packs whose particle program writes encoded gbuffer
 *       data rather than colour, so the layer has to bypass the pack's own passes entirely.</li>
 * </ul>
 *
 * <p>Whether a chain of custom post-effects may run at {@code AFTER_PARTICLES} depends on this being
 * empty: effects there would run over a frame that does not contain the FX yet. {@code PhotonPostFX}
 * asks {@link #isPending()} for exactly that reason.
 *
 * <p>Render thread only.
 */
public final class PhotonDeferredLayer {

    @Nullable
    private static PhotonFXLayer pending;

    private PhotonDeferredLayer() {
    }

    /** Park {@code layer} for the end of the level render. */
    public static void park(PhotonFXLayer layer) {
        pending = layer;
    }

    /** Whether a layer is still owed to this frame. */
    public static boolean isPending() {
        return pending != null;
    }

    /**
     * Blend the parked layer onto the frame's output target, if there is one. Alpha is left alone: the
     * target is already opaque and its alpha channel is not ours to spend.
     */
    public static void compositePending() {
        var layer = pending;
        pending = null;
        if (layer == null) return;
        // the surface being drawn into rather than the game window — see PhotonRenderOutput
        var output = PhotonRenderOutput.color();
        if (output == null) return;
        layer.compositeTo(output, false);
    }

    /** Frame boundary: a layer nobody composited must not survive into the next frame. */
    public static void discardPending() {
        pending = null;
    }
}
