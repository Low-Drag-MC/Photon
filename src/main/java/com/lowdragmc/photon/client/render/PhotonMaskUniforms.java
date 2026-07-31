package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code PhotonMask} std140 block of the CustomMask sub-pass — the flat id a flagged emitter
 * writes, plus its alpha-clip cutoff.
 * <p>
 * Value-keyed and shared, like {@link PhotonMaterialUniforms}: there are only ever as many distinct
 * blocks as there are (group, cutoff) pairs in the project, so a frame allocates nothing.
 */
public final class PhotonMaskUniforms {

    /** {@code maskValue} is the group id in 0..1 ({@code id / 255}); {@code alphaCutoff} 0 = no clip. */
    public record Values(float maskValue, float alphaCutoff) {
    }

    private static final int STD140_SIZE = 16;
    private static final Map<Values, GpuBufferSlice> BUFFERS = new HashMap<>();

    private PhotonMaskUniforms() {
    }

    /** The block a job's {@code MaskWrite} needs — the only shape callers actually have. */
    public static GpuBufferSlice sliceFor(PhotonWorldRenderState.MaskWrite mask) {
        return sliceFor(new Values(mask.value(), mask.alphaCutoff()));
    }

    public static GpuBufferSlice sliceFor(Values values) {
        return BUFFERS.computeIfAbsent(values, PhotonMaskUniforms::upload);
    }

    private static GpuBufferSlice upload(Values values) {
        RenderSystem.assertOnRenderThread();
        var buffer = RenderSystem.getDevice().createBuffer(() -> "PhotonMask UBO",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, STD140_SIZE);
        // calloc, not alloc: the block is padded to 16 and only 8 bytes are written — the tail is
        // uploaded too, so it must not be whatever the allocator handed back
        var bytes = MemoryUtil.memCalloc(STD140_SIZE);
        try {
            Std140Builder.intoBuffer(bytes)
                    .putFloat(values.maskValue())
                    .putFloat(values.alphaCutoff());
            bytes.rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        return buffer.slice();
    }
}
