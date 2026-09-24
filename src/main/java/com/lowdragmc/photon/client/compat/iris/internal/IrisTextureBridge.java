package com.lowdragmc.photon.client.compat.iris.internal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Wraps a shader pack's colour attachment as a {@link GpuTextureView} so the FX layer can be composited with a
 * pipeline draw. GL only (Iris does not exist on Vulkan).
 * <p>
 * The wrapper never deletes Iris' texture ({@link Borrowed}), declares its real format (formats {@link GpuFormat}
 * cannot name get no wrapper), and joins the device's {@link FrameBufferCache}. {@link #invalidate()} drops the
 * cache whenever the resolved layout changes.
 */
final class IrisTextureBridge {

    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;

    private record Key(int glId, int width, int height, GpuFormat format) {}

    private static final Map<Key, GpuTextureView> CACHE = new HashMap<>();

    private static final Int2ObjectMap<GpuFormat> FORMATS = new Int2ObjectOpenHashMap<>();

    static {
        for (var format : GpuFormat.values()) {
            var id = GlConst.toGlInternalId(format);
            if (id != 0) {
                FORMATS.putIfAbsent(id, format);
            }
        }
    }

    private IrisTextureBridge() {
    }

    @Nullable
    static GpuFormat formatOf(int internalFormat) {
        return FORMATS.get(internalFormat);
    }

    static int glId(@Nullable GpuTexture texture) {
        return texture instanceof GlTexture gl ? gl.glId() : 0;
    }

    /** Null when there is nothing to wrap. The caller must not close it; {@link #invalidate()} does. */
    @Nullable
    static GpuTextureView view(int glId, int width, int height, int internalFormat) {
        if (glId == 0 || width <= 0 || height <= 0) return null;
        var format = formatOf(internalFormat);
        if (format == null) return null;
        var cache = frameBufferCache();
        if (cache == null) return null;
        return CACHE.computeIfAbsent(new Key(glId, width, height, format), key -> {
            var texture = new Borrowed(USAGE, "Photon iris target", key.format(),
                    key.width(), key.height(), key.glId(), cache);
            return RenderSystem.getDevice().createTextureView(texture);
        });
    }

    @Nullable
    private static FrameBufferCache frameBufferCache() {
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
        return main instanceof GlTexture gl ? gl.frameBufferCache : null;
    }

    static void invalidate() {
        for (var view : CACHE.values()) {
            // frees our FBO; the texture is Iris'
            view.close();
        }
        CACHE.clear();
    }

    private static final class Borrowed extends GlTexture {
        Borrowed(int usage, String label, GpuFormat format, int width, int height, int id, FrameBufferCache cache) {
            super(usage, label, format, width, height, 1, 1, id, cache);
        }

        @Override
        public void close() {
            // never mark closed: removeViews() would then delete Iris' texture
        }
    }
}
