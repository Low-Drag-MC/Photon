package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * Geometry that changes while it is being drawn — a skinned model mid-animation, a mesh another mod
 * deformed on the GPU. Implement this and hand it over as a {@link DynamicMeshSource}; from there on it
 * is an ordinary model source.
 *
 * <p>The contract: {@link #topology()} is the unchanging half and should be the same instance every
 * frame; {@link #revision()} changes when and only when the geometry's contents do, and is the entire
 * basis on which work is skipped; {@link #geometry()} or {@link #glBuffer()} supplies that revision's
 * positions and normals in {@link PhotonMesh#geometry()}'s layout, in the topology's vertex order.</p>
 *
 * <p>A GPU provider's buffer is bound directly as Photon's geometry stream — no copy, no readback. A
 * buffer object is untyped in GL, so an SSBO a compute pass wrote is bindable as a vertex buffer.</p>
 *
 * <p>⚠️ The GL path cannot feed an emission shape: that samples vertices on the CPU, and the point of
 * the GL path is that they never come back. A provider wanting both supplies {@link #geometry()} too.</p>
 *
 * <p>⚠️ Photon binds {@link #glBuffer()} and reads it later in the frame. The provider must keep that
 * buffer alive and that offset meaning the same vertices while it keeps answering with the same
 * revision — a suballocator recycling a slice under us produces another model's vertices, silently.
 * {@link #onDrawn()} is the hook to pin the allocation with.</p>
 */
@OnlyIn(Dist.CLIENT)
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
     * <p>⚠️ Deriving rebuilds a weld map over the whole mesh per revision. ⚠️ Ignored on the GL path,
     * which supplies positions and normals only — a pass drawn with tangents then gets the rest pose's
     * frame.</p>
     */
    @Nullable
    default float[] tangents() {
        return null;
    }

    /** A GL buffer holding this revision's geometry stream, or {@code 0} for none. */
    default int glBuffer() {
        return 0;
    }

    default long glByteOffset() {
        return 0L;
    }

    /** Whether {@link #glBuffer()} packs each normal as four signed bytes (16B a vertex) rather than
     *  three floats. Only the attribute pointer changes; the shader sees a vec3 either way. */
    default boolean glPackedNormals() {
        return false;
    }

    /** Called once per frame Photon drew this mesh. "Keep my allocation" — the provider's own draw is
     *  not evidence, since an emitter outlives whatever spawned it. */
    default void onDrawn() {
    }
}
