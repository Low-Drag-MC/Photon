package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.editor.ui.ToolPanel;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.integration.LDLibPlugin;
import lombok.Getter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.annotation.Nullable;

/**
 * @author KilaBash
 * @date 2023/6/2
 * @implNote ParticlesList
 */
public class EmittersList extends DraggableScrollableWidgetGroup {
    private final ParticleEditor editor;
    private final ParticleProject particleProject;
    @Getter
    @Nullable
    private IParticleEmitter selected;

    public EmittersList(ParticleEditor editor, ParticleProject particleProject) {
        super(0, 0, ToolPanel.WIDTH, 100);
        this.editor = editor;
        this.particleProject = particleProject;
        setYScrollBarWidth(4).setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(2).transform(-0.5f, 0));
        particleProject.getEmitters().forEach(this::addNewEmitter);
    }

    public void addNewEmitter(IParticleEmitter emitter) {
        int yOffset = 3 + widgets.size() * 15;
        var selectableWidgetGroup = new SelectableWidgetGroup(0, yOffset, ToolPanel.WIDTH - 2, 10);
        selectableWidgetGroup.addWidget(new SwitchWidget(3, 0, 10, 10, (cd, pressed) -> emitter.setVisible(pressed))
                .setTexture(Icons.EYE_OFF, Icons.EYE)
                .setHoverTexture(ColorPattern.T_GRAY.rectTexture())
                .setSupplier(emitter::isVisible));
        selectableWidgetGroup.addWidget(new ImageWidget(14, 0, ToolPanel.WIDTH - 16, 10, new TextTexture().setSupplier(emitter::getName).setType(TextTexture.TextType.HIDE)));
        selectableWidgetGroup.setSelectedTexture(ColorPattern.T_GRAY.rectTexture());
        selectableWidgetGroup.setOnSelected(group -> {
            editor.openEmitterConfigurator(emitter);
            selected = emitter;
        });
        addWidget(selectableWidgetGroup);

        emitter.reset();
        emitter.emmitToLevel(editor.particleScene.level, 0.5, 3, 0.5);
        editor.restartScene();
    }

    public void removeEmitter(IParticleEmitter emitter) {
        int index = particleProject.emitters.indexOf(emitter);
        if (index >= 0) {
            particleProject.emitters.remove(emitter);
            widgets.remove(index);
            editor.restartScene();
            for (int i = 0; i < widgets.size(); i++) {
                if (i >= index) {
                    widgets.get(i).addSelfPosition(0, - 15);
                }
            }
        }
        if (this.selected == emitter) {
            this.selected = null;
            editor.clearEmitterConfigurator();
        }
    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseOverElement(mouseX, mouseY) && button == 1) {
            var menu = TreeBuilder.Menu.start()
                    .branch(Icons.ADD_FILE, "add emitter", m -> {
                        for (var wrapper : LDLibPlugin.REGISTER_EMITTERS.values()) {
                            m.leaf(wrapper.annotation().name(), () -> {
                                var emitter = wrapper.creator().get();
                                var name = emitter.getName();
                                var index = 0;
                                while (particleProject.getEmitters().stream().anyMatch(e -> e.getName().equals(emitter.getName()))) {
                                    emitter.setName(name + "(%d)".formatted(index));
                                    index++;
                                }
                                particleProject.getEmitters().add(emitter);
                                addNewEmitter(emitter);
                            });
                        }
                    });
            if (selected != null) {
                menu.crossLine();
                menu.leaf("ldlib.gui.editor.menu.rename", () -> {
                    DialogWidget.showStringEditorDialog(Editor.INSTANCE, LocalizationUtils.format("ldlib.gui.editor.tips.rename") + " " + LocalizationUtils.format(selected.getEmitterType()), selected.getName(),
                            s -> particleProject.getEmitters().stream().noneMatch(e -> e.getName().equals(s)),
                            s -> {
                                if (s == null) return;
                                selected.setName(s);
                            });
                });
                menu.leaf(Icons.COPY, "ldlib.gui.editor.menu.copy", () -> {
                    var name = selected.getName();
                    var tag = selected.serializeNBT();
                    IParticleEmitter emitter = IParticleEmitter.deserializeWrapper(tag);
                    if (emitter != null) {
                        emitter.setName(name + " copied");
                        particleProject.getEmitters().add(emitter);
                        addNewEmitter(emitter);
                    }
                });
                menu.leaf(Icons.REMOVE_FILE, "ldlib.gui.editor.menu.remove", () -> removeEmitter(selected));
            }
            editor.openMenu(mouseX, mouseY, menu);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

}
