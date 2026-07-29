package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Vertex ring buffer for Photon's own draw slot (the vanilla {@code ParticleBufferCache} pattern):
 * a triple-buffered {@link MappableRingBuffer} written once per frame via a mapped view — no
 * per-frame GPU buffer allocation and no immediate-buffer sync stalls; {@link #rotate()} fences the
 * frame's buffer and moves on. Lazily (re)sized to the frame's need.
 */
public final class PhotonBufferCache implements AutoCloseable {

    @Nullable
    private MappableRingBuffer ringBuffer;

    /** Copy the given vertex blocks back-to-back into this frame's buffer. */
    public void write(List<ByteBuffer> blocks, int totalBytes) {
        if (ringBuffer == null || ringBuffer.size() < totalBytes) {
            if (ringBuffer != null) {
                ringBuffer.close();
            }
            ringBuffer = new MappableRingBuffer(() -> "Photon FX Vertices",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_VERTEX, totalBytes);
        }
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ringBuffer.currentBuffer().slice(0, totalBytes), false, true)) {
            var data = view.data();
            for (var block : blocks) {
                data.put(block);
            }
        }
    }

    public GpuBuffer get() {
        if (ringBuffer == null) {
            throw new IllegalStateException("write() must run before get()");
        }
        return ringBuffer.currentBuffer();
    }

    public void rotate() {
        if (ringBuffer != null) {
            ringBuffer.rotate();
        }
    }

    @Override
    public void close() {
        if (ringBuffer != null) {
            ringBuffer.close();
            ringBuffer = null;
        }
    }
}
