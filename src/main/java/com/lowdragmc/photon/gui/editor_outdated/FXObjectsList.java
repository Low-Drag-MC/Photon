package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.editor.ui.sceneeditor.data.Transform;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.gui.widget.*;
import com.lowdragmc.lowdraglib2.gui.widget.layout.Layout;
import com.lowdragmc.lowdraglib2.utils.LocalizationUtils;
import com.lowdragmc.lowdraglib2.utils.Size;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.integration.PhotonLDLibPlugin;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import lombok.Getter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/2
 * @implNote ParticlesList
 */
@OnlyIn(Dist.CLIENT)
public class FXObjectsList extends DraggableScrollableWidgetGroup {
    private final ParticleScenePanel panel;
    @Getter
    @Nullable
    private IFXObject selected;
    private final ChildrenContainer rootContainer;
    // runtime
    private final Object2BooleanMap<IFXObject> expanded = new Object2BooleanOpenHashMap<>();


    public FXObjectsList(ParticleScenePanel panel, Size size) {
        super(0, 0, size.width, size.height);
        this.panel = panel;
        setYScrollBarWidth(4).setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(2).transform(-0.5f, 0));
        rootContainer = new ChildrenContainer(null, size.width - 4);
        addWidget(rootContainer);
        updateList();
    }

    public boolean isExpanded(IFXObject emitter) {
        if (emitter.transform().parent() == null) return true;
        return expanded.getBoolean(emitter);
    }

    public void setExpanded(IFXObject emitter, boolean expanded) {
        this.expanded.put(emitter, expanded);
    }

    public void addSceneObject(IFXObject fxObject) {
        fxObject.transform().localPosition(new Vector3f());
        panel.runtime.addSceneObject(fxObject);
        fxObject.transform().parent(panel.runtime.getRoot().transform(), false);
    }

    public void removeSceneObject(IFXObject fxObject) {
        panel.runtime.removeSceneObject(fxObject);
        expanded.removeBoolean(fxObject);
    }

    public void updateList() {
        rootContainer.updateChildren();
        panel.restartEmitters();
    }

    public void setSelectedFX(@Nullable IFXObject selected) {
        this.selected = selected;
        panel.scene.setTransformGizmoTarget(selected == null ? null : selected.transform());
        if (selected == null) {
            panel.editor.getConfigPanel().clearAllConfigurators();
        } else {
            panel.editor.getConfigPanel().openConfigurator(FXEditor.BASIC, selected);
        }
    }

    public WidgetGroup createEmitterWidget(IFXObject fxObject, int width) {
        var container = new WidgetGroup(0, 0, width, 10);
        var children = new ChildrenContainer(fxObject, width - 10);
        children.setSelfPosition(10, 10);
        container.addWidget(children);
        container.setDynamicSized(true);
        // add expand button
        container.addWidget(new ImageWidget(0, 0, 10, 10, () -> fxObject.transform().children().isEmpty() ? IGuiTexture.EMPTY :
                (isExpanded(fxObject) ? Icons.DOWN.copy().scale(0.9f) : Icons.RIGHT.copy().scale(0.9f))));
        container.addWidget(new ButtonWidget(0, 0, 10, 10, cd -> {
            if (fxObject.transform().children().isEmpty()) return;
            var isExpanded = !isExpanded(fxObject);
            setExpanded(fxObject, isExpanded);
            children.updateChildren();
        }));
        // add select button
        container.addWidget(new ButtonWidget(10, 0, width - 20, 10, cd -> setSelectedFX(fxObject)));
        // add fxObject name
        container.addWidget(new TextTextureWidget(10, 0, width - 20 , 10)
                .setText(() -> Component.literal(fxObject.getName()))
                .textureStyle(t -> t.setType(TextTexture.TextType.LEFT_HIDE))
                .setHoverTexture(ColorPattern.T_GRAY.rectTexture()).setDraggingConsumer(
                        o -> o instanceof IParticleEmitter e && e != fxObject && !fxObject.transform().isInheritedParent(e.transform()),
                        o -> {},
                        o -> {},
                        o -> {
                            if (o instanceof IParticleEmitter e) {
                                e.transform().parent(fxObject.transform());
                                updateList();
                            }
                        })
                .setDraggingProvider(() -> fxObject, (e, pos) -> new TextTexture(e.getName())));
        // add eye button
        container.addWidget(new SwitchWidget(width - 10, 0, 10, 10,
                (cd, pressed) -> fxObject.setVisible(pressed))
                .setTexture(Icons.EYE_OFF.copy().scale(0.9f), Icons.EYE.copy().scale(0.9f))
                .setPressed(fxObject.isVisible()).setSupplier(fxObject::isVisible));
        // overlay
        container.addWidget(new ImageWidget(0, 0, width, 10,
                () -> selected == fxObject ? ColorPattern.T_GREEN.rectTexture() : IGuiTexture.EMPTY));
        return container;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseOverElement(mouseX, mouseY) && button == 1) {
            var menu = TreeBuilder.Menu.start()
                    .branch(Icons.ADD_FILE, "add emitter", m -> {
                        for (var wrapper : PhotonLDLibPlugin.REGISTER_FX_OBJECTS.values()) {
                            m.leaf(wrapper.annotation().name(), () -> {
                                var emitter = wrapper.creator().get();
                                var name = emitter.getName();
                                var index = 0;
                                while (panel.runtime.objects.values().stream().anyMatch(e -> e.getName().equals(emitter.getName()))) {
                                    emitter.setName(name + "(%d)".formatted(index));
                                    index++;
                                }
                                addSceneObject(emitter);
                                updateList();
                            });
                        }
                    });
            if (selected != null && selected != panel.runtime.getRoot()) {
                // todo sub emitter
//                menu.crossLine();
//                menu.leaf(selected.isSubEmitter() ? Icons.CHECK : IGuiTexture.EMPTY, "photon.gui.editor.fx.is_sub_emitter", () -> selected.setSubEmitter(!selected.isSubEmitter()));
                menu.crossLine();
                menu.leaf("ldlib.gui.editor.menu.rename", () -> {
                    DialogWidget.showStringEditorDialog(Editor.INSTANCE, LocalizationUtils.format("ldlib.gui.editor.tips.rename") + " " +
                                    LocalizationUtils.format(selected.name()), selected.getName(),
                            s -> true,
                            s -> {
                                if (s == null) return;
                                selected.setName(s);
                            });
                });
                menu.leaf(Icons.COPY, "ldlib.gui.editor.menu.copy", () -> {
                    var name = selected.getName();
                    var copied = deepCopyFXObject(selected, selected.transform().parent());
                    copied.setName(name + " copied");
                    updateList();
                });
                menu.leaf(Icons.REMOVE_FILE, "ldlib.gui.editor.menu.remove", () -> {
                    removeSceneObject(selected);
                    setSelectedFX(null);
                    updateList();
                });
            }
            panel.editor.openMenu(mouseX, mouseY, menu);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public IFXObject deepCopyFXObject(IFXObject fxObject, Transform parent) {
        var copied = fxObject.copy(true);
        copied.transform()._refreshInternalID();
        addSceneObject(copied);
        copied.transform().parent(parent);
        for (var child : fxObject.transform().children()) {
            if (child.sceneObject() instanceof IFXObject) {
                deepCopyFXObject((IFXObject) child.sceneObject(), copied.transform());
            }
        }
        return copied;
    }

    private class ChildrenContainer extends WidgetGroup {
        @Nullable
        private final IFXObject parent;
        private int width = 0;

        private ChildrenContainer(@Nullable IFXObject parent, int width) {
            super(0, 0, width, 0);
            this.width = width;
            this.parent = parent;
            setDynamicSized(true);
            setLayout(Layout.VERTICAL_LEFT);
            updateChildren();
        }

        public Collection<IFXObject> getChildren() {
            if (parent == null) {
                return List.of(panel.runtime.getRoot());
            } else {
                return parent.transform().children().stream()
                        .filter(t -> t.sceneObject() instanceof IFXObject)
                        .map(t -> (IFXObject) t.sceneObject())
                        .toList();
            }
        }

        public void updateChildren() {
            clearAllWidgets();
            if (parent != null && !isExpanded(parent)) return;
            for (var child : getChildren()) {
                addWidget(createEmitterWidget(child, width));
            }
        }

    }

}
