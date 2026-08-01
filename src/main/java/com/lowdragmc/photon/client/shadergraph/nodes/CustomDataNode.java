package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.INodeOption;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

/**
 * Reads one user-defined "custom data" stream ({@link com.lowdragmc.photon.client.gameobject.emitter.data.CustomData})
 * chosen by its integer index and outputs it as a {@code vec4} — regardless of how many channels the
 * stream defines, the value is always a vec4 (unused channels read 0); the user picks the channel(s)
 * they need in the graph. The read happens in the vertex stage through {@code photon_custom_data(i)}
 * (declared by {@code photon:particle.glsl}) and is routed through an auto-varying so it is usable in
 * both stages. Instanced render passes upload the {@code PhotonCustomData} buffer texture only when a
 * graph on the pass reads custom data (harvested via {@link PhotonShaderCompiler#markCustomDataUsed}).
 *
 * <p>Reads {@code vec4(0)} on the CPU (non-instanced) path, in node previews, and for stream indices
 * the emitter does not define (0..{@code MAX_CUSTOM_DATA}-1).</p>
 */
@NodeAttribute(name = "photon_custom_data", group = "photon_input",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class CustomDataNode extends ShaderNode {

    private static final String OPTION = "index";

    /** The selected custom-data stream index (from the option; clamped to non-negative). */
    private int currentIndex() {
        INodeOption opt = getNodeOptionById(OPTION);
        if (opt != null) {
            Object raw = opt.tryGetValue(Object.class).result().orElse(null);
            if (raw instanceof Number number) {
                return Math.max(0, number.intValue());
            }
        }
        return 0;
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption(OPTION, TypeHandles.INT)
                .withDefaultValue(0)
                .withTooltips(Tooltips.of("kg.node.photon_custom_data.option.index.tooltip"))
                .build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort("out", RenderTypeGraphTypes.VEC4);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        var zero = new ShaderExpr("vec4(0.0)", GlslType.VEC4);
        if (ctx.isPreview()) {
            ctx.output("out", zero);
            return;
        }
        var compiler = PhotonShaderCompiler.current();
        if (compiler != null) {
            compiler.markCustomDataUsed();
        }
        // out-of-range indices (>= MAX_CUSTOM_DATA) are handled by the GLSL accessor returning vec4(0)
        int index = currentIndex();
        ctx.output("out", ctx.varyingInput("photon_custom_" + index, GlslType.VEC4,
                () -> new ShaderExpr("photon_custom_data(" + index + ")", GlslType.VEC4), zero));
    }

    @Override
    protected String previewOutputPortId() {
        return "out";
    }

    @Override
    public String glslExample() {
        return """
                // vertex stage, routed through a varying
                vec4 data = photon_custom_data(0);""";
    }
}
