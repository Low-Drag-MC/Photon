package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.compiler.StageAffinity;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.shadergraph.PhotonScreenSpace;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

/**
 * The active viewport ({@code U_ViewPort} = x, y, width, height — bound from the GL viewport each draw):
 * <ul>
 *   <li><b>screenUv</b> — this fragment's 0..1 position in the <b>window</b> (the UV Scene Color/Depth
 *       sample with, because the scene capture is window-sized).</li>
 *   <li><b>viewportUv</b> — this fragment's 0..1 position within the <b>viewport</b>. Differs from
 *       {@code screenUv} whenever the viewport is not the whole window (the editor scene renders into a
 *       sub-viewport), and it is the one NDC is built from: {@code viewportUv * 2 - 1}.</li>
 *   <li><b>origin</b> — the viewport's bottom-left corner in pixels.</li>
 *   <li><b>size</b> — the viewport size in pixels (for pixel-perfect effects / aspect correction).</li>
 * </ul>
 * Fragment-only. In editor previews {@code screenUv} falls back to the mesh uv.
 *
 * <p>In a fullscreen post-processing pass the same three notions still hold, one level up: {@code screenUv}
 * is the pass uv, and the viewport is the rect the frame's camera projects into, rescaled to the pass target
 * (the whole target in-world; the scene's sub-rect when the editor runs a chain over its preview scene).</p>
 */
@NodeAttribute(name = "photon_viewport", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class, FullscreenShaderGraph.class})
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
        // frame -> viewport: the space NDC is measured in (Screen To World does this internally).
        ctx.output("viewportUv", PhotonScreenSpace.toViewportUv(ctx, screenUv));
        String viewport = ctx.useBuiltinUniform(PhotonShaderCompiler.VIEWPORT, GlslType.VEC4);
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
                screenUv = gl_FragCoord.xy / ScreenSize;
                viewportUv = (screenUv * ScreenSize
                    - U_ViewPort.xy) / U_ViewPort.zw;
                origin = U_ViewPort.xy;
                size = U_ViewPort.zw;""";
    }
}
