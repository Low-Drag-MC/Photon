package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.Map;

/**
 * Index buffers that wind every triangle the other way, so a back-faces-first draw can show its back faces through a
 * pipeline that culls them: a pipeline only says whether to cull, never which face, and the same triangles wound
 * the other way have their back faces in front. Quads and triangles only (a strip draws unculled instead); the pass
 * goes through {@link PhotonPipelines#reversedWinding}, which turns {@code gl_FrontFacing} the right way round.
 * <p>
 * {@link #sequential} mirrors {@code RenderSystem.getSequentialBuffer}'s patterns; {@link #reverse} is for a mesh's
 * own indices, made where the mesh's indices are. Render thread, outside any pass (a first use or a growth
 * uploads). Always {@code INT} indices.
 */
public final class PhotonReversedIndices {

    private record Sequential(GpuBuffer buffer, int indexCount) {
    }

    private static final Map<PrimitiveTopology, Sequential> SEQUENTIAL = new EnumMap<>(PrimitiveTopology.class);

    private PhotonReversedIndices() {
    }

    /**
     * The reversed form of the sequential indices of {@code topology}, holding at least {@code indexCount}; null for
     * a topology with nothing to cull.
     */
    @Nullable
    public static GpuBuffer sequential(PrimitiveTopology topology, int indexCount) {
        if (topology != PrimitiveTopology.QUADS && topology != PrimitiveTopology.TRIANGLES) {
            return null;
        }
        var current = SEQUENTIAL.get(topology);
        if (current != null && current.indexCount() >= indexCount) {
            return current.buffer();
        }
        int perPrimitive = topology == PrimitiveTopology.QUADS ? 6 : 3;
        int primitives = Math.max(1, Integer.highestOneBit(Math.max(1, (indexCount + perPrimitive - 1) / perPrimitive
                - 1)) << 1);
        var indices = MemoryUtil.memAllocInt(primitives * perPrimitive);
        try {
            for (int p = 0; p < primitives; p++) {
                if (topology == PrimitiveTopology.QUADS) {
                    // RenderSystem's quads are (0, 1, 2), (2, 3, 0)
                    int v = p * 4;
                    indices.put(v).put(v + 2).put(v + 1).put(v + 2).put(v).put(v + 3);
                } else {
                    int v = p * 3;
                    indices.put(v).put(v + 2).put(v + 1);
                }
            }
            indices.flip();
            var buffer = RenderSystem.getDevice().createBuffer(() -> "Photon reversed " + topology + " indices",
                    GpuBuffer.USAGE_INDEX, MemoryUtil.memByteBuffer(indices));
            if (current != null) {
                // a pass recorded earlier this frame may still read it
                PhotonWorldRenderState.closeAtFrameEnd(current.buffer());
            }
            SEQUENTIAL.put(topology, new Sequential(buffer, primitives * perPrimitive));
            return buffer;
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    /** {@code triangles} (a triangle list) wound the other way, as a new index buffer the caller owns. */
    public static GpuBuffer reverse(String label, IntBuffer triangles) {
        var count = triangles.remaining() / 3 * 3;
        var reversed = MemoryUtil.memAllocInt(count);
        try {
            int base = triangles.position();
            for (int i = 0; i < count; i += 3) {
                reversed.put(triangles.get(base + i)).put(triangles.get(base + i + 2)).put(triangles.get(base + i + 1));
            }
            reversed.flip();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_INDEX,
                    MemoryUtil.memByteBuffer(reversed));
        } finally {
            MemoryUtil.memFree(reversed);
        }
    }
}
