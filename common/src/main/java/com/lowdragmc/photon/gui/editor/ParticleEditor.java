package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.data.IProject;
import com.lowdragmc.lowdraglib.gui.editor.ui.*;
import com.lowdragmc.lowdraglib.gui.editor.ui.menu.ViewMenu;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.fx.EditorEffect;
import com.lowdragmc.photon.client.fx.IEffect;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.annotation.Nullable;
import java.io.File;

/**
 * @author KilaBash
 * @date 2022/11/30
 * @implNote ParticleEditor
 */
@LDLRegister(name = "editor.particle", group = "editor")
public class ParticleEditor extends Editor {

    @Environment(EnvType.CLIENT)
    protected ParticleScene particleScene;
    @Environment(EnvType.CLIENT)
    protected IEffect effect;
    @Getter
    @Nullable
    protected EmittersList emittersList;
    @Getter @Setter
    private boolean draggable = false;
    @Getter @Setter
    private boolean dragAll = false;
    @Getter @Setter
    private boolean renderCullBox = false;

    public ParticleEditor(File workSpace) {
        super(workSpace);
    }

    @Environment(EnvType.CLIENT)
    public ParticleScene getParticleScene() {
        return particleScene;
    }

    @Environment(EnvType.CLIENT)
    public IEffect getEditorFX() {
        return effect;
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void initEditorViews() {
        addWidget(particleScene = new ParticleScene(this));
        addWidget(toolPanel = new ToolPanel(this));
        addWidget(configPanel = new ConfigPanel(this));
        addWidget(resourcePanel = new ResourcePanel(this));
        addWidget(menuPanel = new MenuPanel(this));
        addWidget(floatView = new WidgetGroup(0, 0, getSize().width, getSize().height));
        if (menuPanel.getTabs().get("view") instanceof ViewMenu viewMenu) {
            viewMenu.openView(new ParticleInfoView());
        }
        this.effect = new EditorEffect(this);
    }

    public void restartScene() {
        if (currentProject instanceof  ParticleProject particleProject) {
            particleScene.getParticleManager().clearAllParticles();
            for (IParticleEmitter emitter : particleProject.getEmitters()) {
                var pos = emitter.self().getPos();
                var rotation = emitter.self().getRotation(0);
                emitter.reset();
                emitter.emmitToLevel(getEditorFX(), particleScene.level, pos.x, pos.y, pos.z, rotation.x, rotation.y, rotation.z);
            }
        }
    }

    public void openEmitterConfigurator(IParticleEmitter emitter) {
        getConfigPanel().openConfigurator(ConfigPanel.Tab.WIDGET, emitter);
    }

    public void clearEmitterConfigurator() {
        getConfigPanel().clearAllConfigurators(ConfigPanel.Tab.WIDGET);
    }

    @Override
    public void loadProject(IProject project) {
        if (currentProject != null) {
            currentProject.onClosed(this);
        }

        clearEmitterConfigurator();
        toolPanel.clearAllWidgets();

        if (project instanceof ParticleProject particleProject) {
            currentProject = particleProject;
            currentProject.onLoad(this);
            particleScene.getParticleManager().clearAllParticles();
            toolPanel.addNewToolBox("ldlib.gui.editor.group.particles", Icons.WIDGET_CUSTOM, emittersList = new EmittersList(this, particleProject));
        }
    }

}
