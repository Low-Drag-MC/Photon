package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Adapts an {@link IDynamicMesh} into an {@link IModelSource}, so live geometry reaches the Model render
 * mode and the mesh emission shape through the paths a static model already uses.
 *
 * <h2>How an external mod hands one over</h2>
 *
 * <p>This source is <b>not persisted and not registered</b>. A live mesh belongs to a live thing — this
 * character, this frame — so there is nothing meaningful to write into a {@code .fx} file and no
 * registry key that would identify the right instance on the way back. It is installed as a
 * <b>runtime override</b> on the emitter instead, which is the mechanism Photon already has for
 * per-emitter render state:</p>
 *
 * <pre>{@code
 * var runtime = emitter.getConfig().renderer.getRuntime();          // ParticleRendererSetting.Runtime
 * runtime.renderMode.set(ParticleRendererSetting.Mode.Model);
 * runtime.model.set(new MeshData(new DynamicMeshSource(myCharactersMesh)));
 * }</pre>
 *
 * <p>⚠️ Which means the authored config still holds whatever static model the author picked, and that is
 * what the editor shows. The override is what plays.</p>
 *
 * <p>⚠️ Being unregistered also means it has no codec entry, so anything that does try to serialize one
 * writes an empty tag and reads back a default {@link JsonModelSource}. That is the right failure — a
 * saved reference to a live object could only ever come back broken — but it is a silent one.</p>
 *
 * <h2>Batching</h2>
 *
 * <p>Equality is the provider's <b>identity</b>, because two providers with equal-looking topology are
 * still two different animations. So emitters sharing one provider share a render pass, one set of
 * buffers and one upload — several emitters on the same character cost the deformation once — while
 * emitters on different characters are batched apart, which they have to be: they are reading different
 * geometry.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class DynamicMeshSource implements IModelSource {

    private final IDynamicMesh dynamic;
    /** Holds the last mesh so an unchanged revision hands back the identical instance. */
    private final DynamicMeshCache cache = new DynamicMeshCache();

    public DynamicMeshSource(IDynamicMesh dynamic) {
        this.dynamic = dynamic;
    }

    public IDynamicMesh getDynamic() {
        return dynamic;
    }

    /**
     * The mesh at the provider's current revision.
     *
     * <p>⚠️ Returns the <b>same instance</b> until the revision changes, which is what the
     * identity-compare consumers rely on ({@code MeshData.derivedFrom}, the render backend's built-mesh
     * check): a fresh instance per call would make every one of them redo its work every frame. A GPU-only
     * provider never produces a new instance at all — its geometry never passes through here.</p>
     */
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
        cache.invalidate(); // the provider owns the data; nothing of ours is cached beyond the last mesh
    }

    /**
     * ⚠️ Returns {@code this}, deliberately. Copying a source is how the editor clones authored config,
     * and a live mesh has no authored form to clone — a copy that duplicated the wrapper would still
     * point at the same provider, so a distinct object would only break the identity-based batching.
     */
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
