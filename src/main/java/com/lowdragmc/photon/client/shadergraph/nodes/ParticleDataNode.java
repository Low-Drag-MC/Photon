package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import net.minecraft.network.chat.Component;

/**
 * The particle's per-vertex data, valid on every Photon path (CPU quads / trails / beams and both
 * GPU-instanced particle modes — all reads go through {@code getParticleData()}):
 * <ul>
 *   <li><b>position</b> — camera-relative world position (interpolated in the fragment stage).</li>
 *   <li><b>color</b> — the raw particle color (unlit).</li>
 *   <li><b>litColor</b> — particle color x baked lightmap (how vanilla particles are lit).</li>
 *   <li><b>uv</b> — the particle texcoords (sprite frame uv for particles, length uv for trails).</li>
 *   <li><b>normal</b> — the (world-space) surface normal.</li>
 * </ul>
 */
@NodeAttribute(name = "photon_particle_data", group = "photon_input",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class ParticleDataNode extends ShaderNode {
    @Override
    protected Component getNodeTooltip() {
        return Component.translatable("photon.node.particle_data.tooltip");
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort("position", RenderTypeGraphTypes.VEC3);
        context.addOutputPort("color", RenderTypeGraphTypes.VEC4);
        context.addOutputPort("litColor", RenderTypeGraphTypes.VEC4);
        context.addOutputPort("uv", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("normal", RenderTypeGraphTypes.VEC3);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        ctx.output("position", ctx.meshPosition());
        ctx.output("color", ctx.meshColor());
        ctx.output("litColor", ctx.blockVertexColor());
        ctx.output("uv", ctx.meshUv());
        ctx.output("normal", ctx.meshNormal());
    }

    @Override
    protected String previewOutputPortId() {
        return "uv";
    }
}
