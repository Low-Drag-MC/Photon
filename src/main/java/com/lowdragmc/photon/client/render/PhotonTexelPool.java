package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pooled per-frame texel buffers for instanced side data. Texel buffers bind whole, so they cannot come from
 * {@code TransientMemory}; {@code writeToBuffer} is ordered with the commands, so reuse needs no fences.
 */
public final class PhotonTexelPool {

    private static final int USAGE = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;
    private static final long MIN_SIZE = 4096;
    private static final int IDLE_FRAMES = 240;

    private record Pooled(GpuBuffer buffer, long lastUsedFrame) {
    }

    private static final TreeMap<Long, ArrayDeque<Pooled>> FREE = new TreeMap<>();
    private static final List<GpuBuffer> LEASED = new ArrayList<>();
    private static long frame;

    private PhotonTexelPool() {
    }

    /** Valid until the frame boundary; the buffer may be larger than the data. */
    public static GpuBuffer upload(String label, ByteBuffer data) {
        RenderSystem.assertOnRenderThread();
        var size = bucketSize(data.remaining());
        var buffer = lease(label, size);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, data.remaining()), data);
        return buffer;
    }

    private static long bucketSize(long bytes) {
        return Math.max(MIN_SIZE, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
    }

    private static GpuBuffer lease(String label, long size) {
        var free = FREE.get(size);
        var pooled = free == null ? null : free.poll();
        var buffer = pooled != null && !pooled.buffer().isClosed() ? pooled.buffer()
                : RenderSystem.getDevice().createBuffer(() -> "Photon " + label + " texels", USAGE, size);
        LEASED.add(buffer);
        return buffer;
    }

    public static void endFrame() {
        frame++;
        for (var buffer : LEASED) {
            FREE.computeIfAbsent(buffer.size(), key -> new ArrayDeque<>()).add(new Pooled(buffer, frame));
        }
        LEASED.clear();
        for (Map.Entry<Long, ArrayDeque<Pooled>> bucket : FREE.entrySet()) {
            bucket.getValue().removeIf(pooled -> {
                if (frame - pooled.lastUsedFrame() > IDLE_FRAMES) {
                    pooled.buffer().close();
                    return true;
                }
                return false;
            });
        }
    }
}
