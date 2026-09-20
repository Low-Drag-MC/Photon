package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.jetbrains.annotations.Nullable;

/**
 * Resolves an {@link IDynamicMesh} to a {@link PhotonMesh}, holding the last one so an unchanged
 * revision hands back the <b>identical</b> object — every consumer downstream detects change by
 * instance identity, so a fresh instance per call would make all of them redo their work every frame.
 *
 * <p>Split out of {@link DynamicMeshSource} so it can be tested without a Minecraft registry.</p>
 */
final class DynamicMeshCache {

    @Nullable
    private PhotonMesh current;

    PhotonMesh resolve(IDynamicMesh dynamic) {
        var topology = dynamic.topology();
        long revision = dynamic.revision();
        var cached = current;
        if (cached != null && cached.topology() == topology.topology()
                && cached.geometryRevision() == revision) {
            return cached;
        }
        // no CPU geometry means it only exists on the GPU; the backend binds that buffer instead
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
