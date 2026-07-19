package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;

import java.util.Objects;

/**
 * A <b>live reference</b> to a material in the resource library. Resolution happens on every access
 * (a cache-map lookup) instead of being latched once: the library's backing instance can be replaced
 * under us — resource edits, the file watcher re-reading a changed file (which also clears the
 * {@code ResourceInstance} cache), pack reloads — and a latched instance would silently freeze this
 * material on a stale object forever. Following the canonical
 * {@code ResourceInstance.getResource} lookup each time keeps every user of this reference in sync
 * with what the resource panel shows and edits.
 */
@LDLRegisterClient(name = "ui_resource_material", registry = "photon:material")
public final class UIResourceMaterial implements IMaterial {
    @Persisted
    private IResourcePath resourcePath = new BuiltinPath("");

    private UIResourceMaterial() {

    }

    public UIResourceMaterial(IResourcePath resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** Never null — an empty {@code BuiltinPath("")} round-trips to null through the path codec. */
    public IResourcePath getResourcePath() {
        if (resourcePath == null) resourcePath = new BuiltinPath("");
        return resourcePath;
    }

    /** The referenced material, re-resolved from the library on every call (cheap cached lookup). */
    public IMaterial getInternalMaterial() {
        var material = MaterialResource.INSTANCE.getResourceInstance().getResource(getResourcePath());
        return material == null ? IMaterial.MISSING : material;
    }

    public IMaterial getRawMaterial() {
        var material = getInternalMaterial();
        if (material instanceof UIResourceMaterial resourceMaterial) return resourceMaterial.getRawMaterial();
        return material;
    }

    // TODO(M2): delegate the pipeline/uniform contribution to getInternalMaterial() once the
    // pipeline-based material seam replaces the removed begin/end.

    @Override
    public IGuiTexture preview() {
        return getInternalMaterial().preview();
    }

    @Override
    public UIResourceMaterial copy() {
        return new UIResourceMaterial(getResourcePath());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        UIResourceMaterial that = (UIResourceMaterial) o;
        return Objects.equals(getResourcePath(), that.getResourcePath());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getResourcePath());
    }
}
