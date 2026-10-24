package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.function.Consumer;

/**
 * A light list the passes read as a texel buffer of {@code RGBA32_FLOAT}s, re-uploaded with {@code writeToBuffer} and
 * grown on demand. A texel buffer binds whole, so it cannot come from transient memory; the update is ordered with the
 * commands on both backends, so the world and an editor scene may both write in one frame. The per-frame blocks are
 * transient instead ({@code PhotonUploads.block}), the constant ones {@link #constantBlock}. Render thread only,
 * outside any open render pass.
 */
final class LightBuffer {
    private final String label;
    @Nullable
    private GpuBuffer buffer;

    private LightBuffer(String label) {
        this.label = label;
    }

    static LightBuffer texels(String label) {
        return new LightBuffer(label);
    }

    /** A block that never changes, uploaded once. */
    static GpuBufferSlice constantBlock(String label, int size, Consumer<Std140Builder> fill) {
        var staging = MemoryUtil.memCalloc(size);
        try {
            fill.accept(Std140Builder.intoBuffer(staging));
            staging.rewind();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM, staging).slice();
        } finally {
            MemoryUtil.memFree(staging);
        }
    }

    /** {@code floats} from its position to its limit, padded to whole RGBA texels; at least one texel is kept. */
    GpuBuffer upload(FloatBuffer floats) {
        int count = floats.remaining();
        int bytes = Math.max(16, (count + 3) / 4 * 16);
        var target = ensure(bytes);
        if (count > 0) {
            var data = MemoryUtil.memByteBuffer(MemoryUtil.memAddress(floats), count * Float.BYTES);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(0, data.remaining()), data);
        }
        return target;
    }

    /** The buffer as it stands: at least one texel, so a pipeline that declares it always has something bound. */
    GpuBuffer current() {
        return ensure(16);
    }

    private GpuBuffer ensure(int bytes) {
        var current = buffer;
        if (current != null && current.size() >= bytes) return current;
        if (current != null) {
            // queued draws of this frame may still reference it
            PhotonWorldRenderState.closeAtFrameEnd(current);
        }
        long capacity = current == null ? bytes : Math.max(bytes, current.size() + (current.size() >> 1));
        buffer = RenderSystem.getDevice().createBuffer(() -> label,
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, capacity);
        if (current == null) {
            // a fresh buffer holds garbage, and a pipeline may bind it before anything was written
            var zeros = MemoryUtil.memCalloc(16);
            try {
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, 16), zeros);
            } finally {
                MemoryUtil.memFree(zeros);
            }
        }
        return buffer;
    }

    void release() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
