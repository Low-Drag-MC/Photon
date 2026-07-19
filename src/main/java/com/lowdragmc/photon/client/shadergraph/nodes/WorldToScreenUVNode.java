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
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Projects a {@code Position} to a screen-space UV. Output is <b>window-relative</b> (the same space as
 * {@code screenUv()}) — it takes the projected point to NDC, then remaps through {@code U_ViewPort} into
 * the window-sized scene capture, so it is correct in-world and in the editor's sub-viewport scene alike.
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
 */
@NodeAttribute(name = "photon_world_to_screen_uv", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class WorldToScreenUVNode extends ShaderNode {
    private static final List<String> SPACES = List.of("absolute", "camera_relative");

    @Override
    protected Component getNodeTooltip() {
        return Component.translatable("photon.node.world_to_screen_uv.tooltip");
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption("space", TypeHandles.STRING).withDefaultValue("absolute")
                .withTooltips(Tooltips.of(
                        "photon.node.world_to_screen_uv.option.space.tooltip.absolute",
                        "photon.node.world_to_screen_uv.option.space.tooltip.camera_relative"))
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
        ctx.useMinecraftUniform("Projection", "minecraft:projection.glsl");
        String proj = "ProjMat";
        String modelView = ctx.transformField("ModelViewMat", GlslType.MAT4).code();
        // TODO(M2): U_ViewPort was a Photon dynamic uniform staged by the dead ShaderInstance path —
        // re-plumb it (std140) with the material pipeline; until then it reads as zeros.
        String viewport = ctx.uniform(PhotonShaderCompiler.VIEWPORT, GlslType.VEC4).code();
        String screenSize = ctx.screenSize().code();
        // position -> clip -> ndc -> [0,1] over the viewport, then remap through the viewport rect into the
        // window-sized scene capture so it matches screenUv()'s window-relative convention.
        String clip = ctx.temp(GlslType.VEC4, proj + " * " + modelView + " * vec4(" + pos + ", 1.0)").code();
        String vpUv = ctx.temp(GlslType.VEC2, "((" + clip + ".xy / " + clip + ".w) * 0.5 + 0.5)").code();
        ctx.output("uv", new ShaderExpr(
                "((" + viewport + ".xy + " + vpUv + " * " + viewport + ".zw) / " + screenSize + ")",
                GlslType.VEC2));
    }

    @Override
    protected String previewOutputPortId() {
        return "uv";
    }

    private static String label(String space) {
        return switch (space) {
            case "absolute" -> "Absolute (World)";
            case "camera_relative" -> "Camera-Relative";
            default -> space;
        };
    }
}
