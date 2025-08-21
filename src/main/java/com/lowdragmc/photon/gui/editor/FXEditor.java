package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class FXEditor extends Editor {
    public final static ResourceLocation WINDOW_ID = LDLib2.id("fx_editor");

    public final static SpriteTexture ICON = SpriteTexture.of("photon:textures/icon.png");
    public final FXHierarchyView hierarchyView = new FXHierarchyView(this);
    public final SceneView sceneView = new SceneView(this);

    // runtime
    @Nullable
    public FXRuntime runtime;

    public FXEditor() {
        this.icon.style(style -> style.backgroundTexture(ICON));
        this.leftWindow.getLeftTop().addView(hierarchyView);
        this.centerWindow.getLeftTop().addView(sceneView);
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
            sceneView.loadScene();
            reloadEffect();
        }
    }

    @Override
    protected void closeCurrentProject() {
        super.closeCurrentProject();
        hierarchyView.clearFXRuntime();
        sceneView.clearScene();
        runtime = null;
    }
}
