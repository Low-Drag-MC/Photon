package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import lombok.Getter;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaGutter;
import org.appliedenergistics.yoga.YogaOverflow;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class FXHierarchyView extends View {
    public record DraggingNode(FXObjectTreeNode draggedNode) {}
    public final FXEditor fxEditor;
    public final ScrollerView scrollerView = new ScrollerView();
    public final TreeList<FXObjectTreeNode> treeList = new TreeList<>();

    // runtime
    private long lastClickTime = 0;
    @Getter @Nullable
    private FXRuntime runtime;
    @Getter @Nullable
    private FXObjectTreeNode rootNode;

    public FXHierarchyView(FXEditor fxEditor) {
        super("editor.fx_object.hierarchy");
        this.fxEditor = fxEditor;
        this.getLayout().setWidthPercent(100.0F);
        this.getLayout().setHeightPercent(100.0F);

        this.scrollerView.layout((layout) -> {
            layout.setWidthPercent(100.0F);
            layout.setHeightPercent(100.0F);
        });
        this.addChild(this.scrollerView);
        scrollerView.addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown, true);
        scrollerView.addScrollViewChild(treeList
                .setSupportMultipleSelection(true)
                .setNodeUISupplier((node) -> {
                    UIElement container = (new UIElement()).layout((layout) -> {
                        layout.setFlexDirection(YogaFlexDirection.ROW);
                        layout.setGap(YogaGutter.ALL, 2.0F);
                        layout.setHeight(10.0F);
                        layout.setFlex(1.0F);
                    }).addChildren();
                    UIElement icon = (new UIElement()).layout((layout) -> {
                        layout.setAspectRatio(1.0F);
                        layout.setHeightPercent(100.0F);
                    }).style((style) -> style.backgroundTexture(node.getKey().getIcon()));
                    TextElement label = new TextElement();
                    label.textStyle((style) -> {
                        style.textWrap(TextWrap.HOVER_ROLL).textAlignVertical(Vertical.CENTER);
                    }).setText(node.getKey().getName(), false).layout((layout) -> {
                        layout.setHeightPercent(100.0F);
                        layout.setFlex(1.0F);
                    }).setOverflow(YogaOverflow.HIDDEN).addEventListener(UIEvents.TICK, e -> {
                        label.setText(Component.literal(node.getKey().getName()));
                    }).addEventListener(UIEvents.TICK, e -> {
                        label.getTextStyle().textColor(node.getKey().isVisible() ? ColorPattern.WHITE.color : ColorPattern.LIGHT_GRAY.color);
                    });
                    return container.addChildren(icon, label);
                })
                .setOnSelectedChanged(selected -> {
                    if (selected.size() == 1) {
                        fxEditor.sceneView.fxObjectInfoView.inspect(selected.stream().findFirst().get().getKey());
                    } else {
                        fxEditor.sceneView.fxObjectInfoView.clear();
                    }
                })
                .setOnNodeUICreated((node, nodeUI) -> {
                    var eyeButton = new Button().noText().setOnClick(e -> {
                        node.getKey().setSelfVisible(!node.getKey().isSelfVisible());
                    }).buttonStyle(style -> {
                        style.baseTexture(DynamicTexture.of(() -> node.getKey().isSelfVisible() ? Icons.EYE : Icons.EYE_OFF));
                        style.hoverTexture(DynamicTexture.of(() -> (node.getKey().isSelfVisible() ? Icons.EYE : Icons.EYE_OFF)
                                .copy().setColor(ColorPattern.LIGHT_GRAY.color)));
                        style.pressedTexture(DynamicTexture.of(() -> (node.getKey().isSelfVisible() ? Icons.EYE : Icons.EYE_OFF)
                                .copy().setColor(ColorPattern.LIGHT_GRAY.color)));
                    }).layout((layout) -> {
                        layout.setWidth(7);
                        layout.setHeight(7);
                    });
                    nodeUI.addChildAt(eyeButton, 0);
                    nodeUI.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                        if (e.button == 0) {
                            lastClickTime = System.currentTimeMillis();
                        }
                    });
                    nodeUI.addEventListener(UIEvents.MOUSE_LEAVE, e -> {
                        if (lastClickTime != 0 && isMouseDown(0) && treeList.getSelected().size() == 1) {
                            nodeUI.startDrag(new DraggingNode(node), new TextTexture(node.getKey().getName()));
                        }
                        lastClickTime = 0;
                    }, true);
                    nodeUI.addEventListener(UIEvents.MOUSE_UP, e -> {
                        var fxObject = node.getKey();
                        if (treeList.getSelected().size() == 1) {
                            if (fxEditor.inspectorView.inspector.getInspectedConfigurable() != fxObject) {
                                fxEditor.inspectorView.inspect(fxObject);
                                fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(fxObject.transform(), () -> {
                                    fxEditor.historyView.recordSerializableObject(Component.translatable("photon.transform"), fxObject.transform(), fxObject);
                                });
                            }
                        } else {
                            fxEditor.inspectorView.clear();
                            fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
                        }
                        lastClickTime = 0;
                    });
                    nodeUI.addEventListener(UIEvents.DRAG_ENTER, e -> {
                        if (e.dragHandler.getDraggingObject() instanceof DraggingNode(var dragged) && dragged != node) {
                            var mode = isMouseOverNodeAbove(e) ? 0 : isMouseOverNodeCenter(e) ? 1 : isMouseOverNodeBelow(e) ? 2 : -1;
                            e.currentElement.style(style -> style.overlayTexture(createDraggingOverlay(mode)));
                        }
                    });
                    nodeUI.addEventListener(UIEvents.DRAG_END, e -> {
                        e.currentElement.style(style -> style.overlayTexture(IGuiTexture.EMPTY));
                    });
                    nodeUI.addEventListener(UIEvents.DRAG_UPDATE, e -> {
                        if (e.dragHandler.getDraggingObject() instanceof DraggingNode(var dragged) && dragged != node) {
                            var mode = isMouseOverNodeAbove(e) ? 0 : isMouseOverNodeCenter(e) ? 1 : isMouseOverNodeBelow(e) ? 2 : -1;
                            e.currentElement.style(style -> style.overlayTexture(createDraggingOverlay(mode)));
                        }
                    });
                    nodeUI.addEventListener(UIEvents.DRAG_PERFORM, e -> {
                        e.currentElement.style(style -> style.overlayTexture(IGuiTexture.EMPTY));
                        if (e.dragHandler.getDraggingObject() instanceof DraggingNode(var dragged) && dragged != node) {
                            var target = node.getKey().transform();
                            var toMoved = dragged.getKey().transform();
                            if (target.isInheritedParent(toMoved)) return;
                            if (isMouseOverNodeAbove(e)) {
                                // sibling
                                var originalParent = toMoved.parent();
                                var originalSiblingIndex = toMoved.getSiblingIndex();
                                var newParent = target.parent();
                                var newSiblingIndex = target.getSiblingIndex();
                                if (newParent == null) return;
                                if (originalParent == newParent) {
                                    if (originalSiblingIndex < newSiblingIndex) {
                                        newSiblingIndex--;
                                    }
                                }
                                var finalNewSiblingIndex = newSiblingIndex;
                                fxEditor.historyView.pushHistory(Component.translatable("photon.move_fx_object"), EditAction.of(
                                        () -> {
                                            toMoved.parent(newParent, true);
                                            toMoved.setSiblingIndex(finalNewSiblingIndex);
                                        },
                                        () -> {
                                            toMoved.parent(originalParent, true);
                                            toMoved.setSiblingIndex(originalSiblingIndex);
                                        }
                                ));
                            } else if (isMouseOverNodeCenter(e)) {
                                // children
                                var originalParent = toMoved.parent();

                                fxEditor.historyView.pushHistory(Component.translatable("photon.move_fx_object"), EditAction.of(
                                        () -> {
                                            toMoved.parent(target, true);
                                        },
                                        () -> {
                                            toMoved.parent(originalParent, true);
                                        }
                                ));
                            } else if (isMouseOverNodeBelow(e)) {
                                // sibling
                                var originalParent = toMoved.parent();
                                var originalSiblingIndex = toMoved.getSiblingIndex();
                                var newParent = target.parent();
                                var newSiblingIndex = target.getSiblingIndex() + 1;
                                if (newParent == null) return;
                                if (originalParent == newParent) {
                                    if (originalSiblingIndex < newSiblingIndex) {
                                        newSiblingIndex--;
                                    }
                                }
                                var finalNewSiblingIndex = newSiblingIndex;
                                fxEditor.historyView.pushHistory(Component.translatable("photon.move_fx_object"), EditAction.of(
                                        () -> {
                                            toMoved.parent(newParent, true);
                                            toMoved.setSiblingIndex(finalNewSiblingIndex);
                                        },
                                        () -> {
                                            toMoved.parent(originalParent, true);
                                            toMoved.setSiblingIndex(originalSiblingIndex);
                                        }
                                ));
                            }
                        }
                    });
                }));
    }

    public void clearFXRuntime() {
        this.treeList.setRoot(null);
        this.runtime = null;
        this.rootNode = null;
    }

    public void loadFXRuntime(@Nonnull FXRuntime runtime) {
        this.runtime = runtime;
        this.rootNode = new FXObjectTreeNode(runtime.root);
        this.treeList.setRoot(rootNode);
    }

    private boolean isSelectedNodeValid(Set<FXObjectTreeNode> selected) {
        return (!selected.isEmpty() && selected.stream().findAny().get() != rootNode) && selected.stream()
                .map(FXObjectTreeNode::getKey)
                .map(IFXObject::transform)
                .map(Transform::parent).distinct().count() <= 1;
    }

    private boolean isMouseOverNodeAbove(UIEvent event) {
        var ui = event.currentElement;
        var x = ui.getPositionX();
        var y = ui.getPositionY();
        var width = ui.getSizeWidth();
        var height = ui.getSizeHeight();
        return isMouseOver(x, y, width, height / 3, event.x, event.y);
    }

    private boolean isMouseOverNodeCenter(UIEvent event) {
        var ui = event.currentElement;
        var x = ui.getPositionX();
        var y = ui.getPositionY();
        var width = ui.getSizeWidth();
        var height = ui.getSizeHeight();
        return isMouseOver(x, y + height / 3, width, height / 3, event.x, event.y);
    }

    private boolean isMouseOverNodeBelow(UIEvent event) {
        var ui = event.currentElement;
        var x = ui.getPositionX();
        var y = ui.getPositionY();
        var width = ui.getSizeWidth();
        var height = ui.getSizeHeight();
        return isMouseOver(x, y + height * 2 / 3, width, height / 3, event.x, event.y);
    }

    private IGuiTexture createDraggingOverlay(int mode) {
        if (mode == 0) {
            return (graphics, mouseX, mouseY, x, y, width, height, partialTicks) -> {
                DrawerHelper.drawSolidRect(graphics, x, y - 1, width, 1, ColorPattern.T_WHITE.color);
            };
        } else if (mode == 1) {
            return (graphics, mouseX, mouseY, x, y, width, height, partialTicks) -> {
                DrawerHelper.drawSolidRect(graphics, x, y, width, height, ColorPattern.T_WHITE.color);
            };
        } else if (mode == 2) {
            return (graphics, mouseX, mouseY, x, y, width, height, partialTicks) -> {
                DrawerHelper.drawSolidRect(graphics, x, y + height, width, 1, ColorPattern.T_WHITE.color);
            };
        }
        return IGuiTexture.EMPTY;
    }

    protected void onMouseDown(UIEvent event) {
        if (event.button == 1) {
            fxEditor.openMenu(event.x, event.y, createMenu());
            event.stopPropagation();
        }
    }


    @Nullable
    protected TreeBuilder.Menu createMenu() {
        if (runtime == null) return null;
        var menu = TreeBuilder.Menu.start();
        if (treeList.getSelected().size() <= 1) {
            // add fx objects
            menu.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.new", m -> {
                for (var fx : PhotonRegistries.FX_OBJECTS) {
                    m.leaf(fx.annotation().name(), () -> {
                        var fxObject = fx.value().get();
                        var father = treeList.getSelected().stream().findFirst()
                                .map(FXObjectTreeNode::getKey)
                                .map(ISceneObject::transform)
                                .map(Transform::parent)
                                .orElse(runtime.getRoot().transform());
                        fxEditor.historyView.pushHistory(Component.translatable("photon.add_fx_object"), EditAction.of(
                                () -> {
                                    fxObject.transform().parent(father, false);
                                    addSceneObject(fxObject);
                                    fxEditor.reloadEffect();
                                },
                                () -> {
                                    removeSceneObject(fxObject);
                                    fxEditor.reloadEffect();
                                }
                        ));
                    });
                }
            });
        }
        var selected = treeList.getSelected();
        if (isSelectedNodeValid(selected)) {
            menu.leaf(Icons.REMOVE_FILE, "ldlib.gui.editor.menu.remove", () -> {
                var nodes = treeList.getSelected();
                if (!isSelectedNodeValid(nodes)) return;
                var toRemoved = nodes.stream().map(FXObjectTreeNode::getKey)
                        .sorted(Comparator.comparingInt(a -> a.transform().getSiblingIndex())).toList();
                var other = toRemoved.stream().map(IFXObject::transform).map(Transform::getSiblingIndex).toList();
                fxEditor.historyView.pushHistory(Component.translatable("photon.remove_fx_object"), EditAction.of(
                        () -> {
                            for (var removed : toRemoved) {
                                removeSceneObject(removed);
                            }
                            fxEditor.reloadEffect();
                        },
                        () -> {
                            for (int i = 0; i < toRemoved.size(); i++) {
                                var removed = toRemoved.get(i);
                                addSceneObject(removed);
                                removed.transform().setSiblingIndex(other.get(i));
                            }
                            fxEditor.reloadEffect();
                        }
                ));

            });
            menu.leaf(Icons.COPY, "ldlib.gui.editor.menu.copy", () -> {
                var nodes = treeList.getSelected();
                if (!isSelectedNodeValid(nodes)) return;
                var copied = nodes.stream().map(FXObjectTreeNode::getKey).map(this::copySceneObject).flatMap(Collection::stream).toList();
                fxEditor.historyView.pushHistory(Component.translatable("photon.copy_fx_object"), EditAction.of(
                        () -> {
                            for (var copiedFXObject : copied) {
                                addSceneObject(copiedFXObject);
                            }
                            fxEditor.reloadEffect();
                        },
                        () -> {
                            for (var copiedFXObject : copied) {
                                removeSceneObject(copiedFXObject);
                            }
                            fxEditor.reloadEffect();
                        }
                ));
            });
        }
        return menu;
    }

    private List<IFXObject> copySceneObject(IFXObject toCopied) {
        List<IFXObject> result = new ArrayList<>();
        var copied = toCopied.deepCopy();
        result.add(copied);
        copied.transform()._refreshInternalID();
        for (var child : toCopied.children()) {
            if (child instanceof IFXObject childFXObject) {
                var copiedChildren = copySceneObject(childFXObject);
                copiedChildren.getFirst().transform().parent(copied.transform(), false);
                result.addAll(copiedChildren);
            }
        }
        return result;
    }

    public void addSceneObject(IFXObject fxObject) {
        if (runtime == null) return;
        runtime.fxData.objects().add(fxObject);
        runtime.addSceneObject(fxObject);
    }

    public void removeSceneObject(IFXObject fxObject) {
        if (runtime == null) return;
        if (fxEditor.sceneView.sceneEditor.getTransformGizmo().getTargetTransform() == fxObject.transform()) {
            fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
        }
        if (fxEditor.inspectorView.inspector.getInspectedConfigurable() == fxObject) {
            fxEditor.inspectorView.clear();
        }
        fxEditor.sceneView.fxObjectInfoView.clear();
        runtime.fxData.objects().remove(fxObject);
        runtime.removeSceneObject(fxObject);
    }
}
