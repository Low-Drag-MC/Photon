package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElements;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

/**
 * The particle's per-vertex data, valid on every Photon path (CPU quads / trails / beams and both
 * GPU-instanced particle modes — all reads go through {@code getParticleData()}):
 * <ul>
 *   <li><b>position</b> — camera-relative world position (interpolated in the fragment stage).</li>
 *   <li><b>color</b> — the raw particle color (unlit).</li>
 *   <li><b>litColor</b> — particle color x baked lightmap, sampled the way blocks sample it.</li>
 *   <li><b>light</b> — the lightmap texel at the particle, fetched the way vanilla particles (and Kila) fetch it.</li>
 *   <li><b>uv</b> — the particle texcoords (sprite frame uv for particles, length uv for trails).</li>
 *   <li><b>normal</b> — the (world-space) surface normal.</li>
 * </ul>
 *
 * <p>The tangent frame is not here: KilaGraph's own Tangent / Bitangent nodes report it, fed from
 * {@code ParticleData.Tangent} through {@code PhotonShaderCompiler.tangentBasis}. It is real on every
 * GPU-instanced path — uploaded for model instancing, derived in closed form for billboards, trails and
 * beams — and inert only on the CPU path, whose vertex format has no tangent element.</p>
 */
@NodeAttribute(name = "photon_particle_data", group = "photon_input",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class ParticleDataNode extends ShaderNode {

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort("position", RenderTypeGraphTypes.VEC3);
        context.addOutputPort("color", RenderTypeGraphTypes.VEC4);
        context.addOutputPort("litColor", RenderTypeGraphTypes.VEC4);
        context.addOutputPort("light", RenderTypeGraphTypes.VEC4);
        context.addOutputPort("uv", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("normal", RenderTypeGraphTypes.VEC3);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        ctx.output("position", ctx.meshPosition());
        ctx.output("color", ctx.meshColor());
        ctx.output("litColor", ctx.blockVertexColor());
        ctx.output("light", ctx.varyingInput("photon_light", GlslType.VEC4, () -> {
            if (!ctx.hasAttribute(KGVertexElements.UV2)) {
                ctx.markMissingAttribute(KGVertexElements.UV2.attribName());
                return new ShaderExpr("vec4(1.0)", GlslType.VEC4);
            }
            // the sampler KilaGraph's Light Map node reaches for without declaring it
            ctx.lightmapSampler();
            var lightmap = ctx.sampler("Sampler2");
            var uv = ctx.attribute(KGVertexElements.UV2, GlslType.VEC2, new ShaderExpr("vec2(240.0)", GlslType.VEC2));
            return new ShaderExpr("texelFetch(" + lightmap.code() + ", ivec2(" + uv.code() + ") / 16, 0)", GlslType.VEC4);
        }, new ShaderExpr("vec4(1.0)", GlslType.VEC4)));
        ctx.output("uv", ctx.meshUv());
        ctx.output("normal", ctx.meshNormal());
    }

    @Override
    protected String previewOutputPortId() {
        return "uv";
    }
}
