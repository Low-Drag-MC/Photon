package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

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
     * All reads go through the canonical {@code ResourceInstance.getResource} lookup (NOT the raw
     * provider): that is the same instance every {@link UIResourceMaterial} reference resolves, so the
     * object the inspector edits, the tile previews, and the materials applied to fx objects are always
     * one and the same — a per-provider lookup could diverge from it after a file-watcher reload.
     */
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
                            var material = getResourceInstance().getResource(path);
                            return material == null ? IGuiTexture.MISSING_TEXTURE : material.preview();
                        }))));
        container.setOnEdit((c, path) -> {
            var material = getResourceInstance().getResource(path);
            if (material == null) return;
            c.getEditor().inspectorView.inspect(material, configurator -> c.markResourceDirty(path));
        });

        container.setOnDragProvider(UIResourceMaterial::new);
        // converts in place, so every effect referencing the material picks up the Kila one
        container.setOnMenu((c, menu) -> {
            var path = c.getSelected();
            if (path == null || !c.getCanEdit().test(path)) return;
            if (!(getResourceInstance().getResource(path) instanceof TextureMaterial texture)
                    || texture.getClass() != TextureMaterial.class) return;
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
}
