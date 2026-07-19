package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A <b>live reference</b> to a mesh in the resource library — the mesh analog of
 * {@code UIResourceMaterial}. Resolution happens on every access (a cache-map lookup) instead of
 * latching an instance: the library's backing {@link com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData}
 * can be replaced under us (resource edits, the file watcher re-reading a changed file, pack
 * reloads), and a latched instance would silently freeze this slot on a stale object. Following the
 * canonical {@code ResourceInstance.getResource} lookup each time keeps every user of a dragged /
 * dialog-picked mesh in sync with what the resource panel shows and edits.
 */
@LDLRegisterClient(name = "resource_mesh", registry = "photon:model_source")
public final class ResourceMeshSource implements IModelSource {
    @Persisted
    private IResourcePath resourcePath = new BuiltinPath("");

    public ResourceMeshSource() {
    }

    public ResourceMeshSource(IResourcePath resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** Never null — an empty {@code BuiltinPath("")} round-trips to null through the path codec. */
    public IResourcePath getResourcePath() {
        if (resourcePath == null) resourcePath = new BuiltinPath("");
        return resourcePath;
    }

    /**
     * The referenced mesh's real geometry source, re-resolved from the library every call. Unwraps a
     * chain of references defensively (the UI never lets a resource reference another, so this only
     * guards against hand-edited data / cycles).
     */
    @Nullable
    private IModelSource resolveRaw() {
        var meshData = MeshResource.INSTANCE.getResourceInstance().getResource(getResourcePath());
        if (meshData == null) return null;
        var source = meshData.getSource();
        int guard = 0;
        while (source instanceof ResourceMeshSource ref && guard++ < 8) {
            var next = MeshResource.INSTANCE.getResourceInstance().getResource(ref.getResourcePath());
            if (next == null) return null;
            source = next.getSource();
        }
        return source instanceof ResourceMeshSource ? null : source;
    }

    @Override
    public PhotonMesh getMesh() {
        var source = resolveRaw();
        return source == null ? PhotonMesh.EMPTY : source.getMesh();
    }

    @Override
    public void invalidate() {
        var source = resolveRaw();
        if (source != null) source.invalidate();
    }

    @Override
    public boolean hasAtlasUV() {
        var source = resolveRaw();
        return source != null && source.hasAtlasUV();
    }

    @Override
    public IModelSource copy() {
        return new ResourceMeshSource(getResourcePath());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(getResourcePath(), ((ResourceMeshSource) o).getResourcePath());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getResourcePath());
    }
}
