package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;

/**
 * The effect's blended request weight (0..1) as a wireable FLOAT — the render-graph-scoped way to
 * react to fades physically (e.g. scale a distortion amount) instead of relying on the auto final
 * mix. Wire it into any pass float parameter; passes that don't wire it never see it.
 */
@NodeAttribute(name = "photon_effect_weight", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class EffectWeightNode extends RenderGraphNode {

    public static final String OUTPUT_PORT = "out";

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, TypeHandles.FLOAT);
    }
}
