package com.lowdragmc.photon.client.postfx.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.FragmentOutputs;
import com.lowdragmc.kilagraph.rendertype.compiler.IFragmentOutputBlock;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderBlockNode;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentStageNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.UseWithContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;

/**
 * The single fullscreen output: the pass's color (rgb) + alpha (default 1 — post targets are opaque
 * unless the effect deliberately writes coverage). Replaces the particle pipeline's separate base
 * color / alpha / emission / discard blocks — a post pass writes exactly one {@code fragColor}.
 */
@UseWithContext(FragmentStageNode.class)
@NodeAttribute(name = "photon_fullscreen_output", group = "photon_fullscreen",
        graphTypes = FullscreenShaderGraph.class)
public class FullscreenOutputBlock extends ShaderBlockNode implements IFragmentOutputBlock {

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        super.onDefinePorts(context);
        context.addInputPort("color", RenderTypeGraphTypes.VEC3);
        context.addInputPort("alpha", TypeHandles.FLOAT).withDefaultValue(1f);
    }

    @Override
    public void emitFragment(ShaderCompileContext ctx, FragmentOutputs out) {
        out.baseColor = ctx.input("color");
        out.alpha = ctx.input("alpha");
    }

    @Override
    public String glslExample() {
        return "fragColor = vec4(color, alpha);";
    }
}
