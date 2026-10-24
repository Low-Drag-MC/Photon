package com.lowdragmc.photon.client.compat.iris.internal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps a shader pack's colour attachment as a {@link GpuTextureView} so the FX layer can be composited with a
 * pipeline draw. GL only (Iris does not exist on Vulkan).
 * <p>
 * The wrapper never deletes Iris' texture ({@link Borrowed}), declares its real format (formats {@link GpuFormat}
 * cannot name get no wrapper), and joins the device's {@link FrameBufferCache}. {@link #invalidate()} drops the
 * cache whenever the resolved layout changes.
 * <p>
 * Binding a view for sampling sets the texture's {@code GL_TEXTURE_BASE_LEVEL} / {@code GL_TEXTURE_MAX_LEVEL} to the
 * view's mip range — a property of the texture, not the binding, so it outlives the draw, and a pack that mipmaps
 * its scene colour (DOF, bloom) would read level 0 alone. {@link #sampled} remembers both, {@link #endSampling}
 * puts them back.
 */
final class IrisTextureBridge {

    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;

    private record Key(int glId, int width, int height, GpuFormat format) {}

    private static final Map<Key, GpuTextureView> CACHE = new HashMap<>();

    /** GL texture -> {base level, max level} as the pack left them, for every texture lent out to sample. */
    private static final Map<Integer, int[]> SAMPLED = new HashMap<>();
    private static final Map<Integer, Integer> INTERNAL_FORMATS = new HashMap<>();

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

    /** {@link #view} of a texture of the pack's, for sampling, until {@link #endSampling()}. */
    @Nullable
    static GpuTextureView sampled(int glId, int width, int height) {
        // the GL backend's own check, ahead of any GL call: Iris is not assumed to imply it
        if (glId == 0 || frameBufferCache() == null || !GL11.glIsTexture(glId)) return null;
        // a query stalls a threaded driver; a pack's targets keep their format until the pack reloads
        int internalFormat = INTERNAL_FORMATS.computeIfAbsent(glId, id -> withBound(id,
                () -> GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)));
        var view = view(glId, width, height, internalFormat);
        if (view != null && !SAMPLED.containsKey(glId)) {
            SAMPLED.put(glId, withBound(glId, () -> new int[]{
                    GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL),
                    GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL)}));
        }
        return view;
    }

    /** Give every texture lent out by {@link #sampled} its own mip range back. */
    static void endSampling() {
        if (SAMPLED.isEmpty()) return;
        for (var entry : SAMPLED.entrySet()) {
            // deleted by a pack reload in between: nothing left to hand back
            if (!GL11.glIsTexture(entry.getKey())) continue;
            var levels = entry.getValue();
            withBound(entry.getKey(), () -> {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, levels[0]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels[1]);
                return null;
            });
        }
        SAMPLED.clear();
    }

    /** Run {@code action} with {@code glId} bound to the active unit, leaving that unit as it was. */
    private static <T> T withBound(int glId, Supplier<T> action) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(glId);
        try {
            return action.get();
        } finally {
            GlStateManager._bindTexture(previous);
        }
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
        INTERNAL_FORMATS.clear();
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
