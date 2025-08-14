package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import lombok.Getter;
import net.minecraft.client.renderer.ShaderInstance;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Optional;

@LDLRegisterClient(name = "ui_resource_material", registry = "photon:material")
public final class UIResourceMaterial implements IMaterial {
    @Persisted
    private IResourcePath resourcePath = new BuiltinPath("");
    @Getter(lazy = true)
    private final IMaterial internalTexture = getMaterialFromResource();

    private UIResourceMaterial() {

    }

    public UIResourceMaterial(IResourcePath resourcePath) {
        this.resourcePath = resourcePath;
    }

    private IMaterial getMaterialFromResource() {
        return Optional.ofNullable(MaterialResource.INSTANCE.getResourceInstance().getResource(resourcePath))
                .orElse(IMaterial.MISSING);
    }

    @Override
    public ShaderInstance begin(@Nonnull MaterialContext context) {
        return getInternalTexture().begin(context);
    }

    @Override
    public void end(@Nonnull MaterialContext context) {
        getInternalTexture().end(context);
    }

    @Override
    public IGuiTexture preview() {
        return getInternalTexture().preview();
    }

    @Override
    public UIResourceMaterial copy() {
        return new UIResourceMaterial(resourcePath);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        UIResourceMaterial that = (UIResourceMaterial) o;
        return Objects.equals(resourcePath, that.resourcePath);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(resourcePath);
    }
}
