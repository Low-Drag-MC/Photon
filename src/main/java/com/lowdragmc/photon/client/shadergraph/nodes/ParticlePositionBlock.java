package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.IVertexPositionBlock;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderBlockNode;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VaryingStageNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.UseWithContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import net.minecraft.network.chat.Component;

/**
 * Photon's fixed vertex-position block: {@code gl_Position = ProjMat * ModelViewMat * (particlePosition +
 * offset)}. The particle transform (billboard rotation / size / model pose) already happened inside
 * {@code getParticleData()}, so the only user control is the optional world-space {@code offset} input —
 * Unreal-style world position offset for vertex animation (wind, pulse, distortion). Unconnected = no
 * offset.
 */
@UseWithContext(VaryingStageNode.class)
@NodeAttribute(name = "photon_particle_position", group = "photon_vertex", graphTypes = ShaderGraph.class)
public class ParticlePositionBlock extends ShaderBlockNode implements IVertexPositionBlock {
    @Override
    protected Component getNodeTooltip() {
        return Component.translatable("photon.node.particle_position.tooltip");
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        super.onDefinePorts(context);
        context.addInputPort("offset", RenderTypeGraphTypes.VEC3).withoutConfigurator();
    }

    @Override
    public ShaderExpr compilePosition(ShaderCompileContext ctx) {
        String proj = ctx.useBuiltinUniform("ProjMat", GlslType.MAT4);
        String mv = ctx.useBuiltinUniform("ModelViewMat", GlslType.MAT4);
        String position = ctx.modelPosition().code();
        if (ctx.isConnected("offset")) {
            position = "(" + position + " + " + ctx.input("offset").code() + ")";
        }
        return new ShaderExpr(proj + " * " + mv + " * vec4(" + position + ", 1.0)", GlslType.VEC4);
    }
}
