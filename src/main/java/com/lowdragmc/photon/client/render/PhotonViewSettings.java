package com.lowdragmc.photon.client.render;

/**
 * How one view wants its FX drawn — per view, rather than the global editor-flag statics plus a
 * {@code camera instanceof SceneCamera} test this replaced.
 * <p>
 * Read at BAKE time (inside the drain), not at extraction: {@link #shaded}/{@link #wireframe} decide
 * whether geometry is generated at all, and by the drain the view's {@link IPhotonFXCollector} is in
 * hand. A view that wants plain rendering uses {@link #DEFAULT}; the editor scene publishes its
 * SceneView toggles; a third-party view publishes its own.
 *
 * @param shaded    generate the shaded geometry (off = wireframe-only view)
 * @param wireframe also generate the wireframe overlay pass
 * @param bloom     let this view's draws participate in bloom (still gated by the global config)
 */
public record PhotonViewSettings(boolean shaded, boolean wireframe, boolean bloom) {

    /** Plain rendering: shaded, no wireframe overlay, bloom allowed. */
    public static final PhotonViewSettings DEFAULT = new PhotonViewSettings(true, false, true);
}
