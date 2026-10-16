package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

final class TerrainDepthTarget {
    final int width;
    final int height;
    final int format;
    final int framebuffer;
    final int texture;

    TerrainDepthTarget(int width, int height, int format) {
        this.width = width;
        this.height = height;
        this.format = format;
        framebuffer = GlStateManager.glGenFramebuffers();
        texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        boolean stencil = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
        int type = format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                : stencil ? GL30.GL_UNSIGNED_INT_24_8 : GL11.GL_FLOAT;
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0,
                stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT, type, null);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroy();
            throw new IllegalStateException("Incomplete terrain depth framebuffer");
        }
    }

    void copyFrom(int source) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    }

    void destroy() {
        GlStateManager._deleteTexture(texture);
        GlStateManager._glDeleteFramebuffers(framebuffer);
    }
}
