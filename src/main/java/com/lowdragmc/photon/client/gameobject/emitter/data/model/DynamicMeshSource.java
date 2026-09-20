package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Adapts an {@link IDynamicMesh} into an {@link IModelSource}.
 *
 * <p>Not registered and not persisted — a live mesh has no authored form to save, so it is installed as
 * a runtime override instead:</p>
 *
 * <pre>{@code
 * runtime.renderMode.set(ParticleRendererSetting.Mode.Model);
 * runtime.model.set(new MeshData(new DynamicMeshSource(myCharactersMesh)));
 * }</pre>
 *
 * <p>Equality is the provider's identity, so emitters sharing one provider share a render pass, its
 * buffers and its upload, while different providers batch apart.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class DynamicMeshSource implements IModelSource {

    private final IDynamicMesh dynamic;
    private final DynamicMeshCache cache = new DynamicMeshCache();

    public DynamicMeshSource(IDynamicMesh dynamic) {
        this.dynamic = dynamic;
    }

    public IDynamicMesh getDynamic() {
        return dynamic;
    }

    @Override
    public PhotonMesh getMesh() {
        return cache.resolve(dynamic);
    }

    @Override
    public IDynamicMesh asDynamic() {
        return dynamic;
    }

    @Override
    public void invalidate() {
        cache.invalidate();
    }

    /** ⚠️ Returns {@code this}: a copy would be a second wrapper around one provider, which is exactly
     *  what must not batch apart. */
    @Override
    public IModelSource copy() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DynamicMeshSource other && other.dynamic == dynamic;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(dynamic);
    }
}
