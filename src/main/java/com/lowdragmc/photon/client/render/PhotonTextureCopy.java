package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * An owned copy of a texture Photon reads while drawing over it — the scene behind the FX, the frame a distortion
 * bends, the depth a light pass reconstructs from. Same size and format as its source, kept between frames and
 * reallocated when either changes. A source with {@code COPY_SRC} is copied ({@code copyTextureToTexture} needs the
 * formats to match on Vulkan); a colour source without it is drawn over with a sampling pass; a depth source without
 * it cannot be copied at all. Render thread, outside any pass.
 */
public final class PhotonTextureCopy implements AutoCloseable {

    private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private final String label;
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    private int width, height;
    @Nullable
    private GpuFormat format;
    private boolean warnedUncopyable;

    public PhotonTextureCopy(String label) {
        this.label = label;
    }

    /** Sized and formatted as {@code source}, its content left as it is. */
    public GpuTextureView ensureLike(GpuTexture source) {
        var w = source.getWidth(0);
        var h = source.getHeight(0);
        if (texture == null || w != width || h != height || source.getFormat() != format) {
            close();
            width = w;
            height = h;
            format = source.getFormat();
            var device = RenderSystem.getDevice();
            // a depth copy may be drawn into as the depth attachment (the light pass rebuilds a scaled one)
            texture = device.createTexture(() -> label, USAGE, format, w, h, 1, 1);
            view = device.createTextureView(texture);
        }
        return view;
    }

    /** A copy of {@code source}; null when it can be neither copied nor sampled (warned once). */
    @Nullable
    public GpuTextureView copyOf(GpuTextureView source) {
        var from = source.texture();
        if ((from.usage() & GpuTexture.USAGE_COPY_SRC) != 0) {
            var to = ensureLike(from);
            RenderSystem.getDevice().createCommandEncoder()
                    .copyTextureToTexture(from, texture, 0, 0, 0, 0, 0, width, height);
            return to;
        }
        if (!from.getFormat().hasDepthAspect() && (from.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            var to = ensureLike(from);
            PhotonFullscreenPass.copy(label, source, to, null);
            return to;
        }
        // view() must not hand out a texture this never wrote
        close();
        if (!warnedUncopyable) {
            warnedUncopyable = true;
            Photon.LOGGER.warn("{}: the target being drawn into ({}) can neither be copied nor sampled — "
                    + "effects reading it get nothing this frame", label, from.getLabel());
        }
        return null;
    }

    /** The copy as it stands, or null before the first. */
    @Nullable
    public GpuTextureView view() {
        return view;
    }

    @Override
    public void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
        format = null;
    }
}
