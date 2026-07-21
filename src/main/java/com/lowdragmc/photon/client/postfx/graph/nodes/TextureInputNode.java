package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes.Sampler2DValue;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Supplies an external texture to a pass's texture port — the render-graph counterpart of the
 * fullscreen graph's {@code rt_texture} node. Its {@code mode} option decides where the texture comes
 * from:
 * <ul>
 *   <li>{@link Mode#ASSET} — a fixed image baked into the effect ({@code texture} = a
 *       {@link Sampler2DValue}, edited with the SAMPLER2D picker);</li>
 *   <li>{@link Mode#PARAMETER} — an effect sampler parameter named {@code name}, exposed in the
 *       timeline clip inspector and set per request. {@code texture} is its default. Samplers don't
 *       interpolate, so overlapping clips take the highest-weight one.</li>
 * </ul>
 * The single TEXTURE output wires into any pass's texture input. A SAMPLER parameter can't be a
 * blackboard variable ({@code RenderGraph.getVariableSupportTypes()} excludes TEXTURE/SAMPLER2D), so
 * this node is the only way to expose one.
 */
@NodeAttribute(name = "photon_texture_input", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class TextureInputNode extends Node {

    public static final String OPTION_MODE = "mode";
    public static final String OPTION_TEXTURE = "texture";
    public static final String OPTION_NAME = "name";
    public static final String OUTPUT_PORT = "out";

    public enum Mode { ASSET, PARAMETER }

    @Override
    public Component getDisplayName() {
        if (mode() == Mode.PARAMETER) {
            var name = paramName();
            return name.isEmpty() ? Component.translatable("photon_texture_input") : Component.literal(name);
        }
        var location = textureValue().location();
        if (location == null || location.isEmpty()) return Component.translatable("photon_texture_input");
        var slash = location.lastIndexOf('/');
        return Component.literal(slash >= 0 ? location.substring(slash + 1) : location);
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        var modes = Arrays.stream(Mode.values()).map(Enum::name).toList();
        context.addOption(OPTION_MODE, TypeHandles.STRING)
                .withDefaultValue(Mode.ASSET.name())
                .withTooltips(Tooltips.of("photon.node.texture_input.option.mode.tooltip"))
                .withConfigurable((vc, type) -> IConfigurable.create(group ->
                        group.addConfigurator(new SelectorConfigurator<>(
                                "photon.texture_input.mode",
                                () -> vc.getValue() instanceof String s && !s.isEmpty() ? s : Mode.ASSET.name(),
                                vc::setValue,
                                Mode.ASSET.name(),
                                vc.forceUpdate(),
                                modes,
                                name -> name))))
                .showInInspectorOnly()
                .build();
        // SAMPLER2D carries the Sampler2DConfigurator (custom/atlas picker + params + preview).
        context.addOption(OPTION_TEXTURE, RenderTypeGraphTypes.SAMPLER2D)
                .withDisplayName(Component.empty())
                .withDefaultValue(Sampler2DValue.defaultValue())
                .withTooltips(Tooltips.of("photon.node.texture_input.option.texture.tooltip"))
                .showInInspectorOnly()
                .build();
        context.addOption(OPTION_NAME, TypeHandles.STRING)
                .withDefaultValue("")
                .withTooltips(Tooltips.of("photon.node.texture_input.option.name.tooltip"))
                .showInInspectorOnly()
                .build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort(OUTPUT_PORT, RenderGraphTypes.TEXTURE);
    }

    // ---- option readers (compiler + display) -------------------------------------------------

    public Mode mode() {
        if (optionValue(OPTION_MODE) instanceof String name) {
            try {
                return Mode.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Mode.ASSET;
    }

    public Sampler2DValue textureValue() {
        return optionValue(OPTION_TEXTURE) instanceof Sampler2DValue value ? value : Sampler2DValue.defaultValue();
    }

    public String paramName() {
        return optionValue(OPTION_NAME) instanceof String name ? name : "";
    }

    @Nullable
    private Object optionValue(String id) {
        if (getNodeModel() == null) return null; // library display instance, options not attached
        var option = getNodeOptionById(id);
        return option == null ? null : option.tryGetValue(Object.class).result().orElse(null);
    }
}
