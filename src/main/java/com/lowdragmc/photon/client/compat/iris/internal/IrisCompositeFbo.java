package com.lowdragmc.photon.client.compat.iris.internal;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL30;

/**
 * One reusable framebuffer with exactly one colour attachment and no depth, used to composite
 * Photon's finished FX image into the shader pack's target.
 *
 * <p>The alternative — binding the pack's own gbuffer framebuffer and drawing a fullscreen quad —
 * is what corrupts packs today: that framebuffer can have several draw buffers (Complementary's
 * {@code gbuffers_textured} is {@code DRAWBUFFERS:063}), and a fragment shader with a single output
 * leaves attachments 1..n undefined across the entire screen.
 *
 * <p>Masking the other attachments off with {@code glColorMaski} would also work, but restoring it
 * has to bypass {@code GlStateManager._colorMask}, whose redundancy cache can skip the restore and
 * leave attachments permanently masked. A private framebuffer has no such trap.
 */
final class IrisCompositeFbo {

    private static int fbo = 0;
    private static int attachedTexture = 0;

    private IrisCompositeFbo() {
    }

    /** The framebuffer, re-pointed at {@code colorTexture} if needed. Render thread only. */
    static int get(int colorTexture) {
        if (colorTexture == 0) return 0;
        if (fbo == 0) {
            fbo = GlStateManager.glGenFramebuffers();
            attachedTexture = 0;
        }
        if (attachedTexture != colorTexture) {
            int saved = GlStateManager.getBoundFramebuffer();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_TEXTURE_2D, colorTexture, 0);
            GL30.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glReadBuffer(GL30.GL_NONE);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, saved);
            attachedTexture = colorTexture;
        }
        return fbo;
    }

    /**
     * Drop the framebuffer. Must also clear {@link #attachedTexture} rather than relying on the id
     * compare: GL recycles texture names, so after a pack reload the "same" id can be a different
     * texture.
     */
    static void destroy() {
        if (fbo != 0) {
            GlStateManager._glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        attachedTexture = 0;
    }
}
