package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import com.lowdragmc.photon.gui.editor.view.SceneView;
import net.minecraft.MethodsReturnNonnullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class FXEditor extends Editor {
    public final static SpriteTexture ICON = SpriteTexture.of("photon:textures/icon.png");
    public final FXHierarchyView hierarchyView = new FXHierarchyView(this);
    public final SceneView sceneView = new SceneView();

    // runtime
    @Nullable
    public FXRuntime runtime;

    public FXEditor() {
        fileMenu.addProjectProvider(FXProject.PROVIDER);
        this.icon.style(style -> style.backgroundTexture(ICON));
        this.left.addView(hierarchyView);
        this.center.addView(sceneView);
    }

    public void reloadEffect() {
        if (runtime != null) {
            sceneView.clearParticles();
            runtime.emmit(sceneView.effect);
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
