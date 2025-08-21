package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
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

    @Override
    public ResourceProviderContainer<IMaterial> createResourceProviderContainer(IResourceProvider<IMaterial> provider) {
        var container = super.createResourceProviderContainer(provider);
        container.setUiSupplier(path -> new UIElement().layout(layout -> {
                    layout.setWidthPercent(100);
                    layout.setHeightPercent(100);
                }).style(style -> style.backgroundTexture(provider.getResource(path).preview())));
        container.setOnEdit((c, path) -> {
            var material = provider.getResource(path);
            if (material == null) return;
            c.getEditor().inspectorView.inspect(material, configurator -> c.markResourceDirty(path));
        });

        container.setOnDragProvider(UIResourceMaterial::new);

        if (provider.supportAdd()) {
            container.setOnMenu((c, m) -> m.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.add_resource", menu -> {
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
