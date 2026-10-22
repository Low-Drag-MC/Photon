package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.compiler.StageAffinity;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonScreenSpace;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

@NodeAttribute(name = "photon_terrain_depth", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class TerrainDepthNode extends ShaderNode {
    public static final String SAMPLER = "SamplerTerrainDepth";
    public static final String VALID = "TerrainDepthValid";

    @Override
    public StageAffinity stageAffinity() {
        return StageAffinity.FRAGMENT_ONLY;
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("uv", RenderTypeGraphTypes.VEC2).withoutConfigurator();
        context.addOutputPort("raw", TypeHandles.FLOAT);
        context.addOutputPort("eye", TypeHandles.FLOAT);
        context.addOutputPort("visible", TypeHandles.FLOAT);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        String uv = ctx.temp(GlslType.VEC2, (ctx.isConnected("uv") ? ctx.input("uv") : ctx.screenUv()).code()).code();
        String valid = ctx.useBuiltinUniform(VALID, GlslType.FLOAT);
        String terrain = ctx.sampler(SAMPLER).code();
        String raw = ctx.temp(GlslType.FLOAT, "(" + valid + " > 0.5 ? texture(" + terrain + ", " + uv + ").r : 1.0)").code();
        String scene = ctx.sampleSceneDepthRaw(new ShaderExpr(uv, GlslType.VEC2)).code();
        String projectionUv = PhotonScreenSpace.toViewportUv(ctx, new ShaderExpr(uv, GlslType.VEC2)).code();
        String inverseProjection = ctx.transformField("IProjMat", GlslType.MAT4).code();
        String eye = ctx.temp(GlslType.VEC4, inverseProjection + " * vec4((" + projectionUv
                + ") * 2.0 - 1.0, " + raw + " * 2.0 - 1.0, 1.0)").code();
        ctx.output("raw", new ShaderExpr(raw, GlslType.FLOAT));
        ctx.output("eye", new ShaderExpr("(-" + eye + ".z / max(abs(" + eye + ".w), 1e-8))", GlslType.FLOAT));
        ctx.output("visible", new ShaderExpr("(" + valid + " > 0.5 && " + raw + " < 1.0 && "
                + raw + " <= " + scene + " ? 1.0 : 0.0)", GlslType.FLOAT));
    }

    @Override
    protected String previewOutputPortId() {
        return "visible";
    }
}
