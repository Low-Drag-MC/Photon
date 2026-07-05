package com.lowdragmc.photon.client.shadergraph;

import com.lowdragmc.kilagraph.rendertype.ShaderFunctionGraph;
import com.lowdragmc.kilagraph.rendertype.ShaderFunctionGraphModel;
import com.lowdragmc.kilagraph.rendertype.nodes.input.vertex.VertexAttributeInputNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphNodeRegistry;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.graph.CustomGraphModelImpl;
import com.lowdragmc.photon.Photon;

import java.util.ArrayList;
import java.util.List;

/**
 * Photon's reusable shader-function graph: KilaGraph's {@link ShaderFunctionGraph} (pure shader logic,
 * Blackboard-variable interface, inlined by the compiler) extended with Photon's node palette — so a
 * function authored for Photon shader graphs can use Particle Data, Depth Fade, Viewport, etc. Hosted
 * exclusively by {@link ShaderGraph}s (and other Photon function graphs); the host's
 * {@link PhotonShaderCompiler} compiles the inlined nodes, so Photon semantics (ParticleData inputs,
 * pipeline scene samplers) apply automatically.
 */
public class PhotonShaderFunctionGraph extends ShaderFunctionGraph {
    /** Photon nodes annotated with {@code graphTypes = PhotonShaderFunctionGraph.class}. */
    public static final GraphNodeRegistry NODE_REGISTRY =
            GraphNodeRegistry.create(Photon.id("shader_function"), PhotonShaderFunctionGraph.class);

    /** All KilaGraph function nodes (minus raw vertex-attribute reads, which the Photon pipeline —
     *  whose inputs come from {@code getParticleData()} — cannot serve) plus Photon's own. */
    @Override
    public List<Class<? extends Node>> getSupportNodes() {
        var nodes = new ArrayList<Class<? extends Node>>();
        for (var node : ShaderFunctionGraph.NODE_REGISTRY.getNodeClasses()) {
            if (node != VertexAttributeInputNode.class) nodes.add(node);
        }
        nodes.addAll(NODE_REGISTRY.getNodeClasses());
        return nodes;
    }

    /** Local subgraphs nested inside a Photon function graph are Photon function graphs too. */
    @Override
    protected CustomGraphModelImpl createGraphModel() {
        return new ShaderFunctionGraphModel(this) {
            @Override
            public CustomGraphModelImpl createLocalSubgraphInstance() {
                return createLocalSubgraphInstance(PhotonShaderFunctionGraph.class);
            }
        };
    }
}
