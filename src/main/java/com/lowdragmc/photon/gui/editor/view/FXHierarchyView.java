package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.editor.ClipboardManager;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.browser.AssetBrowser;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.CommandEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXData;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXFileActions;
import com.lowdragmc.photon.gui.editor.FXInstanceEditing;
import com.lowdragmc.photon.gui.editor.FXInstanceInspector;
import com.lowdragmc.photon.gui.editor.FXInstanceStyle;
import com.lowdragmc.photon.gui.editor.FXObjectClipboard;
import dev.vfyjxf.taffy.style.FlexDirection;
import lombok.Getter;
import net.minecraft.network.chat.Component;

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
    /** The row the left button went down on: a release that a closing menu lets through to a row is no click. */
    @Nullable
    private FXObjectTreeNode pressedNode;
    @Getter @Nullable
    private FXRuntime runtime;
    @Getter @Nullable
    private FXObjectTreeNode rootNode;

    public FXHierarchyView(FXEditor fxEditor) {
        super("editor.fx_object.hierarchy");
        this.fxEditor = fxEditor;
        setFocusable(true);
        addEventListener(UIEvents.EXECUTE_COMMAND, this::handleCommand);
        this.getLayout().widthPercent(100.0F);
        this.getLayout().heightPercent(100.0F);

        this.scrollerView.layout((layout) -> {
            layout.widthPercent(100.0F);
            layout.heightPercent(100.0F);
        });
        this.addChild(this.scrollerView);
        scrollerView.addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown, true);
        addEventListener(UIEvents.DRAG_PERFORM, e -> {
            if (runtime != null && dropFx(e.dragHandler.getDraggingObject(), runtime.root.transform())) {
                e.stopPropagation();
            }
        });
        scrollerView.addScrollViewChild(treeList
                .setSupportMultipleSelection(true)
                .setDraggable(true)
                .setReorderValidator(req -> {
                    var target = req.target().getKey().transform();
                    // BEFORE/AFTER need a real parent (can't be a sibling of the root)
                    if (req.mode() != TreeList.DropMode.INTO && target.parent() == null) {
                        return false;
                    }
                    var instances = fxEditor.instances;
                    // among what an instance placed only its source decides the order (Unity: "Cannot restructure Prefab instance")
                    if (req.mode() != TreeList.DropMode.INTO
                            && instances.role(req.target().getKey()) == FXInstanceEditing.Role.PLACED) {
                        return false;
                    }
                    for (var dragged : req.dragged()) {
                        var toMoved = dragged.getKey().transform();
                        // can't drop onto self, nor into a node's own subtree
                        if (toMoved == target || target.isInheritedParent(toMoved)) {
                            return false;
                        }
                        if (instances.role(dragged.getKey()) == FXInstanceEditing.Role.PLACED) {
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
                        label.setText(Component.literal(rowText(node.getKey())));
                    }).addEventListener(UIEvents.TICK, e -> {
                        label.getTextStyle().textColor(rowColor(node.getKey()));
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
                    nodeUI.addEventListener(UIEvents.DRAG_PERFORM, e -> {
                        if (dropFx(e.dragHandler.getDraggingObject(), node.getKey().transform())) {
                            e.stopPropagation();
                        }
                    });
                    nodeUI.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                        if (e.button == 0) pressedNode = node;
                    });
                    nodeUI.addEventListener(UIEvents.MOUSE_UP, e -> {
                        if (e.button != 0) {
                            return;
                        }
                        boolean pressedHere = pressedNode == node;
                        pressedNode = null;
                        if (!pressedHere) {
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
                                var group = fxEditor.inspectorView.inspect(fxObject, null, this::onNodeInspectorClosed);
                                FXInstanceInspector.decorate(fxEditor, group, fxObject);
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
                    fxEditor.instances.changed();
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
        // a move into or out of an instance changes what it records added, and where
        fxEditor.instances.changed();
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
                    // an instance is nothing without the effect it places: added below, picking one
                    if (fx == FXInstanceObject.TYPE) continue;
                    m.leaf(fx.icon(), fx.name(), thenFocus(() -> {
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
                    }));
                }
            });
            // opens a dialog, which taking the focus back would close
            menu.leaf(PhotonIcons.FX_FILE, "photon.fx_instance.add", () -> {
                var father = treeList.getSelected().stream().findFirst()
                        .map(FXObjectTreeNode::getKey)
                        .map(ISceneObject::transform)
                        .map(Transform::parent)
                        .orElse(runtime.getRoot().transform());
                FXInstanceStyle.pickFx(fxEditor, fx -> {
                    var created = fxEditor.instances.create(fx, father);
                    if (created != null) select(List.of(created));
                });
            });
        }
        var selected = treeList.getSelected();
        if (selected.size() == 1) {
            appendInstanceMenu(menu, selected.iterator().next().getKey());
        }
        if (isSelectedNodeValid(selected)) {
            menu.leaf(Icons.REMOVE_FILE, "ldlib.gui.editor.menu.remove", thenFocus(() -> {
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

            }));
            menu.leaf(Icons.COPY, "ldlib.gui.editor.menu.copy", thenFocus(this::copySelection));
            menu.leaf(Icons.COPY, "photon.gui.editor.hierarchy.duplicate", thenFocus(this::duplicateSelection));
        }
        if (ClipboardManager.INSTANCE.getClipboardType() == FXObjectClipboard.class) {
            menu.leaf(Icons.PASTE, "ldlib.gui.editor.menu.paste", thenFocus(this::pasteClipboard));
        }
        return menu;
    }

    /** Closing the menu leaves nothing focused, so hand the keyboard back to this view. */
    private Runnable thenFocus(Runnable action) {
        return () -> {
            action.run();
            focus();
        };
    }

    /** Also wired to the scene view. Takes undo/redo too, since commands stop at the focused view. */
    public void handleCommand(UIEvent event) {
        boolean handled;
        if (CommandEvents.COPY.equals(event.command)) {
            handled = copySelection();
        } else if (CommandEvents.PASTE.equals(event.command)) {
            handled = pasteClipboard();
        } else if (CommandEvents.DUPLICATE.equals(event.command)) {
            handled = duplicateSelection();
        } else if (CommandEvents.UNDO.equals(event.command)) {
            fxEditor.historyView.undo();
            handled = true;
        } else if (CommandEvents.REDO.equals(event.command)) {
            fxEditor.historyView.redo();
            handled = true;
        } else {
            handled = false;
        }
        if (handled) event.stopPropagation();
    }

    public boolean copySelection() {
        var roots = selectedRoots();
        if (runtime == null || roots.isEmpty()) return false;
        ClipboardManager.INSTANCE.copyDirect(new FXObjectClipboard(roots, runtime.fxData.timeline()));
        return true;
    }

    /** Pastes next to the selection: under the parent the selected objects share, else under the root. */
    public boolean pasteClipboard() {
        if (runtime == null || ClipboardManager.INSTANCE.getClipboardType() != FXObjectClipboard.class
                || !(ClipboardManager.INSTANCE.paste() instanceof FXObjectClipboard clipboard)) return false;
        var parents = treeList.getSelected().stream().map(node -> node.getKey().transform().parent()).distinct().toList();
        var parent = parents.size() == 1 && parents.getFirst() != null ? parents.getFirst() : runtime.root.transform();
        paste(runtime, clipboard.instantiate(original -> parent.id(), fxEditor.instances.knownAs()), "photon.paste_fx_object");
        return true;
    }

    /** Copies each selected subtree next to its original, without touching the clipboard. */
    public boolean duplicateSelection() {
        var roots = selectedRoots();
        if (runtime == null || roots.isEmpty()) return false;
        var rootId = runtime.root.id();
        var duplicated = new FXObjectClipboard(roots, runtime.fxData.timeline())
                .instantiate(original -> original == null ? rootId : original, fxEditor.instances.knownAs());
        paste(runtime, duplicated, "photon.duplicate_fx_object");
        return true;
    }

    /** Selected objects minus the root and any with a selected ancestor, in tree order. */
    private List<IFXObject> selectedRoots() {
        if (runtime == null) return List.of();
        var selected = new HashSet<IFXObject>();
        treeList.getSelected().forEach(node -> selected.add(node.getKey()));
        var roots = new ArrayList<IFXObject>();
        runtime.root.executeAll(object -> {
            if (object instanceof IFXObject fxObject && fxObject != runtime.root && selected.contains(fxObject)
                    && selected.stream().noneMatch(other -> fxObject.transform().isInheritedParent(other.transform()))) {
                roots.add(fxObject);
            }
        });
        return roots;
    }

    private void paste(FXRuntime runtime, FXData pasted, String historyKey) {
        var objects = pasted.objects();
        var ids = new HashSet<UUID>();
        objects.forEach(object -> ids.add(object.id()));
        var roots = objects.stream().filter(object -> !ids.contains(object.transform()._getInternalParentID())).toList();
        var tracks = runtime.fxData.timeline().tracks();
        fxEditor.instances.reportCycles(pasted);
        fxEditor.historyView.pushHistory(Component.translatable(historyKey), EditAction.of(
                () -> {
                    objects.forEach(this::addSceneObject);
                    objects.forEach(object -> object.transform().rebuildChildOrder());
                    tracks.addAll(pasted.timeline().tracks());
                    fxEditor.instances.changed();
                    fxEditor.timelineView.rebuild();
                    fxEditor.reloadEffect();
                    select(roots);
                },
                () -> {
                    objects.reversed().forEach(this::removeSceneObject);
                    tracks.removeAll(pasted.timeline().tracks());
                    fxEditor.instances.changed();
                    fxEditor.timelineView.rebuild();
                    fxEditor.reloadEffect();
                }));
    }

    /** ⚠️ Selects without inspecting: inspecting pushes history, which an undoable action must not do. */
    private void select(List<IFXObject> objects) {
        var nodes = objects.stream().map(this::nodeOf).filter(Objects::nonNull).toList();
        nodes.forEach(treeList::expandNodeAlongPath);
        fxEditor.inspectorView.clear();
        fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
        treeList.setSelected(nodes, true);
    }

    @Nullable
    private FXObjectTreeNode nodeOf(IFXObject object) {
        var node = rootNode;
        while (node != null && node.getKey() != object) {
            node = node.getChildren().stream().filter(child -> child.getKey() == object
                    || object.transform().isInheritedParent(child.getKey().transform())).findFirst().orElse(null);
        }
        return node;
    }

    /** Adds {@code fxObject} and the subtree still linked to it. */
    public void addSceneObject(IFXObject fxObject) {
        if (runtime == null) return;
        var objects = runtime.fxData.objects();
        fxObject.executeAll(object -> {
            if (object instanceof IFXObject added && !objects.contains(added)) objects.add(added);
        });
        runtime.addSceneObject(fxObject);
        // what an instance records added or removed: Revert All and the overrides dialog read it right away
        fxEditor.instances.changed();
    }

    /** Removes {@code fxObject} and its subtree; the subtree stays linked for undo. */
    public void removeSceneObject(IFXObject fxObject) {
        if (runtime == null) return;
        var sceneEditor = fxEditor.sceneView.sceneEditor;
        fxObject.executeAll(object -> {
            if (sceneEditor.getTransformGizmo().getTargetTransform() == object.transform()) {
                sceneEditor.setTransformGizmoTarget(null);
            }
            if (fxEditor.inspectorView.inspector.getInspectedConfigurable() == object) {
                fxEditor.inspectorView.clear();
            }
            runtime.fxData.objects().remove(object);
        });
        fxEditor.sceneView.fxObjectInfoView.clear();
        runtime.removeSceneObject(fxObject);
        fxEditor.instances.changed();
    }

    // ---- FX instances --------------------------------------------------------------------------------

    private String rowText(IFXObject object) {
        return fxEditor.instances.isAddedTop(object) ? "+ " + object.getName() : object.getName();
    }

    private int rowColor(IFXObject object) {
        if (!object.isVisible()) return ColorPattern.LIGHT_GRAY.color;
        return switch (fxEditor.instances.role(object)) {
            case INSTANCE, PLACED -> FXInstanceStyle.PLACED_COLOR;
            case MISSING -> FXInstanceStyle.MISSING_COLOR;
            default -> ColorPattern.WHITE.color;
        };
    }

    /** Places every {@code .fx} the drag carries under {@code parent}; whether it carried any. */
    private boolean dropFx(@Nullable Object payload, Transform parent) {
        if (runtime == null || !(payload instanceof AssetBrowser.DraggedAssets assets)) return false;
        var any = false;
        IFXObject last = null;
        for (var file : assets.files()) {
            if (!file.getName().endsWith(FX.SUFFIX)) continue;
            any = true;
            var id = FXInstanceEditing.idOf(file);
            if (id == null) {
                FXFileActions.notifyOutsideAssets(fxEditor, file);
                continue;
            }
            var created = fxEditor.instances.create(id, parent);
            if (created != null) last = created;
        }
        if (last != null) select(List.of(last));
        return any;
    }

    private void appendInstanceMenu(TreeBuilder.Menu menu, IFXObject object) {
        var instances = fxEditor.instances;
        switch (instances.role(object)) {
            case INSTANCE, MISSING -> {
                menu.crossLine();
                menu.branch(PhotonIcons.FX_FILE, "photon.fx_instance", m -> FXInstanceInspector.instanceActions(fxEditor,
                        (FXInstanceObject) object, m, this::thenFocus));
            }
            case PLACED -> {
                menu.crossLine();
                if (instances.isOverridden(object)) {
                    menu.leaf(Icons.REPLAY, "photon.fx_instance.revert_object", thenFocus(() -> instances.revertObject(object)));
                } else {
                    menu.disabledLeaf("photon.fx_instance.revert_object");
                }
            }
            default -> {
            }
        }
        if (runtime != null && object != runtime.root) {
            menu.leaf(object.isEnabled() ? "photon.fx_instance.disable" : "photon.fx_instance.enable",
                    thenFocus(() -> instances.setEnabled(object, !object.isEnabled())));
        }
    }
}
