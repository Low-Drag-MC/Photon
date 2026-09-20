package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.jetbrains.annotations.Nullable;

/**
 * Turns an {@link IDynamicMesh}'s "topology plus a revision" into the single {@link PhotonMesh} instance
 * the rest of Photon expects, holding the last one so an unchanged revision hands back the <b>identical</b>
 * object.
 *
 * <p>That identity is the whole point. Every consumer downstream detects a changed model by comparing
 * instances — {@code MeshData.derivedFrom} re-derives its sampling geometry, the render backend rebuilds
 * its buffers — so a fresh instance per call would make all of them redo their work every frame, which is
 * the opposite of what a revision counter is for.</p>
 *
 * <p>Split out of {@link DynamicMeshSource} so it can be tested without a Minecraft registry:
 * {@code IModelSource}'s dispatch codec is a static field on the interface, so merely constructing an
 * implementation of it needs a frozen registry. This class touches nothing but {@link PhotonMesh}.</p>
 */
final class DynamicMeshCache {

    @Nullable
    private PhotonMesh current;

    /**
     * The mesh at the provider's current revision.
     *
     * <p>A provider with no CPU-side geometry (it deformed on the GPU) resolves to its topology
     * unchanged: there is no pose to build on this side, and the render backend binds the provider's
     * buffer instead of uploading anything.</p>
     */
    PhotonMesh resolve(IDynamicMesh dynamic) {
        var topology = dynamic.topology();
        long revision = dynamic.revision();
        var cached = current;
        if (cached != null && cached.topology() == topology.topology()
                && cached.geometryRevision() == revision) {
            return cached;
        }
        var geometry = dynamic.geometry();
        var mesh = geometry == null
                ? topology
                : topology.withGeometry(geometry, dynamic.tangents(), revision);
        current = mesh;
        return mesh;
    }

    void invalidate() {
        current = null;
    }
}
