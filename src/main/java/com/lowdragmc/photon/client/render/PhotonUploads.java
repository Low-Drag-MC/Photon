package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TransientMemory;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Per-frame vertex data in {@link TransientMemory}: every slice is fresh, so an emitter baked for two views in one
 * frame never overwrites data still to be read. Texel buffers go through {@link PhotonTexelPool}.
 */
public final class PhotonUploads {

    private static final long ALIGNMENT = 16;

    private PhotonUploads() {
    }

    private static TransientMemory memory() {
        return RenderSystem.getDevice().createCommandEncoder().transientMemory();
    }

    public static GpuBufferSlice vertices(ByteBuffer data) {
        return memory().uploadGpu(data, ALIGNMENT, GpuBuffer.USAGE_VERTEX);
    }

    /** The blocks back to back in one slice. */
    public static GpuBufferSlice vertices(List<ByteBuffer> blocks) {
        return memory().uploadGpu(blocks, ALIGNMENT, GpuBuffer.USAGE_VERTEX);
    }

    public static GpuBufferSlice records(FloatBuffer staging) {
        return vertices(MemoryUtil.memByteBuffer(staging));
    }

    /**
     * Uploads record {@code order[i]} of {@code staging} as record {@code i}, for distance sorting. {@code order}
     * may be longer than {@code count}.
     */
    public static GpuBufferSlice records(FloatBuffer staging, @Nullable int[] order, int count, int recordFloats) {
        if (order == null || recordFloats <= 0) {
            return records(staging);
        }
        var recordBytes = (long) recordFloats * Float.BYTES;
        var bytes = count * recordBytes;
        try (var view = memory().allocateGpuMapped(bytes, ALIGNMENT, GpuBuffer.USAGE_VERTEX)) {
            var source = MemoryUtil.memAddress0(staging);
            var destination = MemoryUtil.memAddress(view.data());
            for (int i = 0; i < count; i++) {
                MemoryUtil.memCopy(source + order[i] * recordBytes, destination + i * recordBytes, recordBytes);
            }
            return view.slice();
        }
    }

    /** {@link #records(FloatBuffer, int[], int, int)} into a texel buffer. */
    public static GpuBuffer texels(String label, FloatBuffer staging, @Nullable int[] order, int count,
                                   int recordFloats) {
        if (order == null || recordFloats <= 0) {
            return PhotonTexelPool.upload(label, MemoryUtil.memByteBuffer(staging));
        }
        var recordBytes = recordFloats * Float.BYTES;
        var scratch = MemoryUtil.memAlloc(count * recordBytes);
        try {
            var source = MemoryUtil.memAddress0(staging);
            var destination = MemoryUtil.memAddress(scratch);
            for (int i = 0; i < count; i++) {
                MemoryUtil.memCopy(source + (long) order[i] * recordBytes,
                        destination + (long) i * recordBytes, recordBytes);
            }
            return PhotonTexelPool.upload(label, scratch);
        } finally {
            MemoryUtil.memFree(scratch);
        }
    }
}
