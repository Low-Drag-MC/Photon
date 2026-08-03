package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * A snapshot of the frame's depth buffer taken <b>before</b> the translucent chunk layer — terrain,
 * entities, block entities and Photon's own opaque FX, but not water, glass or ice.
 *
 * <p>This is what {@link FXCompositeMode#LATE} depth-tests against. Testing against the live buffer is
 * what makes a water surface slice an effect in half: water is drawn before the particle pass and
 * writes depth, so every FX fragment behind it is rejected outright. Against the opaque snapshot the
 * effect stays whole and simply blends over the water.
 *
 * <p>Attaching the snapshot to the layer's draws has a second, quieter benefit: a material with
 * {@code depthMask} on then writes into this throwaway copy instead of MC's real depth buffer, so an
 * author can use depth writes for FX-vs-FX occlusion without the writes leaking into the clouds,
 * weather and hand rendered after us.
 *
 * <p><b>Demand-driven.</b> The copy is a full-screen depth blit, so it only runs on frames where
 * something actually asked for it — {@link #demand()} is called by the drain when a job resolves to
 * LATE, and the flag is read by the <i>next</i> frame's {@link #capture()} (the snapshot point comes
 * before the drain that would demand it). A world with no LATE effects pays nothing.
 *
 * <p>Render thread only, outside any open render pass.
 */
public final class OpaqueDepthCapture {

    /** Attached as the layer's depth, and copied into by a blit. */
    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;

    @Nullable
    private static GpuTexture texture;
    @Nullable
    private static GpuTextureView view;
    private static int width;
    private static int height;
    @Nullable
    private static TextureFormat format;

    /** Set while a job resolves to LATE; read and cleared by the next {@link #capture()}. */
    private static boolean demanded;
    private static boolean capturedThisFrame;

    private OpaqueDepthCapture() {
    }

    /** Ask for a snapshot from the next frame on. @see OpaqueDepthCapture */
    public static void demand() {
        demanded = true;
    }

    /**
     * Copy the current depth buffer, if anything asked for one. Called at the last seam before the
     * translucent chunk layer.
     */
    public static void capture() {
        capturedThisFrame = false;
        if (!demanded) {
            release(); // nothing has wanted one for a whole frame — stop holding a screen-sized texture
            return;
        }
        demanded = false; // re-armed by the drain each frame a LATE job is still present
        var mainDepth = Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();
        if (mainDepth == null) return;
        var source = mainDepth.texture();
        int w = source.getWidth(0);
        int h = source.getHeight(0);
        if (texture == null || w != width || h != height || source.getFormat() != format) {
            release();
            width = w;
            height = h;
            // The SOURCE's format, not a fixed one: glBlitFramebuffer rejects a depth blit between
            // differing formats, and silently — the copy would just keep whatever it had.
            format = source.getFormat();
            var device = RenderSystem.getDevice();
            var declared = format;
            texture = device.createTexture(() -> "Photon opaque depth", USAGE, declared, w, h, 1, 1);
            view = device.createTextureView(texture);
        }
        PhotonFramebufferBlit.depth(source, texture, w, h);
        capturedThisFrame = true;
    }

    /** The snapshot for this frame, or null when none was taken (nothing demanded one, or it failed). */
    @Nullable
    public static GpuTextureView view() {
        return capturedThisFrame ? view : null;
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
