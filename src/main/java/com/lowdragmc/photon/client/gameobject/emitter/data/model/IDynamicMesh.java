package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Geometry that changes while it is being drawn</b> — a skinned model mid-animation, a morph target, a
 * mesh some other mod deformed on the GPU. Implement this outside Photon and hand it over as a
 * {@link DynamicMeshSource}; from there on it is an ordinary model source, so it feeds the Model render
 * mode and the mesh emission shape through exactly the paths a static model does.
 *
 * <h2>The contract in three sentences</h2>
 *
 * <ol>
 *   <li>{@link #topology()} is the mesh's <b>unchanging</b> half — the index buffer, the UVs, the shade,
 *       the sprite bounds, the vertex count. Return the same instance every frame; return a different one
 *       only when the model itself was replaced, which costs a full buffer rebuild.</li>
 *   <li>{@link #revision()} must change whenever the geometry's <b>contents</b> change and must not
 *       change when they do not. It is the entire basis on which work is skipped: an unchanged revision
 *       means no upload, no re-derive, nothing.</li>
 *   <li>{@link #geometry()} or {@link #glBuffer()} supplies that revision's positions and normals, in
 *       the layout {@link PhotonMesh#geometry()} uses: {@link PhotonMesh#FLOATS_PER_GEOMETRY} floats a
 *       vertex, {@code x, y, z, nx, ny, nz}, {@link #topology()}{@code .vertexCount()} of them, in the
 *       same vertex order as the topology.</li>
 * </ol>
 *
 * <h2>CPU floats or a GL buffer</h2>
 *
 * <p>A provider that deforms on the CPU returns {@link #geometry()} and Photon uploads it. A provider
 * that deformed on the <b>GPU</b> — a compute pass, say — returns {@link #glBuffer()} instead, and Photon
 * binds that buffer as its geometry stream: <b>no copy, no readback, no shader of ours involved</b>. A
 * buffer object is untyped in GL, so the SSBO a compute pass wrote is directly bindable as a vertex
 * buffer; all it has to hold is the layout above, at {@link #glByteOffset()}.
 *
 * <p>⚠️ <b>The GL path cannot feed an emission shape.</b> Sampling a surface for spawn positions reads
 * vertices on the CPU, and the whole point of the GL path is that they never come back. A provider that
 * wants both supplies {@link #geometry()} as well; one that returns only a buffer renders, and emits
 * nothing.
 *
 * <p>⚠️ <b>Lifetime.</b> Photon binds {@link #glBuffer()} and reads it later in the frame, during its own
 * render pass. The provider must therefore keep that buffer alive and keep that offset meaning the same
 * vertices for as long as it keeps answering with the same revision — a suballocator that recycles a
 * slice while Photon still points at it produces another model's vertices, silently, at some camera
 * angles only. {@link #onDrawn()} is called every frame Photon actually draws from it, which is the hook
 * to pin the allocation with.
 */
@OnlyIn(Dist.CLIENT)
public interface IDynamicMesh {

    /**
     * The unchanging half of the mesh: vertex count, indices, UVs, shade, sprite bounds. The geometry
     * stream inside it is the rest pose (or any pose) — it is used when no revision has been supplied
     * yet, so it should be a sensible one rather than zeroes.
     *
     * <p>Build it with {@link PhotonMesh.Builder} once and hold on to it. Returning a new instance is
     * how a provider says "the model changed", and it costs every consumer its cached buffers.</p>
     */
    PhotonMesh topology();

    /**
     * A counter that changes with the geometry's contents. Anything monotonic works — a frame number, a
     * pose hash, an animation sample index. {@code 0} is the topology's own rest pose, so a provider
     * whose first real revision is also {@code 0} would be mistaken for "nothing supplied yet"; start at
     * 1.
     */
    long revision();

    /**
     * This revision's positions and normals for the CPU to upload, or {@code null} when the data only
     * exists on the GPU (see {@link #glBuffer()}). Photon copies out of this array during the upload and
     * does not retain it, so one reused scratch array per provider is correct and expected.
     */
    @Nullable
    default float[] geometry() {
        return null;
    }

    /**
     * This revision's deformed tangents ({@link PhotonMesh#FLOATS_PER_TANGENT} floats a vertex), or
     * {@code null} to let Photon derive them from the UVs.
     *
     * <p>Only read when the emitter's Tangent setting is on. ⚠️ Deriving rebuilds a weld map over the
     * whole mesh <i>per revision</i>, which is not something to do once a frame — a provider that
     * expects to be drawn with tangents should carry the bind-pose tangents through its own deformation
     * and return them here, which is also the more correct answer.</p>
     *
     * <p>⚠️ <b>Ignored on the GL path.</b> A provider supplying {@link #glBuffer()} supplies positions and
     * normals only, so a pass drawn with tangents uses the ones derived from {@link #topology()} — the
     * rest pose's frame on a deformed mesh. Wrong, in a way that shows up as normal-mapped lighting that
     * does not follow the animation, and not something this side can detect. Tangents in a foreign buffer
     * are the thing to extend this interface with if that matters.</p>
     */
    @Nullable
    default float[] tangents() {
        return null;
    }

    /**
     * A GL buffer holding this revision's geometry stream, or {@code 0} for none. Bound directly as
     * Photon's geometry vertex buffer — see the class javadoc for the layout and the lifetime it has to
     * outlive.
     */
    default int glBuffer() {
        return 0;
    }

    /** Byte offset of this mesh's first vertex inside {@link #glBuffer()}, for a shared/suballocated buffer. */
    default long glByteOffset() {
        return 0L;
    }

    /**
     * Whether {@link #glBuffer()} stores each normal as four signed normalized <b>bytes</b> rather than
     * three floats — 16 bytes a vertex instead of 24, the layout a compute skinning pass usually already
     * writes. Only the vertex-attribute pointer changes; the shader sees a {@code vec3} either way.
     *
     * <p>The fourth byte is padding and is ignored. Has no effect on {@link #geometry()}, which is
     * always floats.</p>
     */
    default boolean glPackedNormals() {
        return false;
    }

    /**
     * Called once per frame in which Photon drew this mesh, before it reads the data. A provider backed
     * by a pool or a cache should treat it as "keep my allocation": Photon has no other way to say it is
     * still interested, and the provider's own draw is not evidence — an emitter can outlive whatever
     * was being drawn when it started.
     */
    default void onDrawn() {
    }
}
