package com.lowdragmc.photon.client.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.function.Consumer;

/**
 * Per-frame vertex data and uniform blocks in {@link TransientMemory}: every slice is fresh, so an emitter baked for
 * two views in one frame never overwrites data still to be read. Texel buffers go through {@link PhotonTexelPool}.
 */
public final class PhotonUploads {

    private static final long ALIGNMENT = 16;

    private PhotonUploads() {
    }

    private static TransientMemory memory() {
        return RenderSystem.getDevice().createCommandEncoder().transientMemory();
    }

    /** A uniform block for this frame, aligned as a uniform binding requires. */
    public static GpuBufferSlice block(ByteBuffer data) {
        var alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        return memory().uploadGpu(data, alignment, GpuBuffer.USAGE_UNIFORM);
    }

    /** A std140 block of {@code size} bytes filled by {@code fill}, for this frame. */
    public static GpuBufferSlice block(int size, Consumer<Std140Builder> fill) {
        var staging = MemoryUtil.memCalloc(size);
        try {
            fill.accept(Std140Builder.intoBuffer(staging));
            staging.rewind();
            return block(staging);
        } finally {
            MemoryUtil.memFree(staging);
        }
    }

    public static GpuBufferSlice vertices(ByteBuffer data) {
        PhotonRenderStats.uploaded(data.remaining());
        return memory().uploadGpu(data, ALIGNMENT, GpuBuffer.USAGE_VERTEX);
    }

    /** The blocks back to back in one slice. */
    public static GpuBufferSlice vertices(List<ByteBuffer> blocks) {
        if (PhotonRenderStats.recording) {
            for (var block : blocks) PhotonRenderStats.uploaded(block.remaining());
        }
        return memory().uploadGpu(blocks, ALIGNMENT, GpuBuffer.USAGE_VERTEX);
    }

    /**
     * This frame's copy of the {@code count} instance records of {@code staging}, record {@code order[i]} as record
     * {@code i} (for distance sorting; {@code order} may be longer than {@code count}), uploaded later with the
     * instances drawn alike ({@link #records(List)}).
     */
    public static ByteBuffer stageRecords(FloatBuffer staging, @Nullable int[] order, int count, int recordFloats) {
        var recordBytes = recordFloats * Float.BYTES;
        var copy = PhotonRecordArena.allocate(count * recordBytes);
        var source = MemoryUtil.memAddress0(staging);
        var destination = MemoryUtil.memAddress(copy);
        if (order == null) {
            MemoryUtil.memCopy(source, destination, (long) count * recordBytes);
        } else {
            for (int i = 0; i < count; i++) {
                MemoryUtil.memCopy(source + (long) order[i] * recordBytes, destination + (long) i * recordBytes,
                        recordBytes);
            }
        }
        return copy;
    }

    /**
     * Staged records ({@link #stageRecords}) back to back in one slice, packed: an instance's index runs on across the
     * blocks, where {@link #vertices(List)} would pad each to the alignment.
     */
    public static GpuBufferSlice records(List<ByteBuffer> blocks) {
        long total = 0;
        for (var block : blocks) {
            total += block.remaining();
        }
        PhotonRenderStats.uploaded(total);
        try (var view = memory().allocateGpuMapped(total, ALIGNMENT, GpuBuffer.USAGE_VERTEX)) {
            var destination = MemoryUtil.memAddress(view.data());
            long offset = 0;
            for (var block : blocks) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(block), destination + offset, block.remaining());
                offset += block.remaining();
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
