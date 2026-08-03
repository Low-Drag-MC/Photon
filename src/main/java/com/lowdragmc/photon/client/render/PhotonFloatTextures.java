package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * True float render targets on the GL backend. 26.1's {@link TextureFormat} has no float color
 * formats, so the storage is allocated directly as {@code GL_RGBA16F} and wrapped in a
 * {@link GlTexture} (constructor opened by our AT) whose DECLARED format is RGBA8 — the abstraction
 * only reads the format for color/depth aspect checks and copies, neither of which touch these
 * textures. GL-backend only (accepted M3 debt, like the blend-equation escape); callers fall back
 * to encoded RGBA8 when unavailable. Drop when the engine grows float formats.
 */
public final class PhotonFloatTextures {

    private static final int GL_TEXTURE_MAX_LEVEL = 33085;
    private static final int GL_TEXTURE_MIN_LOD = 33082;
    private static final int GL_TEXTURE_MAX_LOD = 33083;
    private static final int GL_HALF_FLOAT = 5131;

    private PhotonFloatTextures() {
    }

    public static boolean isSupported() {
        // GlDevice itself is package-private — identify the backend by name ("OpenGL" today)
        return RenderSystem.getDevice().getBackendName().toLowerCase(Locale.ROOT).contains("opengl");
    }

    /** An RGBA16F texture usable as render attachment + sampler, or null when unsupported/failed. */
    @Nullable
    public static GpuTexture createRgba16f(String label, int usage, int width, int height) {
        return createHalfFloat(label, usage, width, height, GL30.GL_RGBA16F, GL11.GL_RGBA,
                TextureFormat.RGBA8);
    }

    /**
     * A half-float texture of an arbitrary channel count. {@code declaredFormat} is what the abstraction
     * will BELIEVE this texture is — pick one with the right aspect (a color format for color
     * attachments); its channel count and size are never read for these textures, because the only
     * places that would care ({@code copyTextureToTexture}, format conversions) are exactly what Photon
     * routes around with {@link PhotonFramebufferBlit}.
     */
    @Nullable
    public static GpuTexture createHalfFloat(String label, int usage, int width, int height,
                                             int internalFormat, int format, TextureFormat declaredFormat) {
        if (!isSupported()) {
            return null;
        }
        GlStateManager.clearGlErrors();
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_MIN_LOD, 0);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_LOD, 0);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                format, GL_HALF_FLOAT, null);
        int error = GlStateManager._getError();
        if (error != 0) {
            GlStateManager._deleteTexture(id);
            Photon.LOGGER.warn("half-float texture allocation failed (GL error {}), "
                    + "falling back", error);
            return null;
        }
        return new GlTexture(usage, label, declaredFormat, width, height, 1, 1, id);
    }
}
