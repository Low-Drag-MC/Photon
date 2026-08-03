package com.lowdragmc.photon.client.postfx.graph;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphLogger;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphNodeRegistry;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.SubgraphRegistry;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.GraphCommands;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.IGraphCommand;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.Capabilities;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.SpawnFlags;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.graph.CustomGraphModelImpl;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.graph.nodes.OutputNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.PassNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.SceneColorInputNode;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The effect render graph: ONE asset = ONE post-processing effect. Nodes are fullscreen passes
 * ({@code PassNode}, each referencing a fullscreen shader graph) connected by opaque TEXTURE wires;
 * scene color/depth enter through input nodes; the fixed {@link OutputNode} (which also carries the
 * effect's priority / autoBlend settings) receives the result. Blackboard variables are the effect's
 * blendable parameter schema. Compiled by {@code RenderGraphCompiler} into a flat
 * {@code CompiledEffect} (topo-ordered passes + resource lifetimes for pool aliasing).
 */
public class RenderGraph extends Graph {

    public static final GraphNodeRegistry NODE_REGISTRY =
            GraphNodeRegistry.create(Photon.id("render_graph"), RenderGraph.class);

    /** The effect's blendable parameter types (float/vector/color lerp by weight; bool/int don't). */
    private static final List<TypeHandle> PARAMETER_TYPES = List.of(
            TypeHandles.BOOL, TypeHandles.INT, TypeHandles.FLOAT,
            RenderTypeGraphTypes.VEC2, RenderTypeGraphTypes.VEC3, RenderTypeGraphTypes.VEC4,
            TypeHandles.COLOR);

    private static final List<TypeHandle> ALL_TYPES = List.of(
            RenderGraphTypes.TEXTURE,
            TypeHandles.BOOL, TypeHandles.INT, TypeHandles.FLOAT,
            RenderTypeGraphTypes.VEC2, RenderTypeGraphTypes.VEC3, RenderTypeGraphTypes.VEC4,
            TypeHandles.COLOR);

    private static final List<TypeHandle> CONSTANT_TYPES = List.of(
            TypeHandles.BOOL, TypeHandles.INT, TypeHandles.FLOAT);

    /**
     * Every live RenderGraph (weakly held): when a fullscreen graph is saved anywhere in the
     * editor, pass nodes referencing it re-define so their ports track the new blackboard.
     * The runtime side needs nothing — resource tag identity drives its staleness.
     */
    private static final Set<RenderGraph> LIVE_GRAPHS = Collections.newSetFromMap(new WeakHashMap<>());

    static {
        SubgraphRegistry.INSTANCE.registerListener(savedPath -> {
            if (savedPath == null
                    || FullscreenShaderGraphResource.INSTANCE.getResourceInstance().getResource(savedPath) == null) {
                return; // not a fullscreen graph save
            }
            for (var graph : List.copyOf(LIVE_GRAPHS)) {
                graph.refreshPassNodesReferencing(savedPath);
            }
        });
    }

    @Nullable
    private NodeModel outputNodeModel;
    /** Bumped on every model change; live consumers can gate recompiles on it. */
    private volatile long changeVersion;

    public RenderGraph() {
        this(true);
    }

    /** Pass {@code false} when about to deserialize into this graph (mirror RenderTypeGraph);
     *  call {@link #restoreAfterDeserialize()} after the load. */
    public RenderGraph(boolean initialize) {
        LIVE_GRAPHS.add(this);
        if (initialize) {
            ensureOutputNode();
        }
    }

    /** Re-define every pass node running {@code savedPath} so its ports mirror the fresh save. */
    private void refreshPassNodesReferencing(IResourcePath savedPath) {
        var entry = FullscreenGraphRuntime.get(savedPath);
        if (entry != null && !entry.isValid()) {
            Photon.LOGGER.warn("[postfx] fullscreen graph '{}' was saved but no longer compiles: {} "
                            + "(pass nodes keep their previous ports until it compiles again)",
                    savedPath.getResourceName(), entry.getErrorMessage());
        }
        for (var model : graphModel.getNodeModels()) {
            if (model instanceof NodeModel nodeModel && model instanceof ICustomNodeModel custom
                    && custom.getNode() instanceof PassNode pass
                    && savedPath.equals(pass.graphPath())) {
                var before = List.copyOf(nodeModel.getInputsById().keySet());
                nodeModel.defineNode();
                var after = List.copyOf(nodeModel.getInputsById().keySet());
                if (!before.equals(after)) {
                    Photon.LOGGER.info("[postfx] pass '{}' ports refreshed: {} -> {}",
                            savedPath.getResourceName(), before, after);
                }
            }
        }
        changeVersion++; // live consumers (preview) recompile
    }

    public void restoreAfterDeserialize() {
        ensureOutputNode();
    }

    private void ensureOutputNode() {
        outputNodeModel = findOutputNode();
        boolean fresh = graphModel.getNodeModels().isEmpty();
        if (outputNodeModel == null) {
            outputNodeModel = addNode(OutputNode.class, 420, -80);
            if (fresh) {
                // starter canvas: scene color wired straight through — a valid no-op effect the
                // author splices Passes into
                var sceneColor = addNode(SceneColorInputNode.class, -160, -80);
                graphModel.createWire(
                        outputNodeModel.getInputsById().get(OutputNode.COLOR_PORT),
                        sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
            }
        }
        outputNodeModel.setCapability(Capabilities.DELETABLE, false);
    }

    @Nullable
    private NodeModel findOutputNode() {
        for (var model : graphModel.getNodeModels()) {
            if (model instanceof NodeModel nm && model instanceof ICustomNodeModel custom
                    && custom.getNode() instanceof OutputNode) {
                return nm;
            }
        }
        return null;
    }

    /**
     * The fixed output node, revalidated against the live model registry: the editor opens graphs
     * via {@code createGraph()} + raw {@code graphModel.deserializeNBT} (never the resource's
     * deserialize wrapper), which rebuilds {@code nodeModels} and leaves a cached reference
     * pointing at the pre-load instance.
     */
    @Nullable
    public NodeModel getOutputNodeModel() {
        if (outputNodeModel == null || graphModel.getModel(outputNodeModel.getUid()) != outputNodeModel) {
            outputNodeModel = findOutputNode();
            if (outputNodeModel != null) {
                outputNodeModel.setCapability(Capabilities.DELETABLE, false);
            }
        }
        return outputNodeModel;
    }

    /** Create a node on the canvas (also used by builtin-sample builders and tests). */
    public NodeModel addNode(Class<? extends Node> nodeClass, float x, float y) {
        var data = new GraphNodeCreationData(graphModel, new Vector2f(x, y), SpawnFlags.DEFAULT, null);
        return (NodeModel) CustomGraphModelImpl.createNodeFromData(data, nodeClass);
    }

    /** Set a node option's value (mirrors the editor's option-constant write) and re-define ports. */
    public static void setNodeOption(NodeModel node, String optionId, Object value) {
        for (var option : node.getNodeOptions()) {
            if (option.id.equals(optionId)) {
                var constant = node.getInputConstantsById().get(option.portModel.getUniqueName());
                if (constant != null) constant.setValue(value);
                node.defineNode();
                return;
            }
        }
    }

    @Override
    public List<Class<? extends Node>> getSupportNodes() {
        return NODE_REGISTRY.getNodeClasses();
    }

    /** The fixed output node isn't offered in the item library. */
    @Override
    public List<Class<? extends Node>> getLibrarySupportNodes() {
        return getSupportNodes().stream().filter(node -> node != OutputNode.class).toList();
    }

    @Override
    public List<TypeHandle> getSupportTypes() {
        return ALL_TYPES;
    }

    @Override
    public List<TypeHandle> getVariableSupportTypes() {
        return PARAMETER_TYPES;
    }

    @Override
    public List<TypeHandle> getLibrarySupportTypes() {
        return CONSTANT_TYPES;
    }

    @Override
    public boolean canExecuteCommand(IGraphCommand command) {
        if (command instanceof GraphCommands.DeleteElementsCommand deleteCommand) {
            var output = getOutputNodeModel();
            return deleteCommand.elementsToDelete.stream().noneMatch(element -> element == output);
        }
        return super.canExecuteCommand(command);
    }

    /** Surface compile problems live in the editor (a CPU-only compile is cheap). */
    @Override
    public void onGraphChanged(GraphLogger logger) {
        super.onGraphChanged(logger);
        changeVersion++;
        try {
            RenderGraphCompiler.compile(null, this);
        } catch (RenderGraphCompiler.CompileError error) {
            logger.warning(Component.literal(error.getMessage()));
        } catch (RuntimeException ignored) {
            // malformed model mid-edit; the runtime path reports real failures
        }
    }

    public long getChangeVersion() {
        return changeVersion;
    }
}
