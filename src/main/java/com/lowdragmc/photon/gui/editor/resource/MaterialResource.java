package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.KilaGraphExport;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MaterialResource extends Resource<IMaterial> {
    public static final MaterialResource INSTANCE = new MaterialResource();

    @Override
    public void buildBuiltin(BuiltinResourceProvider<IMaterial> provider) {
        provider.addResource("missing", IMaterial.MISSING);
        provider.addResource("block_atlas", BlockTextureSheetMaterial.INSTANCE);

        addBuiltinShaderMaterial(provider, "circle");
        addBuiltinTextureMaterial(provider, "kila_tail");
        addBuiltinTextureMaterial(provider, "laser");
        addBuiltinTextureMaterial(provider, "smoke");
        addBuiltinTextureMaterial(provider, "thaumcraft");
        addBuiltinTextureMaterial(provider, "ring");
    }

    private void addVanillaTextureMaterial(BuiltinResourceProvider<IMaterial> builtin, String name) {
        builtin.addResource(name, new TextureMaterial(ResourceLocation.parse("textures/particle/%s.png".formatted(name))));
    }

    private void addBuiltinTextureMaterial(BuiltinResourceProvider<IMaterial> builtin, String name) {
        builtin.addResource(name, new TextureMaterial(ResourceLocation.parse("photon:textures/particle/%s.png".formatted(name))));
    }

    private void addBuiltinShaderMaterial(BuiltinResourceProvider<IMaterial> builtin, String name) {
        builtin.addResource(name, new CustomShaderMaterial(ResourceLocation.parse("photon:%s".formatted(name))));
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.MATERIAL;
    }

    @Override
    public String getName() {
        return "material";
    }

    @Nullable
    @Override
    public Tag serializeResource(IMaterial material, HolderLookup.Provider provider) {
        return material.serializeWrapper();
    }

    @Override
    public IMaterial deserializeResource(Tag tag, HolderLookup.Provider provider) {
        return IMaterial.deserializeWrapper(tag);
    }

    /**
     * Fired (with the clicked path) whenever a material tile is selected in ANY container of this
     * resource — set transiently by {@code IMaterialConfigurator}'s selector dialog to receive the
     * chosen <em>path</em> (the stock selector callback only reports the value), cleared on close.
     */
    @Nullable
    private java.util.function.Consumer<IResourcePath> pathSelectListener;

    public void setPathSelectListener(@Nullable java.util.function.Consumer<IResourcePath> listener) {
        this.pathSelectListener = listener;
    }

    /**
     * The material a tile shows and the inspector edits: the provider's own, since that is the one
     * {@code markResourceDirty} writes back. For a registered provider it is also what every
     * {@link UIResourceMaterial} resolves. A folder the asset browser opened has a provider the
     * {@code ResourceInstance} doesn't know, so the canonical lookup reads the file a second time; editing that
     * copy saved the untouched one back over it.
     */
    @Nullable
    private IMaterial editable(IResourceProvider<IMaterial> provider, IResourcePath path) {
        var own = provider.getResource(path);
        return own != null ? own : getResourceInstance().getResource(path);
    }

    @Override
    public ResourceProviderContainer<IMaterial> createResourceProviderContainer(IResourceProvider<IMaterial> provider) {
        var container = new ResourceProviderContainer<>(provider) {
            @Override
            public void selectResource(IResourcePath resourcePath) {
                super.selectResource(resourcePath);
                if (pathSelectListener != null && resourcePath != null && provider.hasResource(resourcePath)) {
                    pathSelectListener.accept(resourcePath);
                }
            }
        };
        container.setUiSupplier(path -> new UIElement().layout(layout -> {
                    layout.widthPercent(100);
                    layout.heightPercent(100);
                }).style(style -> style.backgroundTexture(
                        com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture.of(() -> {
                            var material = editable(provider, path);
                            return material == null ? IGuiTexture.MISSING_TEXTURE : material.preview();
                        }))));
        container.setOnEdit((c, path) -> {
            var material = editable(provider, path);
            if (material == null) return;
            c.getEditor().inspectorView.inspect(material, configurator -> c.markResourceDirty(path));
        });

        container.setOnDragProvider(UIResourceMaterial::new);
        container.setOnMenu((c, menu) -> {
            var path = c.getSelected();
            if (path == null) return;
            if (editable(provider, path) instanceof KilaMaterial kila) {
                menu.crossLine();
                menu.leaf("photon.material.export_shader_graph", () -> exportShaderGraph(c, path, kila));
                return;
            }
            if (!c.getCanEdit().test(path)) return;
            if (!(editable(provider, path) instanceof TextureMaterial texture)
                    || texture.getClass() != TextureMaterial.class) return;
            // converts in place, so every effect referencing the material picks up the Kila one
            menu.crossLine();
            menu.leaf("photon.material.convert_to_kila", () -> Dialog.showCheckBox("photon.material.convert_to_kila",
                    Component.translatable("photon.material.convert_to_kila.confirm", c.resourceProvider.getResourceName(path)),
                    confirmed -> {
                        if (!confirmed) return;
                        var kila = KilaMaterial.fromTexture(texture);
                        if (!c.resourceProvider.addResource(path, kila)) return;
                        c.reloadSpecificResource(path);
                        if (c.getEditor() != null) {
                            c.getEditor().inspectorView.inspect(kila, configurator -> c.markResourceDirty(path));
                        }
                    }).show(c.getModularUI()));
        });

        if (provider.supportAdd()) {
            container.setOnCreateMenu((c, m) -> m.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.add_resource", menu -> {
                for (var holder : PhotonRegistries.MATERIALS) {
                    var name = holder.annotation().name();
                    if (name.equals("missing") || name.equals("block_atlas") || name.equals("ui_resource_material")) continue;
                    menu.leaf(name, () -> {
                        var material = holder.value().get();
                        c.addNewResource(material);
                    });
                }
            }));
        }
        return container;
    }

    /**
     * Asks where the shader graph and the material drawing with it go, and under what name; then writes {@code kila}
     * out as both. The Kila material stays as it is.
     */
    public static void exportShaderGraph(ResourceProviderContainer<IMaterial> c, IResourcePath path, KilaMaterial kila) {
        var graphs = ShaderGraphResource.INSTANCE.getResourceInstance().listWritableProviders();
        var materials = INSTANCE.getResourceInstance().listWritableProviders();
        if (graphs.isEmpty() || materials.isEmpty()) {
            Dialog.showNotification("photon.material.export_shader_graph", "photon.material.export_shader_graph.no_target", null)
                    .show(c.getModularUI());
            return;
        }
        var type = c.resourceProvider.getType();
        var graphTarget = providerSelector(graphs, graphs.stream().filter(p -> p.getType() == type).findFirst()
                .orElse(graphs.getFirst()));
        var materialTarget = providerSelector(materials, materials.contains(c.resourceProvider) ? c.resourceProvider
                : materials.stream().filter(p -> p.getType() == type).findFirst().orElse(materials.getFirst()));
        var nameField = new TextField().setText(c.resourceProvider.getResourceName(path), false)
                .setCharValidator(ResourceLocation::isAllowedInResourceLocation);
        nameField.getLayout().widthPercent(100);
        nameField.addClass("__export-name-field__");
        graphTarget.addClass("__export-graph-target__");
        materialTarget.addClass("__export-material-target__");

        var dialog = new Dialog().setTitle("photon.material.export_shader_graph");
        dialog.addContent(new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightAuto();
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(2);
        }).addChildren(
                new Label().setText(Component.translatable("photon.material.export_shader_graph.graph_target")), graphTarget,
                new Label().setText(Component.translatable("photon.material.export_shader_graph.material_target")), materialTarget,
                new Label().setText(Component.translatable("photon.material.export_shader_graph.name")), nameField));
        // the dropdowns open on the root: a click in one is not a click outside the dialog
        dialog.addExternalElement(graphTarget.dialog);
        dialog.addExternalElement(materialTarget.dialog);
        dialog.addButton(new Button().setOnClick(e -> {
            var name = nameField.getText().trim();
            var graphProvider = graphTarget.getValue();
            var materialProvider = materialTarget.getValue();
            dialog.close();
            if (!name.isEmpty() && graphProvider != null && materialProvider != null) {
                writeExport(c, kila, graphProvider, materialProvider, name);
            }
        }).setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.addButton(new Button().setOnClick(e -> dialog.close()).setText("ldlib.gui.tips.cancel")
                .addClass("__cancel-button__"));
        dialog.show(c.getModularUI());
    }

    private static <T> Selector<IResourceProvider<T>> providerSelector(List<IResourceProvider<T>> providers,
                                                                      IResourceProvider<T> selected) {
        var selector = new Selector<IResourceProvider<T>>()
                .setCandidateUIProvider(UIElementProvider.iconText(
                        provider -> provider == null ? IGuiTexture.EMPTY : provider.getType().getIcon(),
                        provider -> Component.literal(provider == null ? "" : provider.getName())))
                .setCandidates(providers)
                .setSelected(selected, false);
        selector.getLayout().widthPercent(100);
        return selector;
    }

    private static void writeExport(ResourceProviderContainer<IMaterial> c, KilaMaterial kila,
                                    IResourceProvider<CompoundTag> graphTarget, IResourceProvider<IMaterial> materialTarget,
                                    String name) {
        var result = KilaGraphExport.export(kila);
        var graphPath = freePath(graphTarget, name);
        if (!graphTarget.addResource(graphPath, ShaderGraphResource.INSTANCE.serializeGraph(result.graph()))) return;
        ShaderGraphRuntime.invalidate(graphPath);
        var material = new ShaderGraphMaterial(graphPath);
        material.setRenderState(result.renderState());
        var materialPath = freePath(materialTarget, name + "_graph");
        var materialName = materialTarget.getResourceName(materialPath);
        if (materialTarget == c.resourceProvider) {
            c.addNewResource(material, materialName);
        } else {
            materialTarget.addResource(materialPath, material);
        }

        var report = Component.translatable("photon.material.export_shader_graph.done",
                graphTarget.getResourceName(graphPath), graphTarget.getName(), materialName, materialTarget.getName());
        if (!result.skipped().isEmpty()) {
            report.append("\n\n").append(Component.translatable("photon.material.export_shader_graph.skipped"));
            for (var key : result.skipped()) report.append("\n- ").append(Component.translatable(key));
        }
        var dialog = new Dialog().setTitle("photon.material.export_shader_graph");
        dialog.addContent(new Label().textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true))
                .setText(report).layout(layout -> layout.widthPercent(100)));
        dialog.addButton(new Button().setOnClick(e -> dialog.close()).setText("ldlib.gui.tips.confirm")
                .addClass("__confirm-button__"));
        dialog.show(c.getModularUI());
    }

    private static <T> IResourcePath freePath(IResourceProvider<T> provider, String name) {
        var path = provider.createSubPath(name);
        for (int i = 1; provider.hasResource(path); i++) path = provider.createSubPath(name + "_" + i);
        return path;
    }
}
