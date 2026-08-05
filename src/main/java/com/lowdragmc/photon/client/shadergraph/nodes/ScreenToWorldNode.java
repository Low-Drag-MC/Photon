package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.compiler.StageAffinity;
import com.lowdragmc.kilagraph.rendertype.gui.ChoiceConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.shadergraph.PhotonScreenSpace;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

import java.util.List;

/**
 * Reconstructs the world position of the pixel a depth sample came from — the exact inverse of
 * {@link WorldToScreenUVNode}. Feed it a screen {@code uv} (window-relative, the same space Scene Color /
 * Scene Depth sample in) and the <b>raw</b> depth at that uv, and it un-projects through
 * {@code IProjMat}/{@code IViewMat} back to a position.
 *
 * <p>The {@code space} option mirrors World To Screen UV, so the two round-trip exactly:
 * <ul>
 *   <li><b>absolute</b> (default) — world/block coordinates; the camera position ({@code cameraWorldPos()})
 *       is added back for you.</li>
 *   <li><b>camera_relative</b> — relative to the camera, the space {@code ParticleData.Position} lives in
 *       (no camera round-trip, so it keeps its precision far from the world origin).</li>
 * </ul></p>
 *
 * <p>Doing this by hand is a trap worth spelling out: the screen uv is <b>window</b>-relative, but NDC is
 * <b>viewport</b>-relative, and the editor scene renders into a sub-viewport of the window. Skipping the
 * {@code U_ViewPort} remap makes the reconstruction wrong by the window/viewport ratio — the result looks
 * plausibly scaled rather than obviously broken. This node does the remap.</p>
 *
 * <p>{@code rawDepth} is an explicit input on purpose: in the particle graph wire it from Scene Depth
 * (<b>Raw</b> sampling, same uv), and in a fullscreen pass from the pass's depth input sampler — a
 * fullscreen graph never samples the scene implicitly. {@code rawDepth == 1} (sky / nothing drawn) maps to
 * the far plane, where the reconstruction is numerically wild: branch on it if that matters.</p>
 */
@NodeAttribute(name = "photon_screen_to_world", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class, FullscreenShaderGraph.class})
public class ScreenToWorldNode extends ShaderNode {
    private static final List<String> SPACES = List.of("absolute", "camera_relative");

    @Override
    public StageAffinity stageAffinity() {
        return StageAffinity.FRAGMENT_ONLY;
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption("space", TypeHandles.STRING).withDefaultValue("absolute")
                .withTooltips(Tooltips.of("kg.node.photon_screen_to_world.option.space.tooltip"))
                .withConfigurable((vc, t) -> ChoiceConfigurator.build(vc, SPACES, ScreenToWorldNode::label)).build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("uv", RenderTypeGraphTypes.VEC2).withoutConfigurator();
        context.addInputPort("rawDepth", TypeHandles.FLOAT).withDefaultValue(1.0f);
        context.addOutputPort("position", RenderTypeGraphTypes.VEC3);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        // An unconnected uv is this fragment's own screen uv — the common "what is behind me" case.
        ShaderExpr uv = ctx.isConnected("uv") ? ctx.input("uv") : ctx.screenUv();
        String rawDepth = ctx.input("rawDepth").code();
        String iProj = ctx.transformField("IProjMat", GlslType.MAT4).code();
        String iView = ctx.transformField("IViewMat", GlslType.MAT4).code();

        // screen uv -> viewport uv -> NDC. In-world (and in a fullscreen pass, where the executor binds the
        // pass target as the viewport) the remap is the identity; in the editor's sub-viewport scene it is not.
        String viewportUv = ctx.temp(GlslType.VEC2, PhotonScreenSpace.toViewportUv(ctx, uv).code()).code();
        String clip = ctx.temp(GlslType.VEC4, "vec4(" + viewportUv + " * 2.0 - 1.0, "
                + rawDepth + " * 2.0 - 1.0, 1.0)").code();
        // The inverse projection yields homogeneous view coords: the w must be divided out (perspective).
        String viewH = ctx.temp(GlslType.VEC4, iProj + " * " + clip).code();
        String cameraRelative = ctx.temp(GlslType.VEC3,
                "(" + iView + " * vec4(" + viewH + ".xyz / " + viewH + ".w, 1.0)).xyz").code();

        boolean absolute = ctx.option("space", String.class, "absolute").equals("absolute");
        ctx.output("position", new ShaderExpr(absolute
                ? "(" + cameraRelative + " + " + ctx.cameraWorldPos().code() + ")"
                : cameraRelative, GlslType.VEC3));
    }

    @Override
    protected String previewOutputPortId() {
        return "position";
    }

    @Override
    public List<String> optionChoices(String optionId) {
        return "space".equals(optionId) ? SPACES : List.of();
    }

    @Override
    public String glslExample() {
        return """
                // window uv -> viewport uv -> NDC
                vec2 vp = (uv * ScreenSize - U_ViewPort.xy)
                        / U_ViewPort.zw;
                vec4 clip = vec4(vp * 2.0 - 1.0,
                                 rawDepth * 2.0 - 1.0, 1.0);
                vec4 h = IProjMat * clip;
                vec3 p = (IViewMat
                       * vec4(h.xyz / h.w, 1.0)).xyz;
                // absolute space
                position = p + cameraWorldPos;""";
    }

    private static String label(String space) {
        return switch (space) {
            case "absolute" -> "Absolute (World)";
            case "camera_relative" -> "Camera-Relative";
            default -> space;
        };
    }
}
