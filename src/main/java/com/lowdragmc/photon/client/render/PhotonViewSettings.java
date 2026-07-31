package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;

import javax.annotation.Nullable;

/**
 * How one view wants its FX drawn — per view, rather than the global editor-flag statics plus a
 * {@code camera instanceof SceneCamera} test this replaced.
 * <p>
 * Read at BAKE time (inside the drain), not at extraction: {@link #shaded}/{@link #wireframe} decide
 * whether geometry is generated at all, and by the drain the view's {@link IPhotonFXCollector} is in
 * hand. A view that wants plain rendering uses {@link #DEFAULT}; the editor scene publishes its
 * SceneView toggles; a third-party view publishes its own.
 *
 * @param shaded      generate the shaded geometry (off = wireframe-only view)
 * @param wireframe   also generate the wireframe overlay pass
 * @param bloom       let this view's draws participate in bloom (still gated by the global config)
 * @param postEffects the request stack whose effect chain runs over this view (null = none) — the
 *                    editor scene's is isolated from the world's, so a timeline preview never leaks
 *                    into the world render and vice versa
 * @param effects     run that stack's chain at all (the SceneView toggle). Per-view like the other
 *                    three: two views sharing a stack must be able to disagree, which a flag on the
 *                    stack itself could not express
 */
public record PhotonViewSettings(boolean shaded, boolean wireframe, boolean bloom,
                                 @Nullable PostEffectStack postEffects, boolean effects) {

    /** Plain rendering: shaded, no wireframe overlay, bloom allowed, world post effects. */
    public static final PhotonViewSettings DEFAULT =
            new PhotonViewSettings(true, false, true, PostEffectStack.GLOBAL, true);
}
