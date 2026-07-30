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
    AFTER_TRANSLUCENT_PARTICLES
}
