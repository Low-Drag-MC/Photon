package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.compiler.StageAffinity;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import net.minecraft.network.chat.Component;

/**
 * Soft-particle depth fade: {@code saturate((sceneEyeDepth - fragmentEyeDepth) / distance)} — 0 where the
 * fragment touches opaque geometry, 1 once it is {@code distance} world units in front of it. Multiply it
 * into alpha to remove the hard intersection line where particles clip the ground (Unreal's DepthFade).
 * Fragment-only; reads the pipeline's scene depth capture (translucent materials only). Previews show a
 * constant 1 (no scene behind the editor preview).
 */
@NodeAttribute(name = "photon_depth_fade", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class DepthFadeNode extends ShaderNode {
    @Override
    protected Component getNodeTooltip() {
        return Component.translatable("photon.node.depth_fade.tooltip");
    }

    @Override
    public StageAffinity stageAffinity() {
        return StageAffinity.FRAGMENT_ONLY;
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("distance", TypeHandles.FLOAT).withDefaultValue(1.0f);
        context.addOutputPort("fade", TypeHandles.FLOAT);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        ShaderExpr sceneEye = ctx.sampleSceneDepthEye(ctx.screenUv());
        ShaderExpr fragEye = ctx.fragmentEyeDepth();
        // An unconnected distance reads the port's inline constant editor (default 1.0).
        String distance = ctx.input("distance").code();
        ctx.output("fade", new ShaderExpr(
                "clamp((" + sceneEye.code() + " - " + fragEye.code() + ") / max(" + distance + ", 1e-5), 0.0, 1.0)",
                GlslType.FLOAT));
    }

    @Override
    protected String previewOutputPortId() {
        return "fade";
    }
}
