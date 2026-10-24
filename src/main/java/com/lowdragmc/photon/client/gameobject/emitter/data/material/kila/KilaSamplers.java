package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

/**
 * The GPU sampler a {@link KilaTexture} reads through: its filter, and its wrap on each axis. No mipmaps, as the shared
 * samplers Kila uses otherwise. {@link AddressMode} has no mirrored repeat, so a mirroring axis samples clamped and
 * {@code kila_wrap} folds its coordinate ({@link KilaTexture#FLAG_MIRROR_U}): the same on either backend.
 */
public final class KilaSamplers {
    private KilaSamplers() {
    }

    public static GpuSampler of(KilaTexture texture) {
        var filter = texture.nearest ? FilterMode.NEAREST : FilterMode.LINEAR;
        return RenderSystem.getSamplerCache().getSampler(address(texture.wrapU), address(texture.wrapV), filter, filter,
                false);
    }

    /** Clamp for a baked gradient: its ends must not wrap round to each other. */
    public static GpuSampler gradient() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    private static AddressMode address(KilaTexture.Wrap wrap) {
        return wrap.sampled() == KilaTexture.Wrap.REPEAT ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
    }
}
