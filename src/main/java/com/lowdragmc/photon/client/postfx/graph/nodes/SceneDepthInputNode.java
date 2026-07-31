package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;

/** The scene depth texture (raw hardware depth — sample {@code .r} in the fullscreen graph). */
@NodeAttribute(name = "photon_scene_depth_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class SceneDepthInputNode extends RenderGraphNode {

    public static final String OUTPUT_PORT = "out";

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
