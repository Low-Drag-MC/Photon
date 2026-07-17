package com.lowdragmc.photon.client.postfx.graph.gui;

import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphEditorView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResourceProviderContainer;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.NodeCommands;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.graph.CustomGraphModelImpl;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.photon.client.postfx.graph.PassSource;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.nodes.PassNode;
import com.lowdragmc.photon.client.postfx.shadergraph.gui.FullscreenGraphView;
import com.lowdragmc.photon.gui.editor.resource.FullscreenGraphLibrary;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import org.joml.Vector2f;

/**
 * The effect render graph editor view. On top of the stock {@link GraphView} (canvas, item
 * library, blackboard = the effect's parameter schema, inspector):
 * <ul>
 *   <li>dropping a fullscreen shader graph from the resource panel creates a ready-wired
 *       {@link PassNode} for it;</li>
 *   <li>double-clicking a pass node dives into its fullscreen graph (breadcrumb navigation, like
 *       subgraphs) — saving inside the dive writes back to the fullscreen library and refreshes
 *       every pass node using it.</li>
 * </ul>
 */
public class RenderGraphView extends GraphView {

    public RenderGraphView() {
        graphView.addEventListener(UIEvents.DOUBLE_CLICK, this::onCanvasDoubleClick);

        // live effect preview over a clean world-frame capture (mirrors the shader preview dock)
        var previewTool = new PostFXPreviewTool(this);
        var previewPanel = new com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPanel(this, previewTool);
        getPanelLayer().addChild(previewPanel);
        dockManager.register(previewPanel, com.lowdragmc.lowdraglib2.nodegraphtookit.gui.DockSlot.BOTTOM_RIGHT);
        java.util.Optional.ofNullable(dockManager.getCornerPanel(
                        com.lowdragmc.lowdraglib2.nodegraphtookit.gui.DockSlot.BOTTOM_RIGHT))
                .ifPresent(panel -> panel.selectTool(previewTool));
    }

    /** Double-click on a pass node = dive into its fullscreen graph. */
    private void onCanvasDoubleClick(UIEvent event) {
        var target = event.target;
        if (target == null) return;
        var nodeElement = target instanceof NodeElement element ? element
                : target.getFirstAncestorOfType(NodeElement.class);
        if (nodeElement == null
                || !(nodeElement.getModel() instanceof ICustomNodeModel custom)
                || !(custom.getNode() instanceof PassNode pass)) {
            return;
        }
        var path = pass.graphPath();
        if (path == null) return;
        var editorView = getFirstAncestorOfType(GraphEditorView.class);
        if (editorView == null) return;
        var tag = FullscreenGraphLibrary.load(path);
        if (tag == null) return;
        var inner = FullscreenShaderGraphResource.INSTANCE
                .deserializeGraph(tag, FullscreenGraphLibrary.EDIT_RESOLVER);
        if (!FullscreenGraphLibrary.isEditable(path)) {
            // builtins/pack graphs are view-only — say so up front, and again if a save is attempted
            showReadOnlyNotification();
        }
        editorView.enterExternalGraph(inner, pass.getDisplayName(), path,
                FullscreenGraphView::new, (savedPath, savedTag) -> {
                    if (!FullscreenGraphLibrary.save(savedPath, savedTag)) {
                        showReadOnlyNotification();
                    }
                });
        event.stopPropagation();
    }

    private void showReadOnlyNotification() {
        var mui = getModularUI();
        if (mui != null) {
            com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog.showNotification(
                    "photon.render_graph.dive", "photon.render_graph.dive.read_only", null).show(mui);
        }
    }

    /** Dropping a fullscreen graph resource = "add a Pass running that graph" (undoable). */
    @Override
    protected void onGraphViewDragPerform(UIEvent event) {
        if (event.dragHandler.getDraggingObject() instanceof GraphResourceProviderContainer.DraggingGraph dragging
                && dragging.graphResource() instanceof FullscreenShaderGraphResource
                && getGraph() instanceof RenderGraph
                && graphView.isMouseOverContent(event.x, event.y)) {
            var localPosition = snapPosition(getContentViewContainer()
                    .worldToLocalLayoutOffset(new Vector2f(event.x, event.y)));
            var pathWithType = dragging.path().getPathWithType();
            var item = new NodeModelLibraryItem("photon_pass", data -> {
                var model = CustomGraphModelImpl.createNodeFromData(data, PassNode.class);
                if (model instanceof NodeModel nodeModel) {
                    RenderGraph.setNodeOption(nodeModel, PassNode.OPTION_SOURCE, PassSource.ofGraph(pathWithType));
                }
                return model;
            });
            dispatchCommand(new NodeCommands.CreateNodeCommand().onGraph(item, localPosition, null));
            return;
        }
        super.onGraphViewDragPerform(event);
    }
}
