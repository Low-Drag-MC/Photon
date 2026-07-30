package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Raw-GL {@code glBlitFramebuffer} between two textures Photon may not own.
 * <p>
 * The engine's {@code copyTextureToTexture} needs {@code USAGE_COPY_SRC} on the source and matching
 * formats — neither holds for the targets Photon has to move data between: the editor's PIP textures
 * are declared without {@code COPY_SRC}, and the HDR draw target is RGBA16F while the output it
 * copies from / composites back to is RGBA8. A framebuffer blit has neither restriction (GL converts
 * between color formats, clamping on the way down to fixed-point), so it is the one primitive that
 * covers every direction.
 * <p>
 * Bindings are saved and restored, and attachments are detached afterwards so a later blit of the
 * other aspect can't inherit a stale one. Scissor state is deliberately NOT touched: a blit obeys the
 * active scissor box, which is what keeps the composite inside the region the fx draws were clipped
 * to. Render thread only, outside any open render pass.
 */
public final class PhotonFramebufferBlit {

    private static int readFbo;
    private static int drawFbo;

    private PhotonFramebufferBlit() {
    }

    /** Blit {@code source}'s color into {@code destination}, both sized {@code width x height}. */
    public static void color(GpuTexture source, GpuTexture destination, int width, int height) {
        blit(((GlTexture) source).glId(), ((GlTexture) destination).glId(), width, height,
                GL11.GL_COLOR_BUFFER_BIT, GL30.GL_COLOR_ATTACHMENT0);
    }

    /** {@link #color(GpuTexture, GpuTexture, int, int)} over views, sized after the source. */
    public static void color(GpuTextureView source, GpuTextureView destination) {
        color(source.texture(), destination.texture(),
                source.texture().getWidth(0), source.texture().getHeight(0));
    }

    public static void blit(int sourceTexture, int destinationTexture, int width, int height,
                            int bufferBit, int attachment) {
        if (readFbo == 0) readFbo = GL30.glGenFramebuffers();
        if (drawFbo == 0) drawFbo = GL30.glGenFramebuffers();
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL11.GL_TEXTURE_2D, sourceTexture, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    GL11.GL_TEXTURE_2D, destinationTexture, 0);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, bufferBit, GL11.GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }
}
