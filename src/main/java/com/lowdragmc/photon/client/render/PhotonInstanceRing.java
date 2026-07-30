package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;

/**
 * Per-emitter ring buffer for instanced draw data, bound as a {@code TEXEL_BUFFER} uniform
 * (RGBA8 texels; the shader bit-reassembles floats — see {@code photon:particle.glsl}). Texel
 * uniforms must bind the ENTIRE buffer, so this cannot be a slice of a shared ring: each use site
 * owns one. Triple-buffered + fenced like the vertex ring ({@link PhotonBufferCache}); the drain
 * registers used rings and {@link #rotate()}s them at the frame boundary.
 */
public final class PhotonInstanceRing implements AutoCloseable {

    private static final int USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE;

    @Nullable
    private MappableRingBuffer buffer;

    /** Copy the staging floats (position → limit) into this frame's buffer and return it (whole
     *  buffer — capacity may exceed the written range; fetches are bounded by instance count). */
    public GpuBuffer write(FloatBuffer staging) {
        var bytes = staging.remaining() * Float.BYTES;
        var ring = ensureCapacity(bytes);
        try (GpuBuffer.MappedView view = map(ring, bytes)) {
            view.data().asFloatBuffer().put(staging);
        }
        return ring.currentBuffer();
    }

    /**
     * The reordering write: emit {@code count} fixed-size records, taking record {@code order[i]} from
     * {@code staging} as output record {@code i}. Used for {@code SortMode.DISTANCE} on the instanced
     * path — a whole-record permutation keeps the divisor attribute tail and every {@code gl_InstanceID}
     * -indexed side buffer (PhotonData/PhotonCustomData, permuted with the same order) aligned.
     * <p>
     * One memcpy per record straight into the mapped buffer: no intermediate staging copy, so the total
     * bytes moved match the sequential {@link #write(FloatBuffer)}. {@code staging} must be flipped
     * (position 0) — records are addressed absolutely from its start. {@code order} may be LONGER than
     * {@code count}: it comes from {@link PhotonDistanceSort}'s reused scratch, so the count is explicit
     * rather than {@code order.length}.
     */
    public GpuBuffer write(FloatBuffer staging, int[] order, int count, int recordFloats) {
        if (recordFloats <= 0) {
            return write(staging);
        }
        var recordBytes = recordFloats * Float.BYTES;
        var bytes = count * recordBytes;
        var ring = ensureCapacity(bytes);
        try (GpuBuffer.MappedView view = map(ring, bytes)) {
            var source = MemoryUtil.memAddress(staging);
            var destination = MemoryUtil.memAddress(view.data());
            for (int i = 0; i < count; i++) {
                MemoryUtil.memCopy(source + (long) order[i] * recordBytes,
                        destination + (long) i * recordBytes, recordBytes);
            }
        }
        return ring.currentBuffer();
    }

    private MappableRingBuffer ensureCapacity(int bytes) {
        var ring = buffer;
        if (ring == null || ring.size() < bytes) {
            if (ring != null) {
                ring.close();
            }
            // 1.5x headroom: growing trails/bursts would otherwise reallocate every frame
            var capacity = ring == null ? bytes : Math.max(bytes, ring.size() + (ring.size() >> 1));
            ring = new MappableRingBuffer(() -> "Photon instance data", USAGE, capacity);
            buffer = ring;
        }
        return ring;
    }

    private static GpuBuffer.MappedView map(MappableRingBuffer ring, int bytes) {
        return RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ring.currentBuffer().slice(0, bytes), false, true);
    }

    public void rotate() {
        if (buffer != null) {
            buffer.rotate();
        }
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
