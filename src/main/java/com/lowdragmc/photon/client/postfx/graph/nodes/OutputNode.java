package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.kilagraph.rendertype.compiler.NodeDisplayNames;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphTypes;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect;
import net.minecraft.network.chat.Component;

/**
 * The effect's fixed output: whatever texture feeds {@code color} becomes the new scene color.
 * Also carries the effect-level settings as options — {@code priority} (execution order on the
 * single global axis; builtin bloom = 0, &lt;0 runs before it, &gt;0 after) and {@code autoBlend}
 * (the automatic {@code mix(scene, effect, Weight)} fade). Created by the graph, non-deletable.
 */
@NodeAttribute(name = "photon_effect_output", group = "photon_render_graph", graphTypes = RenderGraph.class)
public class OutputNode extends Node {

    public static final String COLOR_PORT = "color";
    public static final String OPTION_PRIORITY = "priority";
    public static final String OPTION_AUTO_BLEND = "autoBlend";

    @Override
    public Component getDisplayName() {
        return NodeDisplayNames.fromAttribute(this);
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption(OPTION_PRIORITY, TypeHandles.INT)
                .withDefaultValue(CompiledEffect.DEFAULT_PRIORITY)
                .withTooltips(Tooltips.of("photon.node.effect_output.option.priority.tooltip"))
                .showInInspectorOnly()
                .build();
        context.addOption(OPTION_AUTO_BLEND, TypeHandles.BOOL)
                .withDefaultValue(true)
                .withTooltips(Tooltips.of("photon.node.effect_output.option.auto_blend.tooltip"))
                .showInInspectorOnly()
                .build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        // TEXTURE is wire-only: no inline editor, no embedded constant (TextureValue has no codec)
        context.addInputPort(COLOR_PORT, RenderGraphTypes.TEXTURE)
                .withoutConfigurator().withoutSerialization();
    }

    public int priority() {
        var option = getNodeOptionById(OPTION_PRIORITY);
        if (option != null) {
            var value = option.tryGetValue(Object.class).result().orElse(null);
            if (value instanceof Integer priority) return priority;
        }
        return CompiledEffect.DEFAULT_PRIORITY;
    }

    public boolean autoBlend() {
        var option = getNodeOptionById(OPTION_AUTO_BLEND);
        if (option != null) {
            var value = option.tryGetValue(Object.class).result().orElse(null);
            if (value instanceof Boolean autoBlend) return autoBlend;
        }
        return true;
    }
}
