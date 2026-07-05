package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.compiler.StageAffinity;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import net.minecraft.network.chat.Component;

/**
 * The active viewport ({@code U_ViewPort} = x, y, width, height — bound from the GL viewport each draw):
 * <ul>
 *   <li><b>screenUv</b> — this fragment's 0..1 position within the viewport (the UV Scene Color/Depth use).</li>
 *   <li><b>size</b> — the viewport size in pixels (for pixel-perfect effects / aspect correction).</li>
 * </ul>
 * Fragment-only. In editor previews {@code screenUv} falls back to the mesh uv.
 */
@NodeAttribute(name = "photon_viewport", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class ViewportNode extends ShaderNode {
    @Override
    protected Component getNodeTooltip() {
        return Component.translatable("photon.node.viewport.tooltip");
    }

    @Override
    public StageAffinity stageAffinity() {
        return StageAffinity.FRAGMENT_ONLY;
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort("screenUv", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("size", RenderTypeGraphTypes.VEC2);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        ctx.output("screenUv", ctx.screenUv());
        String viewport = ctx.useBuiltinUniform(PhotonShaderCompiler.VIEWPORT, GlslType.VEC4);
        ctx.output("size", new ShaderExpr(viewport + ".zw", GlslType.VEC2));
    }

    @Override
    protected String previewOutputPortId() {
        return "screenUv";
    }
}
