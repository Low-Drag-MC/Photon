package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.keymap.EditorAction;
import com.lowdragmc.lowdraglib2.editor.keymap.KeyChord;
import com.lowdragmc.lowdraglib2.editor.keymap.KeyContext;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.utils.TransformGizmo;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import com.lowdragmc.photon.gui.editor.view.FXTimelineView;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class FXEditor extends Editor {
    public final static Identifier WINDOW_ID = LDLib2.id("fx_editor");

    public final static SpriteTexture ICON = SpriteTexture.of("photon:textures/icon.png");
    public final FXHierarchyView hierarchyView = new FXHierarchyView(this);
    public final SceneView sceneView = new SceneView(this);
    public final FXTimelineView timelineView = new FXTimelineView(this);

    // runtime
    @Nullable
    public FXRuntime runtime;

    public FXEditor() {
        this.icon.style(style -> style.backgroundTexture(ICON));
        this.leftWindow.getLeftTop().addView(hierarchyView);
        this.centerWindow.getLeftTop().addView(sceneView);
        this.bottomWindow.getLeftTop().addView(timelineView);
    }

    @Override
    protected void initMenus() {
        super.initMenus();
        fileMenu.addProjectProvider(FXProject.TYPE);
    }

    @Override
    protected Editor createNewEditorInstance() {
        return new FXEditor();
    }

    public void reloadEffect() {
        if (runtime != null) {
            sceneView.reset();
            sceneView.play();
        }
    }

    @Override
    protected void loadNewProject(IProject project, @Nullable File projectFile) {
        if (project instanceof FXProject fxProject) {
            super.loadNewProject(project, projectFile);
            this.runtime = fxProject.getFx().createInternalRuntime();
            this.runtime.root.updatePos(new Vector3f(0.5f, 2, 0.5f));
            hierarchyView.loadFXRuntime(runtime);
            timelineView.rebuild();
            sceneView.loadScene();
            reloadEffect();
        }
    }

    @Override
    protected void closeCurrentProject() {
        super.closeCurrentProject();
        hierarchyView.clearFXRuntime();
        timelineView.clear();
        sceneView.clearScene();
        runtime = null;
    }

    /**
     * The FX editor's shortcuts: the gizmo tools on Unity's Q/W/E/R with X toggling space, and the
     * transport on Space with comma/period stepping frames.
     *
     * <p>Being bare keys is what makes the {@code when} clauses load-bearing — not while typing (a
     * bare W must not retype itself into a name field) and, for the gizmo, not while flying, since
     * the scene's fly controls poll those very keys while the right button is held.</p>
     */
    @Override
    protected void initKeymap() {
        super.initKeymap();
        var notTyping = KeyContext.notTyping();
        var gizmoUsable = notTyping.and(KeyContext.of(
                context -> !sceneView.sceneEditor.isCameraMoving(), KeyContext.SPECIFICITY_STATE));

        keymap.registerAll(
                gizmoAction(PhotonEditorActions.GIZMO_NONE, TransformGizmo.Mode.NONE, GLFW.GLFW_KEY_Q, gizmoUsable),
                gizmoAction(PhotonEditorActions.GIZMO_TRANSLATE, TransformGizmo.Mode.TRANSLATE, GLFW.GLFW_KEY_W, gizmoUsable),
                gizmoAction(PhotonEditorActions.GIZMO_ROTATE, TransformGizmo.Mode.ROTATE, GLFW.GLFW_KEY_E, gizmoUsable),
                gizmoAction(PhotonEditorActions.GIZMO_SCALE, TransformGizmo.Mode.SCALE, GLFW.GLFW_KEY_R, gizmoUsable),
                EditorAction.builder(PhotonEditorActions.GIZMO_SPACE)
                        .category(PhotonEditorActions.CATEGORY_SCENE)
                        .defaultChord(KeyChord.key(GLFW.GLFW_KEY_X))
                        .when(gizmoUsable)
                        .onAction(context -> {
                            var gizmo = sceneView.sceneEditor.getTransformGizmo();
                            gizmo.setSpace(gizmo.getSpace() == TransformGizmo.Space.LOCAL
                                    ? TransformGizmo.Space.GLOBAL : TransformGizmo.Space.LOCAL);
                            return true;
                        })
                        .build(),
                EditorAction.builder(PhotonEditorActions.CYCLE_DRAW_MODE)
                        .category(PhotonEditorActions.CATEGORY_SCENE)
                        .defaultChord(KeyChord.key(GLFW.GLFW_KEY_Z))
                        .when(notTyping)
                        .onAction(context -> {
                            var modes = SceneView.DrawMode.values();
                            sceneView.setDrawMode(modes[(sceneView.getDrawMode().ordinal() + 1) % modes.length]);
                            return true;
                        })
                        .build(),
                EditorAction.builder(PhotonEditorActions.TOGGLE_BLOOM)
                        .category(PhotonEditorActions.CATEGORY_SCENE)
                        .defaultChord(KeyChord.key(GLFW.GLFW_KEY_B))
                        .when(notTyping)
                        .onAction(context -> {
                            sceneView.setBloomEnabled(!sceneView.isBloomEnabled());
                            return true;
                        })
                        .build(),

                EditorAction.builder(PhotonEditorActions.PLAY_PAUSE)
                        .category(PhotonEditorActions.CATEGORY_PLAYBACK)
                        .defaultChord(KeyChord.key(GLFW.GLFW_KEY_SPACE))
                        .when(notTyping)
                        .onAction(context -> withProject(timelineView::togglePlay))
                        .build(),
                EditorAction.builder(PhotonEditorActions.STOP)
                        .category(PhotonEditorActions.CATEGORY_PLAYBACK)
                        .defaultChord(KeyChord.shift(GLFW.GLFW_KEY_SPACE))
                        .when(notTyping)
                        .onAction(context -> withProject(timelineView::stop))
                        .build(),
                EditorAction.builder(PhotonEditorActions.STEP_BACK)
                        .category(PhotonEditorActions.CATEGORY_PLAYBACK)
                        .defaultChord(KeyChord.key(GLFW.GLFW_KEY_COMMA))
                        .when(notTyping)
                        .onAction(context -> withProject(() -> timelineView.stepFrames(-1)))
                        .build(),
                EditorAction.builder(PhotonEditorActions.STEP_FORWARD)
                        .category(PhotonEditorActions.CATEGORY_PLAYBACK)
                        .defaultChord(KeyChord.key(GLFW.GLFW_KEY_PERIOD))
                        .when(notTyping)
                        .onAction(context -> withProject(() -> timelineView.stepFrames(1)))
                        .build(),
                EditorAction.builder(PhotonEditorActions.RELOAD_EFFECT)
                        .category(PhotonEditorActions.CATEGORY_PLAYBACK)
                        .defaultChord(KeyChord.ctrl(GLFW.GLFW_KEY_R))
                        .when(KeyContext.withProject())
                        .onAction(context -> withProject(this::reloadEffect))
                        .build()
        );
    }

    /** Switching to the mode it already has still counts as handled — letting the chord fall
     *  through would hand W to something else mid-edit. */
    private EditorAction gizmoAction(Identifier id, TransformGizmo.Mode mode, int key, KeyContext when) {
        return EditorAction.builder(id)
                .category(PhotonEditorActions.CATEGORY_SCENE)
                .defaultChord(KeyChord.key(key))
                .when(when)
                .onAction(context -> {
                    sceneView.sceneEditor.getTransformGizmo().setMode(mode);
                    return true;
                })
                .build();
    }

    /** Runs {@code action} only if there is something to act on, reporting whether it ran. */
    private boolean withProject(Runnable action) {
        if (runtime == null) {
            return false;
        }
        action.run();
        return true;
    }
}
