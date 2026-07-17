package com.lowdragmc.photon.client.postfx.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.IVertexPositionBlock;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderBlockNode;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VaryingStageNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.UseWithContext;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import net.minecraft.network.chat.Component;

/**
 * The fixed fullscreen vertex transform: the executor draws a ±1 {@code POSITION} quad, so the
 * clip-space position is the attribute itself — no matrices involved. Created by the default graph;
 * deletion is blocked ({@code FullscreenShaderGraph.canExecuteCommand}) because without it the compiler
 * falls back to the {@code ProjMat · ModelViewMat} path, which has no matrices bound in a fullscreen
 * dispatch.
 */
@UseWithContext(VaryingStageNode.class)
@NodeAttribute(name = "photon_fullscreen_position", group = "photon_fullscreen",
        graphTypes = FullscreenShaderGraph.class)
public class FullscreenPositionBlock extends ShaderBlockNode implements IVertexPositionBlock {

    @Override
    protected Component getNodeTooltip() {
        return Component.translatable("photon.node.fullscreen_position.tooltip");
    }

    @Override
    public ShaderExpr compilePosition(ShaderCompileContext ctx) {
        return new ShaderExpr("vec4(Position.xy, 0.0, 1.0)", GlslType.VEC4);
    }
}
