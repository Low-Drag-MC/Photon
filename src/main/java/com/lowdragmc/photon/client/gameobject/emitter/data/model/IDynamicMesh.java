package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.jetbrains.annotations.Nullable;

/**
 * Geometry that changes while it is being drawn — a skinned model mid-animation, a mesh another mod
 * deformed on the GPU. Implement this and hand it over as a {@link DynamicMeshSource}; from there on it
 * is an ordinary model source.
 *
 * <p>The contract: {@link #topology()} is the unchanging half and should be the same instance every
 * frame; {@link #revision()} changes when and only when the geometry's contents do, and is the entire
 * basis on which work is skipped; {@link #geometry()} or {@link #gpuGeometry()} supplies that revision's
 * positions and normals in {@link PhotonMesh#geometry()}'s layout, in the topology's vertex order.</p>
 *
 * <p>{@link #gpuGeometry()} is a backend-neutral {@link GpuBufferSlice} created with {@code USAGE_VERTEX}.
 * ⚠️ No renderer binds it yet, and it cannot feed an emission shape; supply {@link #geometry()} too. The slice
 * must stay valid while the revision is unchanged ({@link #onDrawn()} pins it).</p>
 */
public interface IDynamicMesh {

    /** Vertex count, indices, UVs, shade, sprite bounds, and a rest pose. Returning a new instance costs
     *  every consumer its cached buffers. */
    PhotonMesh topology();

    /** Anything monotonic. ⚠️ Start at 1: {@code 0} is the topology's own rest pose. */
    long revision();

    /** This revision's positions and normals, or null when the data only exists on the GPU.
     *  Photon copies during the upload, so a reused scratch array is expected. */
    @Nullable
    default float[] geometry() {
        return null;
    }

    /**
     * This revision's deformed tangents, or null to derive them from the UVs.
     *
     * <p>⚠️ Deriving rebuilds a weld map over the whole mesh per revision. ⚠️ Ignored on the GPU path,
     * which supplies positions and normals only — a pass drawn with tangents then gets the rest pose's
     * frame.</p>
     */
    @Nullable
    default float[] tangents() {
        return null;
    }

    @Nullable
    default GpuBufferSlice gpuGeometry() {
        return null;
    }

    /** Whether {@link #gpuGeometry()} packs normals as four signed bytes (16B a vertex) instead of floats. */
    default boolean gpuPackedNormals() {
        return false;
    }

    /** Called once per frame Photon drew this mesh. "Keep my allocation" — the provider's own draw is
     *  not evidence, since an emitter outlives whatever spawned it. */
    default void onDrawn() {
    }
}
