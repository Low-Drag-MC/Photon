package com.lowdragmc.photon.client.render;

/**
 * Where in the frame a Photon draw belongs — the 26.1 form of the 1.21 opaque/translucent particle
 * queues, which is what {@code RendererSetting.Layer} selects. Every job carries one; a view drains
 * one stage at a time, so a single collector can put opaque FX before translucent world geometry and
 * translucent FX after it — Photon opens its own pass at each point rather than handing anything to
 * a vanilla feature renderer.
 * <p>
 * The names mirror {@code RenderLevelStageEvent}'s sub-events, which is where the world drains them.
 * Adding a stage here plus a drain call at the matching point is all it takes to let effects render
 * somewhere new.
 */
public enum PhotonStage {
    /**
     * Right after the solid feature pass. Opaque FX go here: they write depth, so translucent world
     * geometry drawn afterwards occludes correctly.
     */
    AFTER_OPAQUE_FEATURES,
    /**
     * After vanilla's translucent particles — the 1.21 slot, and where every blended/HDR effect goes.
     */
    AFTER_TRANSLUCENT_PARTICLES,
    /**
     * {@link FXCompositeMode#LATE}: drawn at the SAME seam as {@link #AFTER_TRANSLUCENT_PARTICLES},
     * but into a standalone {@link PhotonFXLayer} instead of onto the frame — only the <b>composite</b>
     * waits until after {@code LevelRenderer.renderLevel} returns, i.e. past the clouds and the weather.
     * <p>
     * <b>The draw must stay in the level pass.</b> Geometry drawn after it loses everything ambient that
     * a Photon draw reads: the modelview stack has been unwound to the identity, and the camera-derived
     * engine uniforms no longer describe the view — effects come out pinned to the screen. Deferring the
     * cheap fullscreen composite costs none of that, which is why 1.21 deferred only that too.
     */
    DEFERRED;

    /** The frame's last Photon draw slot for a view — where the post-effect chain runs, so it sees
     *  every stage's output. NOT the last constant: {@link #AFTER_LEVEL} is a deferred layer whose
     *  chain runs after its composite, outside the drain. */
    public static final PhotonStage LAST = AFTER_TRANSLUCENT_PARTICLES;
}
