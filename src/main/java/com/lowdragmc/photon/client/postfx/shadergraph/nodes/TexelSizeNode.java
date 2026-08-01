package com.lowdragmc.photon.client.postfx.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;

/**
 * The size of a bound texture: {@code size} = (width, height) in pixels, {@code texelSize} =
 * (1/width, 1/height) — the primitive every blur/downsample kernel needs. Backed by a per-sampler
 * {@code vec4 <sampler>_TexelSize} uniform (w, h, 1/w, 1/h) the effect executor sets for every
 * render-target input it binds; samplers not bound by the executor (plain asset textures) read 0 —
 * feed those through a constant instead.
 */
@NodeAttribute(name = "photon_texel_size", group = "photon_fullscreen",
        graphTypes = FullscreenShaderGraph.class)
public class TexelSizeNode extends ShaderNode {

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("sampler", RenderTypeGraphTypes.SAMPLER2D).withoutConfigurator();
        context.addOutputPort("size", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("texelSize", RenderTypeGraphTypes.VEC2);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        // A sampler-typed expr is always a bare uniform name (variable sampler / texture node /
        // missing sampler), so the companion uniform is derived by suffix — the executor sets it
        // by the same convention when it binds the sampler.
        ShaderExpr sampler = ctx.isConnected("sampler") ? ctx.input("sampler") : ctx.missingSampler();
        String uniform = ctx.uniform(
                sampler.code() + PhotonFullscreenCompiler.TEXEL_SIZE_SUFFIX, GlslType.VEC4).code();
        ctx.output("size", new ShaderExpr(uniform + ".xy", GlslType.VEC2));
        ctx.output("texelSize", new ShaderExpr(uniform + ".zw", GlslType.VEC2));
    }

    @Override
    public String glslExample() {
        return """
                // vec4(w, h, 1/w, 1/h), set when the
                // executor binds the sampler
                uniform vec4 MyTex_TexelSize;
                size = MyTex_TexelSize.xy;
                texelSize = MyTex_TexelSize.zw;""";
    }
}
