package com.lowdragmc.photon.client;

import com.lowdragmc.photon.gui.editor.view.scene.SceneView;

/**
 * The per-frame render switches a {@link PhotonParticleManager} reads out of the scene it belongs to.
 * <p>
 * Photon's own FX editor answers these from its top bar ({@link SceneView} implements this directly),
 * but the manager is also what makes Photon render <em>correctly inside a UI sub-viewport</em> — it
 * publishes the scene's {@link com.lowdragmc.photon.client.render.PhotonViewSettings} on the frame's
 * collector (routing the effect chain to the isolated {@code PostEffectStack.EDITOR_SCENE}), sizes the
 * scene captures to the PIP target rather than the game window, runs the shaders on the timeline clock,
 * and flags {@code isEditorSceneRendering()} so the Iris bridge stands down.
 * <p>
 * Any mod embedding an FX preview in an LDLib2 scene wants that behaviour without wanting Photon's
 * editor, so the switches are an interface with sensible defaults rather than a hard reference to the
 * editor view. Use {@link #DEFAULT} for "just render it normally".
 */
public interface FXSceneOptions {

    /** Plain shaded draw, bloom and post effects on, no mask debug view. */
    FXSceneOptions DEFAULT = new FXSceneOptions() {};

    default SceneView.DrawMode getDrawMode() {
        return SceneView.DrawMode.DRAW;
    }

    /** Whether the scene runs the bloom pass; in-game bloom still follows the mod config. */
    default boolean isBloomEnabled() {
        return true;
    }

    /** Whether the scene's post-effect chain runs at all. */
    default boolean isEffectsEnabled() {
        return true;
    }

    /** Debug view: replace the scene with the CustomMask contents (the builtin show_mask effect). */
    default boolean isMaskViewEnabled() {
        return false;
    }
}
