package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code PhotonMaterial} std140 uniform block (DiscardThreshold/HDR/HDRMode/PixelBits — the
 * 1.21 per-material shader uniforms). Unlike KilaGraph's live-updatable {@code MaterialUniformBuffer},
 * Photon's values are baked into the RenderType identity (the RenderType cache key includes them), so
 * each distinct value combo gets ONE immutable GPU buffer, uploaded once at creation. Photon's
 * {@code RenderTypeMixin} binds the associated slice right before {@code RenderPass.drawIndexed}.
 */
public final class PhotonMaterialUniforms {

    /** std140 layout mirrors the fsh PhotonMaterial block (1.21 member names): vec4 HDR;
     *  vec4 U_SpriteUV (RAW corners u0,v0,u1,v1 — 1.21 semantics); float DiscardThreshold;
     *  int HDRMode; float Bits; (+pad) = 48 B. */
    public record Values(float hdrR, float hdrG, float hdrB, float hdrA,
                         float spriteU, float spriteV, float spriteUScale, float spriteVScale,
                         float discardThreshold, float hdrMode, float pixelBits) {
        public static final Values DEFAULT = new Values(0, 0, 0, 1, 0, 0, 1, 1, 0.1f, 0, 0);

        public static Values of(Vector4f hdr, float discardThreshold, float hdrMode, float pixelBits) {
            return new Values(hdr.x, hdr.y, hdr.z, hdr.w, 0, 0, 1, 1, discardThreshold, hdrMode, pixelBits);
        }

        /** Sprite-atlas variant: [0,1] particle UVs remap into the sprite's atlas window. */
        public static Values ofSprite(Vector4f hdr, float discardThreshold, float hdrMode,
                                      float u0, float v0, float u1, float v1) {
            return new Values(hdr.x, hdr.y, hdr.z, hdr.w, u0, v0, u1, v1, discardThreshold, hdrMode, 0);
        }
    }

    private static final int STD140_SIZE = 48;
    private static final Map<Values, GpuBufferSlice> BUFFERS = new ConcurrentHashMap<>();
    /** RenderType identity → its material uniform slice; entries live as long as the RenderType cache. */
    private static final Map<RenderType, GpuBufferSlice> BY_RENDER_TYPE = new ConcurrentHashMap<>();

    private PhotonMaterialUniforms() {
    }

    /** Associate a freshly created RenderType with its (already or lazily uploaded) uniform buffer.
     *  Must run on the render thread outside an open render pass (extraction qualifies). */
    public static void associate(RenderType renderType, Values values) {
        BY_RENDER_TYPE.put(renderType, BUFFERS.computeIfAbsent(values, PhotonMaterialUniforms::upload));
    }

    /** The slice to bind as {@code PhotonMaterial} for this RenderType's draw, or null for foreign types. */
    @Nullable
    public static GpuBufferSlice sliceFor(RenderType renderType) {
        return BY_RENDER_TYPE.get(renderType);
    }

    /** The (lazily uploaded) slice for raw values — instanced draws bind without a RenderType. */
    public static GpuBufferSlice sliceFor(Values values) {
        return BUFFERS.computeIfAbsent(values, PhotonMaterialUniforms::upload);
    }

    private static GpuBufferSlice upload(Values v) {
        RenderSystem.assertOnRenderThread();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "PhotonMaterial UBO",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                STD140_SIZE);
        var bytes = MemoryUtil.memAlloc(STD140_SIZE);
        try {
            Std140Builder.intoBuffer(bytes)
                    .putVec4(v.hdrR(), v.hdrG(), v.hdrB(), v.hdrA())
                    .putVec4(v.spriteU(), v.spriteV(), v.spriteUScale(), v.spriteVScale())
                    .putFloat(v.discardThreshold())
                    .putInt((int) v.hdrMode())
                    .putFloat(v.pixelBits());
            bytes.rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        return buffer.slice();
    }
}
