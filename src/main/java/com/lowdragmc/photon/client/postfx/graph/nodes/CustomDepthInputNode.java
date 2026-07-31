package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;

/**
 * The CustomDepth texture (Unreal CustomDepth-style): the mask target's own depth — only the FX
 * passes that enabled {@code writeCustomMask} wrote into it (over the scene depth pre-fill).
 */
@NodeAttribute(name = "photon_custom_depth_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class CustomDepthInputNode extends RenderGraphNode {

    public static final String OUTPUT_PORT = "out";

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
