package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33C;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The GPU sampler a {@link KilaTexture} reads through: its filter, and its wrap on each axis.
 * <p>
 * 26.1's {@link AddressMode} has no mirrored repeat. A mirroring slot gets a sampler of its own — never one from the
 * shared {@code SamplerCache}, which other draws use — with GL's {@code GL_MIRRORED_REPEAT} set on it directly: the
 * one raw GL call here, and only for the GL backend (anything else falls back to repeat).
 */
public final class KilaSamplers {
    /** The mirroring samplers, by (u, v, nearest); made once, kept for the session (at most ten). */
    private static final Map<Integer, GpuSampler> MIRRORED = new HashMap<>();

    private KilaSamplers() {
    }

    public static GpuSampler of(KilaTexture texture) {
        var filter = texture.nearest ? FilterMode.NEAREST : FilterMode.LINEAR;
        boolean mirrorU = texture.wrapU == KilaTexture.Wrap.MIRROR;
        boolean mirrorV = texture.wrapV == KilaTexture.Wrap.MIRROR;
        if (!mirrorU && !mirrorV) {
            return RenderSystem.getSamplerCache().getSampler(address(texture.wrapU), address(texture.wrapV), filter,
                    filter, false);
        }
        int key = (mirrorU ? 1 : 0) | (mirrorV ? 2 : 0) | (texture.nearest ? 4 : 0)
                | (!mirrorU && texture.wrapU != KilaTexture.Wrap.REPEAT ? 8 : 0)
                | (!mirrorV && texture.wrapV != KilaTexture.Wrap.REPEAT ? 16 : 0);
        return MIRRORED.computeIfAbsent(key, k -> mirrored(texture, filter, mirrorU, mirrorV));
    }

    /** Clamp for a baked gradient: its ends must not wrap round to each other. */
    public static GpuSampler gradient() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    private static AddressMode address(KilaTexture.Wrap wrap) {
        // CLIP clamps; the shader makes it transparent past the edge
        return wrap == KilaTexture.Wrap.REPEAT || wrap == KilaTexture.Wrap.MIRROR ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
    }

    private static GpuSampler mirrored(KilaTexture texture, FilterMode filter, boolean mirrorU, boolean mirrorV) {
        // no mipmaps, as the shared samplers Kila uses otherwise: max lod 0
        var sampler = RenderSystem.getDevice().createSampler(address(texture.wrapU), address(texture.wrapV), filter, filter,
                1, OptionalDouble.of(0));
        if (sampler instanceof GlSampler gl) {
            if (mirrorU) GL33C.glSamplerParameteri(gl.getId(), GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
            if (mirrorV) GL33C.glSamplerParameteri(gl.getId(), GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
        }
        return sampler;
    }
}
