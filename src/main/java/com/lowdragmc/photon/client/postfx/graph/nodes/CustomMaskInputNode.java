package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;

/**
 * The CustomMask texture (Unreal CustomStencil-style): R = mask id / 255 of the FX passes that
 * enabled {@code writeCustomMask}, 0 elsewhere. Empty (black) on frames where nothing is flagged.
 */
@NodeAttribute(name = "photon_custom_mask_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class CustomMaskInputNode extends RenderGraphNode {

    public static final String OUTPUT_PORT = "out";

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
