package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.gui.ChoiceConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

import java.util.List;

/**
 * Projects a {@code Position} to a screen-space UV. Output is <b>window-relative</b> (the same space as
 * {@code screenUv()}) — it takes the projected point to NDC and reads it as a 0..1 position in the target
 * being drawn into, which is exactly what the scene capture is sized after, so it is correct in-world and
 * in the editor's own preview scene alike.
 * Pure uniform math (no {@code gl_FragCoord}), so it is usable in <b>both</b> the vertex and fragment
 * stages.
 *
 * <p>The {@code space} option says what {@code Position} is measured in:
 * <ul>
 *   <li><b>absolute</b> (default) — world/block coordinates; the camera position ({@code cameraWorldPos()})
 *       is subtracted for you (1.21 renders camera-relative).</li>
 *   <li><b>camera_relative</b> — already relative to the camera (e.g. {@code ParticleData.Position}),
 *       projected directly.</li>
 * </ul></p>
 *
 * <p>Also available to fullscreen post-processing passes (project a world point — a light, an entity — to
 * the uv a pass samples at). There the view/projection come from KilaGraph's {@code KG_Transforms} block
 * rather than from Minecraft's per-draw ones, which a bare quad blit never gets.
 * {@link ScreenToWorldNode} is the inverse.</p>
 */
@NodeAttribute(name = "photon_world_to_screen_uv", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class, FullscreenShaderGraph.class})
public class WorldToScreenUVNode extends ShaderNode {
    private static final List<String> SPACES = List.of("absolute", "camera_relative");

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption("space", TypeHandles.STRING).withDefaultValue("absolute")
                .withTooltips(Tooltips.of("kg.node.photon_world_to_screen_uv.option.space.tooltip"))
                .withConfigurable((vc, t) -> ChoiceConfigurator.build(vc, SPACES, WorldToScreenUVNode::label)).build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("position", RenderTypeGraphTypes.VEC3);
        context.addOutputPort("uv", RenderTypeGraphTypes.VEC2);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        String pos = ctx.input("position").code();
        // absolute world coords -> camera-relative: 1.21 renders camera-relative, and cameraWorldPos() is the
        // absolute world camera position (KG's precision-split kg_CameraBlockPos - kg_CameraOffset), bound each
        // frame by KGBuiltinUniforms and overridden to the pipeline's render camera by ShaderGraphMaterial.
        if (ctx.option("space", String.class, "absolute").equals("absolute")) {
            // 26.1: KG's precision-split camera world position (kg_CameraBlockPos - kg_CameraOffset)
            String camPos = "(vec3(" + ctx.transformField("CameraBlockPos", GlslType.VEC3).code() + ") - "
                    + ctx.transformField("CameraOffset", GlslType.VEC3).code() + ")";
            pos = "(" + pos + " - " + camPos + ")";
        }
        String proj;
        String modelView;
        if (PhotonFullscreenCompiler.isCompiling()) {
            // A fullscreen pass binds no matrices of its own — no Projection block, and RenderSystem's
            // model-view is whatever the last draw left. KilaGraph's KG_Transforms carries both taken
            // from the camera object (or the one an off-screen renderer published through
            // SceneCameraContext), which is exactly what ScreenToWorldNode inverts, so the two
            // round-trip in a pass as well.
            proj = ctx.transformField("ProjMat", GlslType.MAT4).code();
            modelView = ctx.transformField("ViewMat", GlslType.MAT4).code();
        } else {
            ctx.useMinecraftUniform("Projection", "minecraft:projection.glsl");
            proj = "ProjMat";
            modelView = ctx.transformField("ModelViewMat", GlslType.MAT4).code();
        }
        // position -> clip -> ndc -> [0,1] over the viewport. That IS the scene-capture UV: the capture is
        // sized after the target being drawn into, so viewport-relative is the right space. (This used to
        // remap through U_ViewPort into a window-sized capture, which was the 1.21 layout — 26.1's PIP gives
        // the editor scene its own widget-sized texture, so that remap stretched the result and made it
        // depend on the panel size.)
        String clip = ctx.temp(GlslType.VEC4, proj + " * " + modelView + " * vec4(" + pos + ", 1.0)").code();
        ctx.output("uv", new ShaderExpr(
                "((" + clip + ".xy / " + clip + ".w) * 0.5 + 0.5)", GlslType.VEC2));
    }

    @Override
    protected String previewOutputPortId() {
        return "uv";
    }

    @Override
    public List<String> optionChoices(String optionId) {
        return "space".equals(optionId) ? SPACES : List.of();
    }

    @Override
    public String glslExample() {
        return """
                // absolute space
                vec3 p = position - cameraWorldPos;
                vec4 clip = ProjMat * ModelViewMat
                          * vec4(p, 1.0);
                uv = clip.xy / clip.w * 0.5 + 0.5;""";
    }

    private static String label(String space) {
        return switch (space) {
            case "absolute" -> "Absolute (World)";
            case "camera_relative" -> "Camera-Relative";
            default -> space;
        };
    }
}
