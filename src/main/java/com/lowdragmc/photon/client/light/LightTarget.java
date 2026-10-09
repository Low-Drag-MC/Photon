package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonFloatTextures;
import com.lowdragmc.photon.client.render.PhotonFramebufferBlit;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * One texture a light pass draws into or copies to, kept between frames and reallocated on a size change.
 * <p>
 * The float kinds go through {@link PhotonFloatTextures} (26.1 has no float formats; GL backend only, like the
 * rest of Photon's render layer). A {@link Kind#COPY_OF_SOURCE} target takes the format of whatever it copies
 * — a depth blit needs the formats to match exactly — and is filled by {@link PhotonFramebufferBlit}, never by
 * the engine's {@code copyTextureToTexture}: that needs {@code COPY_SRC} on a source Photon does not own (the
 * editor's PIP textures) and leaves the scissor test off behind it. Render thread only.
 */
final class LightTarget {
    enum Kind { RGBA16F, R16F, COPY_OF_SOURCE }

    /** Sampled, drawn into, and cleared ({@code clearColorTexture} needs the copy-destination bit). */
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_DST;

    private final String label;
    private final Kind kind;
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    @Nullable
    private TextureFormat format;
    /** The size an allocation last failed at: not tried again until the size changes. */
    private long failedSize = -1;

    LightTarget(String label, Kind kind) {
        this.label = label;
        this.kind = kind;
    }

    /** Sized {@code width x height}; null when a float texture could not be allocated (reported once). */
    @Nullable
    GpuTextureView ensure(int width, int height) {
        return ensure(width, height, null);
    }

    @Nullable
    private GpuTextureView ensure(int width, int height, @Nullable TextureFormat sourceFormat) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height
                && (sourceFormat == null || sourceFormat == format)) {
            return view;
        }
        release();
        long size = ((long) width << 32) | height;
        if (size == failedSize) return null;
        var device = RenderSystem.getDevice();
        texture = switch (kind) {
            case RGBA16F -> PhotonFloatTextures.createRgba16f(label, USAGE, width, height);
            case R16F -> PhotonFloatTextures.createHalfFloat(label, USAGE, width, height,
                    GL30.GL_R16F, GL11.GL_RED, TextureFormat.RGBA8);
            case COPY_OF_SOURCE -> device.createTexture(() -> label, USAGE,
                    sourceFormat == null ? TextureFormat.RGBA8 : sourceFormat, width, height, 1, 1);
        };
        if (texture == null) {
            failedSize = size;
            Photon.LOGGER.error("Photon could not allocate {} ({}x{}): dynamic lights are off", label, width, height);
            return null;
        }
        failedSize = -1;
        format = sourceFormat;
        view = device.createTextureView(texture);
        return view;
    }

    /** Sized and formatted as {@code source}, its content left as it is. */
    @Nullable
    GpuTextureView ensureLike(GpuTextureView source) {
        var from = source.texture();
        return ensure(from.getWidth(0), from.getHeight(0), from.getFormat());
    }

    /** A copy of {@code source} — its colour, or its depth when the source is a depth texture. */
    @Nullable
    GpuTextureView copyOf(GpuTextureView source) {
        var from = source.texture();
        int width = from.getWidth(0), height = from.getHeight(0);
        var to = ensure(width, height, from.getFormat());
        if (to == null) return null;
        if (from.getFormat().hasDepthAspect()) {
            PhotonFramebufferBlit.depth(from, to.texture(), width, height);
        } else {
            PhotonFramebufferBlit.color(from, to.texture(), width, height);
        }
        return to;
    }

    /** Clear to {@code argb}; the target must exist. */
    void clear(int argb) {
        if (texture != null) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, argb);
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
        format = null;
    }
}
