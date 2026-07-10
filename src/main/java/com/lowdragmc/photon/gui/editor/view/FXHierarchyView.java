package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import lombok.Getter;
import net.minecraft.network.chat.Component;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class FXHierarchyView extends View {
    /** Drag payload handed to external drop targets (force-field refs, control-track lanes). */
    public record DraggingNode(FXObjectTreeNode draggedNode) {}
    public final FXEditor fxEditor;
    public final ScrollerView scrollerView = new ScrollerView();
    public final TreeList<FXObjectTreeNode> treeList = new TreeList<>();

    // runtime
    // guards the inspector<->selection binding so our own selection-driven inspector swaps
    // don't fire the "deselect on inspect loss" reaction.
    private boolean updatingInspector = false;
    @Getter @Nullable
    private FXRuntime runtime;
    @Getter @Nullable
    private FXObjectTreeNode rootNode;

    public FXHierarchyView(FXEditor fxEditor) {
        super("editor.fx_object.hierarchy");
        this.fxEditor = fxEditor;
        this.getLayout().widthPercent(100.0F);
        this.getLayout().heightPercent(100.0F);

        this.scrollerView.layout((layout) -> {
            layout.widthPercent(100.0F);
            layout.heightPercent(100.0F);
        });
        this.addChild(this.scrollerView);
        scrollerView.addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown, true);
        scrollerView.addScrollViewChild(treeList
                .setSupportMultipleSelection(true)
                .setDraggable(true)
                .setReorderValidator(req -> {
                    var target = req.target().getKey().transform();
                    // BEFORE/AFTER need a real parent (can't be a sibling of the root)
                    if (req.mode() != TreeList.DropMode.INTO && target.parent() == null) {
                        return false;
                    }
                    for (var dragged : req.dragged()) {
                        var toMoved = dragged.getKey().transform();
                        // can't drop onto self, nor into a node's own subtree
                        if (toMoved == target || target.isInheritedParent(toMoved)) {
                            return false;
                        }
                    }
                    return true;
                })
                .setOnReorder(this::reparent)
                .setDragPayloadFactory(DraggingNode::new)
                .setNodeUISupplier((node) -> {
                    UIElement container = (new UIElement()).layout((layout) -> {
                        layout.flexDirection(FlexDirection.ROW);
                        layout.gapAll(2.0F);
                        layout.height(10.0F);
                        layout.flex(1.0F);
                    }).addChildren();
                    UIElement icon = (new UIElement()).layout((layout) -> {
                        layout.setAspectRatio(1.0F);
                        layout.heightPercent(100.0F);
                    }).style((style) -> style.backgroundTexture(node.getKey().getIcon()));
                    TextElement label = new TextElement();
                    label.textStyle((style) -> {
                        style.textWrap(TextWrap.HOVER_ROLL).textAlignVertical(Vertical.CENTER);
                    }).setText(node.getKey().getName(), false).layout((layout) -> {
                        layout.heightPercent(100.0F);
                        layout.flex(1.0F);
                    }).setOverflowVisible(false).addEventListener(UIEvents.TICK, e -> {
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
                        layout.width(7);
                        layout.height(7);
                    });
                    nodeUI.addChildAt(eyeButton, 0);
                    nodeUI.addEventListener(UIEvents.MOUSE_UP, e -> {
                        if (e.button != 0) {
                            return;
                        }
                        var fxObject = node.getKey();
                        // Drive the single-object main inspector here (not from onSelectedChanged,
                        // whose selection updates only on the following CLICK). The onClose callback
                        // deselects the row when the inspector later switches away or is cleared.
                        updatingInspector = true;
                        try {
                            if (e.isCtrlDown() || e.isShiftDown()) {
                                // building a multi-selection: the single-object inspector doesn't apply
                                fxEditor.inspectorView.clear();
                            } else if (fxEditor.inspectorView.inspector.getInspectedConfigurable() != fxObject) {
                                fxEditor.inspectorView.inspect(fxObject, null, this::onNodeInspectorClosed);
                                fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(fxObject.transform(), () ->
                                        fxEditor.historyView.recordSerializableObject(
                                                Component.translatable("photon.transform"), fxObject.transform(), fxObject));
                            }
                        } finally {
                            updatingInspector = false;
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

    /**
     * Fired when the main inspector switches away from / clears an fx object we inspected. Guarded so
     * our own selection-driven inspector swaps don't spuriously deselect (see {@link #updatingInspector}).
     */
    private void onNodeInspectorClosed() {
        if (updatingInspector) {
            return;
        }
        treeList.setSelected(Collections.emptySet(), true);
        fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
    }

    /**
     * Performs a drag-reorder requested by the {@link TreeList}: moves all dragged fx objects
     * before/into/after the target as one undoable step. Nodes that are descendants of another
     * dragged node are skipped (moving a parent carries its children).
     */
    private void reparent(TreeList.ReorderRequest<FXObjectTreeNode> req) {
        var target = req.target().getKey().transform();
        var dragged = new ArrayList<>(req.dragged());
        var moving = dragged.stream()
                .filter(n -> dragged.stream().noneMatch(o -> o != n
                        && n.getKey().transform().isInheritedParent(o.getKey().transform())))
                .sorted(Comparator.comparingInt(n -> n.getKey().transform().getSiblingIndex()))
                .map(FXObjectTreeNode::getKey)
                .toList();
        if (moving.isEmpty()) {
            return;
        }
        Transform newParent = req.mode() == TreeList.DropMode.INTO ? target : target.parent();
        if (newParent == null) {
            return;
        }
        // Snapshot every affected parent's ordered children so undo restores the exact arrangement.
        var affected = new LinkedHashSet<Transform>();
        affected.add(newParent);
        for (var k : moving) {
            var p = k.transform().parent();
            if (p != null) {
                affected.add(p);
            }
        }
        var snapshot = new LinkedHashMap<Transform, List<Transform>>();
        for (var p : affected) {
            snapshot.put(p, new ArrayList<>(p.children()));
        }
        fxEditor.historyView.pushHistory(Component.translatable("photon.move_fx_object"), EditAction.of(
                () -> applyReparent(moving, target, newParent, req.mode()),
                () -> {
                    snapshot.forEach((p, order) -> {
                        for (var child : order) {
                            child.parent(p, true);
                        }
                    });
                    snapshot.forEach((p, order) -> {
                        for (int i = 0; i < order.size(); i++) {
                            order.get(i).setSiblingIndex(i);
                        }
                    });
                }
        ));
    }

    private void applyReparent(List<IFXObject> moving, Transform target, Transform newParent, TreeList.DropMode mode) {
        for (var k : moving) {
            k.transform().parent(newParent, true);
        }
        var movingT = moving.stream().map(IFXObject::transform).toList();
        var rest = new ArrayList<>(newParent.children());
        rest.removeAll(movingT);
        // Build the desired child order, then a left-to-right setSiblingIndex pass realizes it exactly.
        List<Transform> desired = new ArrayList<>();
        if (mode == TreeList.DropMode.INTO) {
            desired.addAll(rest);
            desired.addAll(movingT);
        } else {
            int ti = rest.indexOf(target);
            int insertPos = (ti < 0) ? rest.size() : (mode == TreeList.DropMode.BEFORE ? ti : ti + 1);
            insertPos = Math.max(0, Math.min(insertPos, rest.size()));
            desired.addAll(rest.subList(0, insertPos));
            desired.addAll(movingT);
            desired.addAll(rest.subList(insertPos, rest.size()));
        }
        for (int i = 0; i < desired.size(); i++) {
            desired.get(i).setSiblingIndex(i);
        }
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
                    m.leaf(fx.icon(), fx.name(), () -> {
                        var fxObject = fx.create();
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
