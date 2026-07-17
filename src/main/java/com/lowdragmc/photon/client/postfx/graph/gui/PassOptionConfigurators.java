package com.lowdragmc.photon.client.postfx.graph.gui;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.IFieldValueConfigurable;
import com.lowdragmc.photon.client.postfx.graph.PassSize;
import com.lowdragmc.photon.client.postfx.graph.PassSource;
import com.lowdragmc.photon.client.postfx.graph.SizeSpec;
import com.lowdragmc.photon.client.postfx.runtime.CustomShaderPass;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Inspector UIs for the pass node's composite option values. Both are
 * {@link ConfiguratorSelectorConfigurator}s: the dropdown picks the mode/type and the child group
 * swaps to just the fields that mode actually uses — the whole value lives in ONE option
 * ({@link PassSize} / {@link PassSource}), so every row writes back through the same
 * {@link IFieldValueConfigurable}.
 */
@OnlyIn(Dist.CLIENT)
public final class PassOptionConfigurators {

    private PassOptionConfigurators() {}

    // ---- size --------------------------------------------------------------------------------

    public static IConfigurable sizeConfigurator(IFieldValueConfigurable vc) {
        var modes = Arrays.stream(SizeSpec.Mode.values()).map(Enum::name).toList();
        return IConfigurable.create(group -> group.addConfigurator(new ConfiguratorSelectorConfigurator<>(
                "photon.pass.size_mode",
                () -> size(vc).mode().name(),
                name -> vc.setValue(size(vc).withMode(parseMode(name))),
                SizeSpec.Mode.SCREEN_RELATIVE.name(), true, modes,
                name -> name,
                (name, sub) -> {
                    switch (parseMode(name)) {
                        case SCREEN_RELATIVE -> sub.addConfigurator(scaleRow(vc));
                        case INPUT_RELATIVE -> {
                            sub.addConfigurator(scaleRow(vc));
                            sub.addConfigurator(inputPortRow(vc));
                        }
                        case ABSOLUTE -> {
                            sub.addConfigurator(widthRow(vc));
                            sub.addConfigurator(heightRow(vc));
                        }
                    }
                })));
    }

    private static PassSize size(IFieldValueConfigurable vc) {
        return vc.getValue() instanceof PassSize passSize ? passSize : PassSize.DEFAULT;
    }

    private static SizeSpec.Mode parseMode(String name) {
        try {
            return SizeSpec.Mode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return SizeSpec.Mode.SCREEN_RELATIVE;
        }
    }

    private static Configurator scaleRow(IFieldValueConfigurable vc) {
        return new NumberConfigurator("photon.pass.size_scale",
                () -> size(vc).scale(),
                number -> vc.setValue(size(vc).withScale(number.floatValue())),
                1f, true);
    }

    private static Configurator inputPortRow(IFieldValueConfigurable vc) {
        return new StringConfigurator("photon.pass.size_input",
                () -> size(vc).inputPort(),
                text -> vc.setValue(size(vc).withInputPort(text)),
                "", true);
    }

    private static Configurator widthRow(IFieldValueConfigurable vc) {
        return new NumberConfigurator("photon.pass.size_width",
                () -> size(vc).width(),
                number -> vc.setValue(size(vc).withWidth(number.intValue())),
                256, true);
    }

    private static Configurator heightRow(IFieldValueConfigurable vc) {
        return new NumberConfigurator("photon.pass.size_height",
                () -> size(vc).height(),
                number -> vc.setValue(size(vc).withHeight(number.intValue())),
                256, true);
    }

    // ---- source ------------------------------------------------------------------------------

    public static IConfigurable sourceConfigurator(IFieldValueConfigurable vc) {
        var types = Arrays.stream(PassSource.Type.values()).map(Enum::name).toList();
        return IConfigurable.create(group -> group.addConfigurator(new ConfiguratorSelectorConfigurator<>(
                "photon.pass.source",
                () -> source(vc).type().name(),
                name -> vc.setValue(source(vc).withType(parseType(name))),
                PassSource.Type.GRAPH.name(), true, types,
                name -> name,
                (name, sub) -> {
                    if (parseType(name) == PassSource.Type.GRAPH) {
                        sub.addConfigurator(graphRow(vc));
                    } else {
                        sub.addConfigurator(builtinShaderRow(vc));
                        sub.addConfigurator(shaderRow(vc));
                    }
                })));
    }

    private static PassSource source(IFieldValueConfigurable vc) {
        return vc.getValue() instanceof PassSource passSource ? passSource : PassSource.DEFAULT;
    }

    private static PassSource.Type parseType(String name) {
        try {
            return PassSource.Type.valueOf(name);
        } catch (IllegalArgumentException e) {
            return PassSource.Type.GRAPH;
        }
    }

    /** A picker row: the button shows the current graph name; clicking opens the resource-browser
     *  selector dialog (thumbnails/folders), and the chosen PATH is written back via the resource's
     *  transient selection listener. */
    private static Configurator graphRow(IFieldValueConfigurable vc) {
        var row = new Configurator("photon.pass.source_graph");
        var button = new com.lowdragmc.lowdraglib2.gui.ui.elements.Button();
        button.setText(graphDisplayName(source(vc).graph()));
        button.setOnClick(event -> {
            var mui = event.currentElement.getModularUI();
            if (mui == null) return;
            FullscreenShaderGraphResource.INSTANCE.setPathSelectListener(path -> {
                vc.setValue(source(vc).withGraph(path.getPathWithType()));
                button.setText(graphDisplayName(path.getPathWithType()));
            });
            var dialog = FullscreenShaderGraphResource.INSTANCE.getResourceInstance()
                    .createSelectorDialog(event.x, event.y, tag -> { }, () -> { });
            dialog.setOnClose(() -> FullscreenShaderGraphResource.INSTANCE.setPathSelectListener(null));
            dialog.show(mui);
        });
        row.addInlineChild(button);
        return row;
    }

    /** The shipped pass library as a dropdown (a fixed engine list, unlike resource picking);
     *  foreign locations show as themselves and stay selectable. */
    private static Configurator builtinShaderRow(IFieldValueConfigurable vc) {
        var candidates = new ArrayList<String>();
        candidates.add("");
        candidates.addAll(CustomShaderPass.BUILTIN_SHADERS);
        var current = source(vc).shader();
        if (!current.isEmpty() && !candidates.contains(current)) candidates.add(current);
        return new SelectorConfigurator<>(
                "photon.pass.source_shader_builtin",
                () -> source(vc).shader(),
                path -> vc.setValue(source(vc).withShader(path)),
                "", true, candidates,
                PassOptionConfigurators::shaderDisplayName);
    }

    private static String shaderDisplayName(String location) {
        if (location == null || location.isEmpty()) return "—";
        var slash = location.lastIndexOf('/');
        return slash >= 0 ? location.substring(slash + 1) : location;
    }

    private static Configurator shaderRow(IFieldValueConfigurable vc) {
        return new StringConfigurator("photon.pass.source_shader",
                () -> source(vc).shader(),
                text -> vc.setValue(source(vc).withShader(text)),
                "", true);
    }

    public static String graphDisplayName(String pathWithType) {
        if (pathWithType == null || pathWithType.isEmpty()) {
            return I18n.get("photon.node.pass.select_graph");
        }
        var path = IResourcePath.parse(pathWithType);
        var name = path == null ? pathWithType : path.getResourceName();
        var colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
