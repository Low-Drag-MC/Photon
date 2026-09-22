package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.photon.Photon;
import net.minecraft.resources.Identifier;

/**
 * The ids of the shortcuts the FX editor adds on top of LDLib2's built-ins, and their keymap
 * categories. Public like {@code EditorActions}: an id is how a saved keymap, a tooltip or a
 * subclass refers to a binding.
 */
public final class PhotonEditorActions {

    private PhotonEditorActions() {}

    public static final String CATEGORY_SCENE = "keymap.category.photon.scene";
    public static final String CATEGORY_PLAYBACK = "keymap.category.photon.playback";

    // Scene — gizmo tools on Unity's Q/W/E/R
    public static final Identifier GIZMO_NONE = Photon.id("scene.gizmo_none");
    public static final Identifier GIZMO_TRANSLATE = Photon.id("scene.gizmo_translate");
    public static final Identifier GIZMO_ROTATE = Photon.id("scene.gizmo_rotate");
    public static final Identifier GIZMO_SCALE = Photon.id("scene.gizmo_scale");
    public static final Identifier GIZMO_SPACE = Photon.id("scene.gizmo_space");
    public static final Identifier CYCLE_DRAW_MODE = Photon.id("scene.cycle_draw_mode");
    public static final Identifier TOGGLE_BLOOM = Photon.id("scene.toggle_bloom");

    // Playback
    public static final Identifier PLAY_PAUSE = Photon.id("playback.play_pause");
    public static final Identifier STOP = Photon.id("playback.stop");
    public static final Identifier STEP_BACK = Photon.id("playback.step_back");
    public static final Identifier STEP_FORWARD = Photon.id("playback.step_forward");
    public static final Identifier RELOAD_EFFECT = Photon.id("playback.reload_effect");
}
