package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor_outdated.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.value.TextWrap;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
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

public class FXHierarchyView extends View {
    public final FXEditor fxEditor;
    public final ScrollerView scrollerView = new ScrollerView();
    public final TreeList<FXObjectTreeNode> treeList = new TreeList<>();

    // runtime
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
                        var name = Component.literal(node.getKey().getName());
                        if (!label.getText().equals(name)) {
                            label.setText(name);
                        }
                    });
                    return container.addChildren(icon, label);
                })
                .setOnSelectedChanged(nodes -> {
                    if (nodes.size() == 1) {
                        var fxObject = nodes.stream().findFirst().get().getKey();
                        fxEditor.inspectorView.inspect(fxObject);
                        fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(fxObject.transform());
                    } else {
                        fxEditor.inspectorView.clear();
                        fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
                    }
                }));
    }

    protected void onMouseDown(UIEvent event) {
        if (event.button == 1) {
            fxEditor.openMenu(event.x, event.y, createMenu());
            event.stopPropagation();
        }
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

    @Nullable
    protected TreeBuilder.Menu createMenu() {
        if (runtime == null) return null;
        var menu = TreeBuilder.Menu.start();
        // add fx objects
        menu.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.new", m -> {
            for (var fx : PhotonRegistries.FX_OBJECTS) {
                m.leaf(fx.annotation().name(), () -> {
                    var fxObject = fx.value().get();
                    addSceneObject(fxObject, false);
                    fxEditor.reloadEffect();
                });
            }
        });
        var selected = treeList.getSelected();
        if (!selected.isEmpty() && (selected.size() > 1 || selected.stream().findFirst().get() != rootNode)) {
            menu.leaf(Icons.REMOVE_FILE, "ldlib.gui.editor.menu.remove", () -> {
                var nodes = treeList.getSelected();
                if (nodes.isEmpty()) return;
                for (var node : nodes) {
                    var fxObject = node.getKey();
                    if (runtime.objects.containsValue(fxObject)) {
                        removeSceneObject(fxObject);
                    }
                }
                fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
                fxEditor.reloadEffect();
            });
        }
        return menu;
    }

    public void addSceneObject(IFXObject fxObject, boolean keepWorldTransform) {
        if (runtime == null) return;
        runtime.addSceneObject(fxObject);
        fxObject.transform().parent(runtime.getRoot().transform(), keepWorldTransform);
    }

    public void removeSceneObject(IFXObject fxObject) {
        if (runtime == null) return;
        runtime.removeSceneObject(fxObject);
    }
}
