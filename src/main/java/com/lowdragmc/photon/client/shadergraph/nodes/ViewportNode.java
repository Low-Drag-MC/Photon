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

/**
 * The active viewport ({@code U_ViewPort} = x, y, width, height — the rect of the target being drawn into,
 * published per world frame / per editor-scene render by {@code PhotonEngineUniforms}):
 * <ul>
 *   <li><b>screenUv</b> — this fragment's 0..1 position in the target being drawn into (the UV Scene
 *       Color/Depth sample with, because the capture is sized after that same target).</li>
 *   <li><b>viewportUv</b> — this fragment's 0..1 position within the viewport, the space NDC is built
 *       from: {@code viewportUv * 2 - 1}.</li>
 *   <li><b>origin</b> — the viewport's bottom-left corner in pixels.</li>
 *   <li><b>size</b> — the viewport size in pixels (for pixel-perfect effects / aspect correction).</li>
 * </ul>
 * Fragment-only. In editor previews {@code screenUv} falls back to the mesh uv.
 *
 * <p><b>26.1 note:</b> {@code viewportUv} and {@code origin} exist so a graph authored against the 1.21
 * node still loads, but here they are degenerate by construction, and deliberately so. 1.21 drew the
 * editor's preview scene into a sub-rectangle of the game window, which is what made "window" and
 * "viewport" two different spaces; 26.1 gives that scene its own picture-in-picture texture, so the
 * viewport always covers the whole target — {@code origin} is the zero vector and {@code viewportUv}
 * equals {@code screenUv}. Reconstructing NDC from {@code screenUv} directly is therefore correct here,
 * and the 1.21 remap through {@code U_ViewPort} would <i>introduce</i> a panel-size-dependent stretch.</p>
 */
@NodeAttribute(name = "photon_viewport", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class ViewportNode extends ShaderNode {

    @Override
    public StageAffinity stageAffinity() {
        return StageAffinity.FRAGMENT_ONLY;
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort("screenUv", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("viewportUv", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("origin", RenderTypeGraphTypes.VEC2);
        context.addOutputPort("size", RenderTypeGraphTypes.VEC2);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        ShaderExpr screenUv = ctx.screenUv();
        ctx.output("screenUv", screenUv);
        // the viewport covers the whole target here, so this is the identity — see the class note
        ctx.output("viewportUv", screenUv);
        String viewport = PhotonShaderCompiler.viewport(ctx).code();
        ctx.output("origin", new ShaderExpr(viewport + ".xy", GlslType.VEC2));
        ctx.output("size", new ShaderExpr(viewport + ".zw", GlslType.VEC2));
    }

    @Override
    protected String previewOutputPortId() {
        return "screenUv";
    }

    @Override
    public String glslExample() {
        return """
                screenUv = gl_FragCoord.xy / U_ViewPort.zw;
                viewportUv = screenUv;
                origin = U_ViewPort.xy;
                size = U_ViewPort.zw;""";
    }
}
