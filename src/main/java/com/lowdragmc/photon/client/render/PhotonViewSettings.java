package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;

import javax.annotation.Nullable;

/**
 * How one view wants its FX drawn, captured when it submits ({@link #withCurrent}).
 *
 * @param shaded      generate the shaded geometry (off = wireframe-only view)
 * @param wireframe   also generate the wireframe overlay pass
 * @param bloom       let this view's draws participate in bloom (still gated by the global config)
 * @param postEffects the stack whose chain runs over this view (null = none)
 * @param effects     whether to run that chain (the SceneView toggle)
 */
public record PhotonViewSettings(boolean shaded, boolean wireframe, boolean bloom,
                                 @Nullable PostEffectStack postEffects, boolean effects) {

    /** Plain rendering: shaded, no wireframe overlay, bloom allowed, world post effects. */
    public static final PhotonViewSettings DEFAULT =
            new PhotonViewSettings(true, false, true, PostEffectStack.GLOBAL, true);

    @Nullable
    private static PhotonViewSettings current;

    public static PhotonViewSettings current() {
        var settings = current;
        return settings == null ? DEFAULT : settings;
    }

    /** Every Photon submit inside {@code submission} carries {@code settings}. Nests. */
    public static void withCurrent(PhotonViewSettings settings, Runnable submission) {
        var previous = current;
        current = settings;
        try {
            submission.run();
        } finally {
            current = previous;
        }
    }
}
