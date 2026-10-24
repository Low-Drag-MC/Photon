package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4fc;

/**
 * One float texture a light pass draws into, kept between frames and reallocated on a size change. The frame's own
 * colour and depth, which the passes read as they stood before the lights went on, are {@code PhotonTextureCopy}s.
 * Render thread only.
 */
final class LightTarget {
    /** Sampled, drawn into, and cleared ({@code clearColorTexture} needs the copy-destination bit). */
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_DST;

    private final String label;
    private final GpuFormat format;
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    /** The size an allocation last failed at: not tried again until the size changes. */
    private long failedSize = -1;

    LightTarget(String label, GpuFormat format) {
        this.label = label;
        this.format = format;
    }

    /** Sized {@code width x height}; null when it could not be allocated (reported once). */
    @Nullable
    GpuTextureView ensure(int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height) {
            return view;
        }
        release();
        long size = ((long) width << 32) | height;
        if (size == failedSize) return null;
        var device = RenderSystem.getDevice();
        try {
            int w = width, h = height;
            texture = device.createTexture(() -> label, USAGE, format, w, h, 1, 1);
        } catch (RuntimeException e) {
            failedSize = size;
            Photon.LOGGER.error("Photon could not allocate {} ({}x{}): dynamic lights are off", label, width, height, e);
            return null;
        }
        failedSize = -1;
        view = device.createTextureView(texture);
        return view;
    }

    /** Clear to {@code color}; the target must exist. */
    void clear(Vector4fc color) {
        if (texture != null) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, color);
    }

    @Nullable
    GpuTextureView view() {
        return view;
    }

    void release() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
    }
}
