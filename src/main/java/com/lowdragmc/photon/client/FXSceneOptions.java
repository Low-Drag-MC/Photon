package com.lowdragmc.photon.client;

import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The per-scene render switches a {@link PhotonParticleManager} reads every frame.
 * <p>
 * Photon's own FX editor answers these from its top bar ({@link SceneView} implements this
 * directly), but the manager is also what makes Photon render <em>correctly inside a UI
 * sub-viewport</em> — it routes post effects to the isolated
 * {@code PostEffectStack.EDITOR_SCENE}, captures a sub-viewport camera, and flags itself as
 * {@code PhotonParticleManager.getRenderingManager()} so {@code RenderPassPipeline} takes its
 * scene branch instead of the world one.
 * <p>
 * Any mod embedding an FX preview in an LDLib2 scene wants that behaviour without wanting
 * Photon's editor, so the switches are an interface with sensible defaults rather than a
 * hard reference to the editor view. Use {@link #DEFAULT} for "just render it normally".
 */
@OnlyIn(Dist.CLIENT)
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
