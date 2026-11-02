package com.lowdragmc.photon.client.render;

/**
 * Where in the frame a Photon draw belongs — the 1.21 opaque/translucent particle queues, selected by
 * {@code RendererSetting.Layer}. A view drains one stage at a time at a pass-free point ({@link PhotonFrameSeams}).
 */
public enum PhotonStage {
    /** After the opaque draws, before any translucent one: opaque FX, occluded and tinted by what is drawn later. */
    AFTER_OPAQUE_FEATURES,
    /** After the translucent world, clouds and weather included: every blended/HDR effect. */
    AFTER_TRANSLUCENT_PARTICLES,
    /**
     * {@link FXCompositeMode#LATE}: drawn at the same seam as {@link #AFTER_TRANSLUCENT_PARTICLES}, into a
     * standalone {@link PhotonFXLayer} tested against the opaque-only depth; only its composite waits for the end of
     * the level render, where the view the draws need is gone.
     */
    DEFERRED;

    /** The frame's last Photon draw slot for a view — where the post-effect chain runs, so it sees every stage's
     *  output. Not the last constant: {@link #DEFERRED}'s chain runs after its composite, outside the drain. */
    public static final PhotonStage LAST = AFTER_TRANSLUCENT_PARTICLES;
}
