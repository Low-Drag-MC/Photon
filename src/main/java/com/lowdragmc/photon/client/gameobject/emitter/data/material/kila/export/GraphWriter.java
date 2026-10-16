package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export;

import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.nodes.logic.ExpressionNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.BlockNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.variable.VariableKind;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.SpawnFlags;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.graph.CustomGraphModelImpl;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ContextNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.CustomBlockNodeModelImpl;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.variable.VariableDeclarationModelBase;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.variable.VariableScope;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds a shader graph in code: nodes in rows, one per Kila module, each framed by a placemat, its exposed
 * variables down the left; expression nodes for the formulas no single node has. Rows are laid out once all
 * is built, so a row can grow after the next one has started.
 */
@OnlyIn(Dist.CLIENT)
final class GraphWriter {
    private static final float COLUMN = 240;
    private static final float NODE_GAP = 24;
    private static final float VARIABLE_STEP = 26;
    private static final float ROW_GAP = 140;
    private static final float VARIABLES_X = -2200;
    private static final float NODES_X = -1980;

    private final CustomGraphModelImpl model;
    private final Map<String, Integer> names = new HashMap<>();
    private final Map<NodeModel, List<NodeModel>> sources = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private Row row;

    GraphWriter(ShaderGraph graph) {
        this.model = graph.graphModel;
    }

    /** Starts the row the following nodes and variables go in. */
    void row(String title) {
        row = new Row(title);
        rows.add(row);
    }

    /** Builds something shared in a row of its own, then carries on in the row it was asked for from. */
    <T> T aside(String title, Supplier<T> build) {
        var previous = row;
        row(title);
        try {
            return build.get();
        } finally {
            if (previous != null) row = previous;
        }
    }

    NodeModel node(Class<? extends Node> type) {
        var data = new GraphNodeCreationData(model, new Vector2f(), SpawnFlags.DEFAULT, null);
        var node = (NodeModel) CustomGraphModelImpl.createNodeFromData(data, type);
        node.setPreviewExpanded(false);
        row.nodes.add(node);
        return node;
    }

    NodeModel block(NodeModel stage, Class<? extends BlockNode> type) {
        BlockNode node;
        try {
            node = type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create block " + type.getName(), e);
        }
        var block = new CustomBlockNodeModelImpl();
        block.setGraphModel(model);
        block.setSpawnFlags(SpawnFlags.DEFAULT);
        block.initCustomNode(node);
        block.setContextNodeModel((ContextNodeModel) stage);
        block.onCreateNode();
        ((ContextNodeModel) stage).insertBlock(block, -1);
        return block;
    }

    static PortModel in(NodeModel node, String port) {
        var model = node.getInputsById().get(port);
        if (model == null) throw new IllegalStateException("no input " + port + " on " + node.getName());
        return model;
    }

    static PortModel out(NodeModel node, String port) {
        var model = node.getOutputsById().get(port);
        if (model == null) throw new IllegalStateException("no output " + port + " on " + node.getName());
        return model;
    }

    void wire(NodeModel node, String port, PortModel source) {
        model.createWire(in(node, port), source);
        if (source.getNodeModel() instanceof NodeModel from) sources.computeIfAbsent(node, n -> new ArrayList<>()).add(from);
    }

    /** Places a stage node to the right of the current row. */
    void attach(NodeModel stage) {
        row.stages.add(stage);
    }

    /** An unconnected input's own value. */
    static void constant(NodeModel node, String port, Object value) {
        var constant = node.getInputConstantsById().get(port);
        if (constant == null) throw new IllegalStateException("no constant " + port + " on " + node.getName());
        constant.setValue(value);
    }

    static void option(NodeModel node, String id, Object value) {
        for (var option : node.getNodeOptions()) {
            if (!option.id.equals(id)) continue;
            var constant = node.getInputConstantsById().get(option.portModel.getUniqueName());
            if (constant == null) throw new IllegalStateException("no constant behind option " + id);
            constant.setValue(value);
            node.defineNode();
            return;
        }
        throw new IllegalStateException("no option " + id + " on " + node.getName());
    }

    /** A material parameter: an exposed variable. Names stay unique. */
    PortModel variable(String name, TypeHandle type, Object value) {
        int seen = names.merge(name, 1, Integer::sum);
        var unique = seen == 1 ? name : name + " " + seen;
        var declaration = (VariableDeclarationModelBase) model.createVariable(unique, type, value, VariableKind.LOCAL);
        declaration.setScope(VariableScope.EXPOSED);
        var node = model.createVariableNode(declaration, new Vector2f(), null, null);
        node.setPreviewExpanded(false);
        row.variables.add(node);
        return node.getOutputPort();
    }

    /** A formula as a node: typed inputs wired to their sources, outputs read back by name. */
    NodeModel expression(List<Port> inputs, List<Port> outputs, String body) {
        var spec = new ExpressionNode.ExpressionSpec(
                inputs.stream().map(p -> new ExpressionNode.PortSpec(p.name, p.type.name())).toList(),
                outputs.stream().map(p -> new ExpressionNode.PortSpec(p.name, p.type.name())).toList(), body);
        var node = node(ExpressionNode.class);
        option(node, ExpressionNode.OPTION, ExpressionNode.toJson(spec));
        for (var input : inputs) {
            if (input.source != null) wire(node, input.name, input.source);
        }
        return node;
    }

    /**
     * Places every row under the last: its nodes in columns by how far down its own chain they are, each column
     * stacked by the nodes' drawn heights, its variables down the left, all framed in a placemat named after it.
     */
    void layout() {
        float y = 0;
        for (var r : rows) {
            if (r.nodes.isEmpty() && r.variables.isEmpty()) continue;
            var members = Set.copyOf(r.nodes);
            var depths = new HashMap<NodeModel, Integer>();
            var stacks = new HashMap<Integer, Float>();
            int columns = 1;
            for (var node : r.nodes) {
                int column = depth(node, members, depths);
                float top = stacks.getOrDefault(column, 0f);
                node.setPosition(new Vector2f(NODES_X + column * COLUMN, y + top));
                stacks.put(column, top + height(node) + NODE_GAP);
                columns = Math.max(columns, column + 1);
            }
            for (int i = 0; i < r.variables.size(); i++) {
                r.variables.get(i).setPosition(new Vector2f(VARIABLES_X, y + i * VARIABLE_STEP));
            }
            for (int i = 0; i < r.stages.size(); i++) {
                r.stages.get(i).setPosition(new Vector2f(NODES_X + columns * COLUMN + 80, y + i * 200));
            }
            float height = Math.max(stacks.values().stream().max(Float::compare).orElse(0f), r.variables.size() * VARIABLE_STEP);
            model.createPlacemat(r.title, new Vector2f(VARIABLES_X - 30, y - 50),
                    new Vector2f(NODES_X - VARIABLES_X + columns * COLUMN + 40, height + 70));
            y += height + ROW_GAP;
        }
    }

    private int depth(NodeModel node, Set<NodeModel> members, Map<NodeModel, Integer> depths) {
        var known = depths.get(node);
        if (known != null) return known;
        depths.put(node, 0);
        int depth = 0;
        for (var source : sources.getOrDefault(node, List.of())) {
            if (members.contains(source)) depth = Math.max(depth, depth(source, members, depths) + 1);
        }
        depths.put(node, depth);
        return depth;
    }

    /** How tall the editor draws a node: an expression lists every input, with its type, above its body. */
    private static float height(NodeModel node) {
        int inputs = node.getInputsById().size();
        if (node instanceof ICustomNodeModel custom && custom.getNode() instanceof ExpressionNode) return 45 + 74 * (inputs + 1);
        int options = node instanceof ICustomNodeModel custom ? custom.getNodeOptions().size() : 0;
        return Math.max(42, 30 + 18 * Math.max(inputs, node.getOutputsById().size()) + 26 * options);
    }

    /** An expression port: {@code source} null for an output. */
    record Port(String name, GlslType type, PortModel source) {
        static Port in(String name, GlslType type, PortModel source) {
            return new Port(name, type, source);
        }

        static Port out(String name, GlslType type) {
            return new Port(name, type, null);
        }
    }

    private static final class Row {
        final String title;
        final List<NodeModel> nodes = new ArrayList<>();
        final List<NodeModel> variables = new ArrayList<>();
        final List<NodeModel> stages = new ArrayList<>();

        Row(String title) {
            this.title = title;
        }
    }
}
