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
 * The CustomMask texture (Unreal CustomStencil-style): R = mask id / 255 of the FX passes that
 * enabled {@code writeCustomMask}, 0 elsewhere. Empty (black) on frames where nothing is flagged.
 */
@NodeAttribute(name = "photon_custom_mask_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class CustomMaskInputNode extends Node {

    public static final String OUTPUT_PORT = "out";

    @Override
    public Component getDisplayName() {
        return NodeDisplayNames.fromAttribute(this);
    }

    @Override
    public void setImplementation(NodeModel nodeModel) {
        super.setImplementation(nodeModel);
        NodeTooltipHelper.apply(nodeModel, Component.translatable("photon.node.custom_mask_input.tooltip"));
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
