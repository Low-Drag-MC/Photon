package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code PhotonVatInfo} std140 block — how a {@code PHOTON_VAT} draw is told to read the baked pose
 * table bound as {@code PhotonVat}.
 *
 * <p>1.21 pushed these as two free-standing uniforms ({@code ivec2 PhotonVatSize}, {@code vec4
 * PhotonVatParams}) set per draw with {@code glUniform*}. 26.1 pipelines have no free uniforms at all —
 * everything is a UBO, a texel buffer or a sampler — so they become a block. Deduped by VALUE like
 * {@link PhotonMaterialUniforms}: an emitter's size/phase changes rarely (a clip swap, a phase slider),
 * and identical settings across emitters then share one immutable buffer.
 *
 * <p>Render thread only, and uploads happen between passes (creating a buffer inside an open render pass
 * is illegal).</p>
 */
public final class PhotonVatUniforms {

    /** std140: ivec2 Size (vertices a frame, frames) padded to 16, then vec4 Params
     *  (clip position, weight on the per-particle random, weight on the particle's t, blend) = 32 B.
     *  MIRRORED FROM the PHOTON_VAT block of {@code particle.glsl}. */
    public record Values(int vertexCount, int frames,
                         float base, float randomWeight, float tWeight, float blend) {

        public static Values of(int vertexCount, int frames, float[] phase) {
            return new Values(vertexCount, frames, phase[0], phase[1], phase[2], phase[3]);
        }
    }

    private static final int STD140_SIZE = 32;
    private static final Map<Values, GpuBufferSlice> BUFFERS = new ConcurrentHashMap<>();

    private PhotonVatUniforms() {
    }

    /** The (lazily uploaded) slice for these values. */
    public static GpuBufferSlice sliceFor(Values values) {
        return BUFFERS.computeIfAbsent(values, PhotonVatUniforms::upload);
    }

    private static GpuBufferSlice upload(Values v) {
        RenderSystem.assertOnRenderThread();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "PhotonVatInfo UBO",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                STD140_SIZE);
        var bytes = MemoryUtil.memAlloc(STD140_SIZE);
        try {
            Std140Builder.intoBuffer(bytes)
                    .putIVec2(v.vertexCount(), v.frames())
                    // putVec4 aligns to 16, so Params lands at 16 and the block ends at 32
                    .putVec4(v.base(), v.randomWeight(), v.tWeight(), v.blend());
            bytes.rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        return buffer.slice();
    }
}
