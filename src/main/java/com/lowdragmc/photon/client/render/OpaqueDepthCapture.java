package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * A snapshot of the frame's depth taken <b>before</b> the translucent chunk layer, which {@link FXCompositeMode#LATE}
 * tests against so water cannot slice an effect in half. Depth writes of the layer also stay in this copy.
 * <p>
 * Demand-driven: {@link #demand()} is called while preparing the frame, before the snapshot seam of the same frame.
 */
public final class OpaqueDepthCapture {

    /** COPY_SRC: scene-depth materials in the layer sample a copy of this snapshot. */
    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;

    @Nullable
    private static GpuTexture texture;
    @Nullable
    private static GpuTextureView view;
    private static int width;
    private static int height;
    @Nullable
    private static GpuFormat format;

    private static boolean demanded;
    /** A snapshot only stands in for the depth it was taken of (editor scenes have their own). */
    @Nullable
    private static GpuTexture capturedFrom;

    private OpaqueDepthCapture() {
    }

    public static void demand() {
        demanded = true;
    }

    /** Called at AfterOpaqueFeatures. */
    public static void capture() {
        capturedFrom = null;
        if (!demanded) {
            release();
            return;
        }
        demanded = false;
        var outputDepth = PhotonRenderOutput.depth();
        if (outputDepth == null) return;
        var source = outputDepth.texture();
        if ((source.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            return; // the layer then tests against the live depth
        }
        int w = source.getWidth(0);
        int h = source.getHeight(0);
        if (texture == null || w != width || h != height || source.getFormat() != format) {
            release();
            width = w;
            height = h;
            // a depth copy needs identical formats
            format = source.getFormat();
            var device = RenderSystem.getDevice();
            var declared = format;
            texture = device.createTexture(() -> "Photon opaque depth", USAGE, declared, w, h, 1, 1);
            view = device.createTextureView(texture);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, texture, 0, 0, 0, 0, 0, w, h);
        capturedFrom = source;
    }

    /** This frame's snapshot of {@code liveDepth}, or null. */
    @Nullable
    public static GpuTextureView view(GpuTextureView liveDepth) {
        return capturedFrom != null && capturedFrom == liveDepth.texture() ? view : null;
    }

    private static void release() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        width = 0;
        height = 0;
        format = null;
    }
}
