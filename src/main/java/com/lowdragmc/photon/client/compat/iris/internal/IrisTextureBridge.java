package com.lowdragmc.photon.client.compat.iris.internal;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lends a shader pack's colour attachment to Photon as a {@link GpuTextureView}, so the FX layer can be
 * composited onto it with an ordinary {@code RenderPipeline} draw.
 *
 * <p><b>This is the 26.1 replacement for 1.21's owned composite FBO.</b> There, Photon had to bind the
 * pack's framebuffer itself and drive the blend with raw {@code GlStateManager} calls — which meant
 * fighting Iris for the depth/colour-mask lock it takes whenever a shader it does not manage is
 * applied. Here blending and the write mask are properties of the pipeline, applied by the GL backend
 * when the pass opens, so none of that applies: wrap the texture, hand it to
 * {@link com.lowdragmc.photon.client.render.PhotonFullscreenPass}, done.
 *
 * <p>Two things make the wrapping safe:
 *
 * <ul>
 *   <li><b>Ownership.</b> {@link GlTexture#close()} deletes the GL texture, and this one is Iris'.
 *       {@link Borrowed} overrides it to nothing; the pack owns the storage and frees it when it
 *       reloads.</li>
 *   <li><b>Format.</b> A pack's colortex may be {@code R11F_G11F_B10F} or another format 26.1's
 *       {@link TextureFormat} cannot name. The declared format is therefore a stand-in — the
 *       abstraction only consults it for aspect checks and for copies, and Photon does neither to
 *       this texture; the real format stays whatever the pack allocated. Same accepted trade as
 *       {@link com.lowdragmc.photon.client.render.PhotonFloatTextures}.</li>
 * </ul>
 *
 * <p>Cached by (texture, size) because a pass opened on a view makes the backend build an FBO for it,
 * and rebuilding that every frame would be wasteful. {@link #invalidate()} drops everything whenever
 * the resolved layout changes — a pack reload replaces the textures, and a stale wrapper would then
 * point at a deleted name.
 *
 * <p>Render thread only.
 */
final class IrisTextureBridge {

    /** The pack's texture is only ever a colour attachment for us; it is never sampled or copied. */
    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;

    private record Key(int glId, int width, int height) {}

    private static final Map<Key, GpuTextureView> CACHE = new HashMap<>();

    private IrisTextureBridge() {
    }

    /**
     * A view over the pack-owned GL texture {@code glId}, or null when there is nothing to wrap.
     * The caller must NOT close it — {@link #invalidate()} owns the lifetime.
     */
    @Nullable
    static GpuTextureView view(int glId, int width, int height) {
        if (glId == 0 || width <= 0 || height <= 0) return null;
        return CACHE.computeIfAbsent(new Key(glId, width, height), key -> {
            var texture = new Borrowed(USAGE, "Photon iris target", TextureFormat.RGBA8,
                    key.width(), key.height(), 1, 1, key.glId());
            return RenderSystem.getDevice().createTextureView(texture);
        });
    }

    /** Drop every wrapper. Called whenever the resolved layout changes, including a pack reload. */
    static void invalidate() {
        for (var view : CACHE.values()) {
            // frees the FBO the backend built for this view; the texture itself is Iris' to free
            view.close();
        }
        CACHE.clear();
    }

    /** A {@link GlTexture} over storage somebody else owns. @see IrisTextureBridge */
    private static final class Borrowed extends GlTexture {
        Borrowed(int usage, String label, TextureFormat format, int width, int height,
                 int depthOrLayers, int mipLevels, int id) {
            super(usage, label, format, width, height, depthOrLayers, mipLevels, id);
        }

        @Override
        public void close() {
            // Deliberately does NOT set `closed`. GlTexture.removeViews() destroys the texture as soon
            // as it sees closed && views == 0 — and a view is always closed after us — so marking it
            // closed here would hand the shader pack's colortex to glDeleteTextures by the back door.
            // The GL name belongs to Iris; the wrapper simply stops being referenced.
        }
    }
}
