package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.kilagraph.graph.util.NodeTooltipHelper;
import com.lowdragmc.kilagraph.rendertype.compiler.NodeDisplayNames;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;
import net.minecraft.network.chat.Component;

/**
 * The CustomDepth texture (Unreal CustomDepth-style): the mask target's own depth — only the FX
 * passes that enabled {@code writeCustomMask} wrote into it (over the scene depth pre-fill).
 */
@NodeAttribute(name = "photon_custom_depth_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class CustomDepthInputNode extends Node {

    public static final String OUTPUT_PORT = "out";

    @Override
    public Component getDisplayName() {
        return NodeDisplayNames.fromAttribute(this);
    }

    @Override
    public void setImplementation(NodeModel nodeModel) {
        super.setImplementation(nodeModel);
        NodeTooltipHelper.apply(nodeModel, Component.translatable("photon.node.custom_depth_input.tooltip"));
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
