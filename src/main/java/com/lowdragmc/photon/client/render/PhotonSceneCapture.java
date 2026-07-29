package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;

/**
 * Copies of the current render target's color/depth taken at the start of Photon's draw slot (the
 * 1.21 "scene texture" the wireframe inverse shader and {@code SamplerScene*} custom-shader
 * samplers read — you cannot sample the active attachment). Captured lazily per drain only when a
 * queued job needs them; sized/formatted after whatever target is current (world main or the
 * editor's PIP textures). The depth copy has {@code TEXTURE_BINDING} so it samples as a plain
 * {@code sampler2D} (hardware depth in {@code .r}). Render thread only.
 */
public final class PhotonSceneCapture {

    private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;

    /** One owned capture slot (color or depth), recreated on size/format change. */
    private static final class Slot {
        final String label;
        final int blitBufferBit;
        final int blitAttachment;
        @Nullable
        GpuTexture texture;
        @Nullable
        GpuTextureView view;
        int width, height;
        @Nullable
        TextureFormat format;

        Slot(String label, int blitBufferBit, int blitAttachment) {
            this.label = label;
            this.blitBufferBit = blitBufferBit;
            this.blitAttachment = blitAttachment;
        }

        GpuTextureView capture(GpuTextureView target) {
            var source = target.texture();
            var w = source.getWidth(0);
            var h = source.getHeight(0);
            if (texture == null || w != width || h != height || source.getFormat() != format) {
                if (view != null) {
                    view.close();
                }
                if (texture != null) {
                    texture.close();
                }
                width = w;
                height = h;
                format = source.getFormat();
                var device = RenderSystem.getDevice();
                texture = device.createTexture(() -> label, USAGE, format, w, h, 1, 1);
                view = device.createTextureView(texture);
            }
            if ((source.usage() & GpuTexture.USAGE_COPY_SRC) != 0) {
                RenderSystem.getDevice().createCommandEncoder()
                        .copyTextureToTexture(source, texture, 0, 0, 0, 0, 0, w, h);
            } else {
                // sources we don't own (the editor's PIP textures) lack USAGE_COPY_SRC — raw-GL
                // framebuffer blit with binding save/restore (KilaGraph's proven Iris-copy pattern)
                blit(((GlTexture) source).glId(), ((GlTexture) texture).glId(), w, h,
                        blitBufferBit, blitAttachment);
            }
            return view;
        }
    }

    private static final Slot COLOR = new Slot("Photon scene capture",
            GL11.GL_COLOR_BUFFER_BIT, GL30.GL_COLOR_ATTACHMENT0);
    private static final Slot DEPTH = new Slot("Photon scene depth capture",
            GL11.GL_DEPTH_BUFFER_BIT, GL30.GL_DEPTH_ATTACHMENT);

    /** Lazily created FBO pair for the raw-GL blit path. */
    private static int blitReadFbo, blitDrawFbo;

    private PhotonSceneCapture() {
    }

    /** Copy {@code target}'s color into the owned capture texture and return its view. */
    public static GpuTextureView captureColor(GpuTextureView target) {
        return COLOR.capture(target);
    }

    /** Copy {@code target}'s depth into the owned capture texture and return its view. */
    public static GpuTextureView captureDepth(GpuTextureView target) {
        return DEPTH.capture(target);
    }

    private static void blit(int srcTex, int dstTex, int w, int h, int bufferBit, int attachment) {
        if (blitReadFbo == 0) blitReadFbo = GL30.glGenFramebuffers();
        if (blitDrawFbo == 0) blitDrawFbo = GL30.glGenFramebuffers();
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL11.GL_TEXTURE_2D, srcTex, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitDrawFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    GL11.GL_TEXTURE_2D, dstTex, 0);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, bufferBit, GL11.GL_NEAREST);
            // detach so a later blit of the other aspect doesn't inherit a stale attachment
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    /** Clamp/nearest sampler for screen-space lookups of the captures. */
    public static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
    }
}
