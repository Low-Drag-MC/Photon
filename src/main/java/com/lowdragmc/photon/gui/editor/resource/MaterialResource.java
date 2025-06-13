package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.editor_outdated.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public class MaterialResource extends Resource<IMaterial> {
    
    public MaterialResource() {
        var builtinResource = new BuiltinResourceProvider<>(this);
        builtinResource.addResource("circle", new TextureMaterial());
        addResourceProvider(builtinResource);

    }

    @Override
    public void buildDefault() {
        addResourceProvider(createNewFileResourceProvider(new File(LDLib2.getAssetsDir(), "ldlib2/resources")).setName("global"));
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
    public ResourceProviderContainer<IMaterial> createResourceProviderContainer(ResourceProvider<IMaterial> provider) {
        var container = super.createResourceProviderContainer(provider);
        container.setUiSupplier(path -> new UIElement().layout(layout -> {
                    layout.setWidthPercent(100);
                    layout.setHeightPercent(100);
                }).style(style -> style.backgroundTexture(provider.getResource(path).preview())));
        container.setOnEdit((c, path) -> {
            var material = provider.getResource(path);
            if (material == null) return;
            c.getEditor().inspectorView.inspect(material, configurator -> c.markResourceDirty(path), null);
        });

        if (provider.supportAdd()) {
            container.setOnMenu((c, m) -> m.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.add_resource", menu -> {
                for (var holder : PhotonRegistries.MATERIALS) {
                    var name = holder.annotation().name();
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
