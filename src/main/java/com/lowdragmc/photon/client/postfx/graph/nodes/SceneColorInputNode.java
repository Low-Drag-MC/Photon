package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;

/** The effect chain's input color (the scene as rendered so far, HDR) — wire it into pass inputs. */
@NodeAttribute(name = "photon_scene_color_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class SceneColorInputNode extends RenderGraphNode {

    public static final String OUTPUT_PORT = "out";

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }
}
