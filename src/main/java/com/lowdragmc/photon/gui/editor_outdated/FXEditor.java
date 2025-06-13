package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.gui.editor.data.IProject;
import com.lowdragmc.lowdraglib2.gui.editor.ui.*;
import com.lowdragmc.lowdraglib2.gui.editor.ui.menu.ViewMenu;
import com.lowdragmc.lowdraglib2.gui.widget.WidgetGroup;
import com.lowdragmc.photon.client.fx.IEffect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.File;
import java.util.List;

@LDLRegister(name = "editor.fx", group = "editor")
@OnlyIn(Dist.CLIENT)
public class FXEditor extends Editor {
    public static final ConfigPanel.Tab BASIC = ConfigPanel.Tab.WIDGET;
    public static final ConfigPanel.Tab RESOURCE = ConfigPanel.Tab.RESOURCE;

    protected IEffect effect;

    public FXEditor(File workSpace) {
        super(workSpace);
    }

    public IEffect getEditorFX() {
        return effect;
    }

    @Override
    public void initEditorViews() {
        this.toolPanel = new ToolPanel(this);
        this.toolPanel.setSizeWidth(150);
        this.configPanel = new ConfigPanel(this, List.of(BASIC, RESOURCE));
        this.tabPages = new StringTabContainer(this);
        this.resourcePanel = new ResourcePanel(this);
        this.menuPanel = new MenuPanel(this);
        this.floatView = new WidgetGroup(0, 0, this.getSize().width, this.getSize().height);

        this.addWidget(this.tabPages);
        this.addWidget(this.toolPanel);
        this.addWidget(this.configPanel);
        this.addWidget(this.resourcePanel);
        this.addWidget(this.menuPanel);
        this.addWidget(this.floatView);
    }

    @Override
    public void loadProject(IProject project) {
        if (project == null || project instanceof FXProject) {
            super.loadProject(project);
        } else {
            throw new IllegalArgumentException("Invalid project type");
        }
    }

}
