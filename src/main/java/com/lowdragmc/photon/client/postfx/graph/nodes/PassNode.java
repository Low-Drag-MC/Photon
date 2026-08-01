package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.PassSize;
import com.lowdragmc.photon.client.postfx.graph.PassSource;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.photon.client.postfx.graph.gui.PassOptionConfigurators;
import com.lowdragmc.photon.client.postfx.runtime.CustomShaderPass;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * One fullscreen dispatch in the effect graph. Its {@link PassSource} picks what it runs (a
 * fullscreen shader graph now; a hand-written core shader in a later phase) and mirrors that
 * source's interface as ports: every {@code SAMPLER2D} input variable becomes a TEXTURE input port
 * (wire scene inputs or other passes' outputs), every exposed uniform becomes a typed value port
 * (inline constant, or wire a blackboard parameter). The single TEXTURE output is this pass's
 * render target, sized by the {@link PassSize} option ({@code INPUT_RELATIVE ×0.5} chains build the
 * manually-unrolled bloom pyramid).
 *
 * <p>Ports are keyed by variable display name — renaming a variable in the fullscreen graph orphans
 * wires on existing pass nodes (reconnect them). The {@code Weight} variable is engine-driven and
 * not exposed as a port.</p>
 */
@NodeAttribute(name = "photon_pass", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class PassNode extends RenderGraphNode {

    public static final String OPTION_SOURCE = "source";
    public static final String OPTION_SIZE = "size";
    public static final String OPTION_FORMAT = "format";
    public static final String OUTPUT_PORT = "out";

    @Override
    public Component getDisplayName() {
        var source = passSource();
        var label = switch (source.type()) {
            case GRAPH -> source.graph().isEmpty() ? ""
                    : PassOptionConfigurators.graphDisplayName(source.graph());
            case CUSTOM_SHADER -> source.shader();
        };
        return label.isEmpty() ? Component.translatable("photon_pass") : Component.literal(label);
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption(OPTION_SOURCE, RenderGraphTypes.SOURCE)
                .withDisplayName(Component.empty())
                .withDefaultValue(PassSource.DEFAULT)
                .withCodec(PassSource.CODEC)
                .withTooltips(Tooltips.of("kg.node.photon_pass.option.source.tooltip"))
                .build();
        context.addOption(OPTION_SIZE, RenderGraphTypes.SIZE)
                .withDisplayName(Component.empty())
                .withDefaultValue(PassSize.DEFAULT)
                .withCodec(PassSize.CODEC)
                .withTooltips(Tooltips.of("kg.node.photon_pass.option.size.tooltip"))
                .showInInspectorOnly()
                .build();
        context.addOption(OPTION_FORMAT, TypeHandles.STRING)
                .withDefaultValue(TargetFormat.RGBA16F.name())
                .withTooltips(Tooltips.of("kg.node.photon_pass.option.format.tooltip"))
                .withConfigurable((vc, type) -> IConfigurable.create(
                        group -> group.addConfigurator(new SelectorConfigurator<>(
                                "photon.pass.format",
                                () -> vc.getValue() instanceof String s && !s.isEmpty() ? s
                                        : TargetFormat.RGBA16F.name(),
                                vc::setValue,
                                TargetFormat.RGBA16F.name(),
                                vc.forceUpdate(),
                                Arrays.stream(TargetFormat.values()).map(Enum::name).toList(),
                                name -> name))))
                .showInInspectorOnly()
                .build();
    }

    /** One remembered input port — replayed when the source graph is transiently broken. */
    private record CachedPort(String name, com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle type,
                              boolean texture, @Nullable Object defaultValue) {}

    /**
     * The last successfully-resolved input port set. A broken/missing source graph must NOT
     * define zero ports — port reconciliation would DELETE every wire, destroying authored data
     * over a transient failure. Replaying the cached set keeps wires alive until the graph
     * resolves again (the render-graph compiler still reports the broken pass).
     */
    private final java.util.List<CachedPort> lastGoodPorts = new java.util.ArrayList<>();

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        if (passSource().type() == PassSource.Type.GRAPH) {
            var entry = FullscreenGraphRuntime.get(graphPath());
            if (entry != null && entry.isValid() && entry.getGraph() != null) {
                var compiled = entry.getCompiled();
                lastGoodPorts.clear();
                for (var declaration : entry.getGraph().graphModel.getGraphVariableModels()) {
                    if (declaration == null) continue;
                    var name = declaration.getName();
                    if (compiled.variableSamplers().containsKey(name)) {
                        lastGoodPorts.add(new CachedPort(name, RenderGraphTypes.TEXTURE, true, null));
                    } else if (compiled.uniformFields().containsKey(name)) {
                        var defaultValue = declaration.tryGetDefaultValue(declaration.getDataType())
                                .result().orElse(null);
                        lastGoodPorts.add(new CachedPort(name, declaration.getDataTypeHandle(), false, defaultValue));
                    }
                }
            }
        } else if (!passSource().shader().isEmpty()) {
            // hand-written shader: the port set is introspected from the shader json
            var info = CustomShaderPass.getInfo(passSource().shader());
            if (info != null) {
                lastGoodPorts.clear();
                for (var sampler : info.samplers()) {
                    lastGoodPorts.add(new CachedPort(sampler, RenderGraphTypes.TEXTURE, true, null));
                }
                for (var uniform : info.uniforms()) {
                    var defaults = uniform.defaults();
                    switch (uniform.count()) {
                        case 1 -> lastGoodPorts.add(new CachedPort(uniform.name(),
                                TypeHandles.FLOAT, false, defaults[0]));
                        case 2 -> lastGoodPorts.add(new CachedPort(uniform.name(),
                                RenderTypeGraphTypes.VEC2, false, new Vector2f(defaults[0], defaults[1])));
                        case 3 -> lastGoodPorts.add(new CachedPort(uniform.name(),
                                RenderTypeGraphTypes.VEC3, false,
                                new Vector3f(defaults[0], defaults[1], defaults[2])));
                        case 4 -> lastGoodPorts.add(new CachedPort(uniform.name(),
                                RenderTypeGraphTypes.VEC4, false,
                                new Vector4f(defaults[0], defaults[1], defaults[2], defaults[3])));
                        default -> { }
                    }
                }
            }
        }
        // valid source -> the fresh port set; broken source -> replay the last good one
        for (var port : lastGoodPorts) {
            if (port.texture()) {
                // TEXTURE is wire-only: no inline editor, and no embedded constant to persist
                // (TextureValue has no codec — a serialized constant would warn and break the
                // node's round-trip)
                context.addInputPort(port.name(), RenderGraphTypes.TEXTURE)
                        .withoutConfigurator().withoutSerialization();
            } else {
                var builder = context.addInputPort(port.name(), port.type());
                if (port.defaultValue() != null) builder.withDefaultValue(port.defaultValue());
            }
        }
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }

    // ---- option readers (compiler + display) -------------------------------------------------

    public PassSource passSource() {
        return optionValue(OPTION_SOURCE) instanceof PassSource source ? source : PassSource.DEFAULT;
    }

    public PassSize passSize() {
        return optionValue(OPTION_SIZE) instanceof PassSize passSize ? passSize : PassSize.DEFAULT;
    }

    @Nullable
    public IResourcePath graphPath() {
        var source = passSource();
        if (source.type() != PassSource.Type.GRAPH || source.graph().isEmpty()) return null;
        return IResourcePath.parse(source.graph());
    }

    public TargetFormat targetFormat() {
        if (optionValue(OPTION_FORMAT) instanceof String name && !name.isEmpty()) {
            try {
                return TargetFormat.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return TargetFormat.RGBA16F;
    }

    @Nullable
    private Object optionValue(String id) {
        if (getNodeModel() == null) return null; // library display instance, options not attached
        var option = getNodeOptionById(id);
        return option == null ? null : option.tryGetValue(Object.class).result().orElse(null);
    }

    @Override
    public java.util.List<String> optionChoices(String optionId) {
        return OPTION_FORMAT.equals(optionId)
                ? Arrays.stream(TargetFormat.values()).map(Enum::name).toList()
                : java.util.List.of();
    }
}
