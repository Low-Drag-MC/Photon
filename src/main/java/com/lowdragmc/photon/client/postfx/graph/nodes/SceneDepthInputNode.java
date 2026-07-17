package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.kilagraph.rendertype.compiler.NodeDisplayNames;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;
import net.minecraft.network.chat.Component;

/** The scene depth texture (raw hardware depth — sample {@code .r} in the fullscreen graph). */
@NodeAttribute(name = "photon_scene_depth_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class SceneDepthInputNode extends Node {

    public static final String OUTPUT_PORT = "out";

    @Override
    public Component getDisplayName() {
        return NodeDisplayNames.fromAttribute(this);
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
