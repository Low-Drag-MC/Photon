package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 26.1 caches a colour view's framebuffers by the depth texture's GL name, so once a depth texture is closed while the
 * colour view lives on, a later texture given the same name finds the old framebuffer — attached to the dead texture —
 * and the pass tests and clears depth that is gone. A pass attaching depth therefore draws through a view kept for that
 * one depth texture object; a pair goes once either texture is closed. Render thread.
 */
public final class PhotonDepthPairs {
    private record Key(GpuTexture color, GpuTexture depth) {
    }

    private static final Map<Key, GpuTextureView> VIEWS = new HashMap<>();

    private PhotonDepthPairs() {
    }

    /** {@code color} as a pass attaching {@code depth} should draw into it; {@code color} itself without a depth. */
    public static GpuTextureView color(GpuTextureView color, @Nullable GpuTextureView depth) {
        if (depth == null) return color;
        RenderSystem.assertOnRenderThread();
        prune();
        // textures compare by identity: a new depth texture is a new pair, whatever name GL gave it
        return VIEWS.computeIfAbsent(new Key(color.texture(), depth.texture()), key ->
                RenderSystem.getDevice().createTextureView(key.color(), color.baseMipLevel(), color.mipLevels()));
    }

    /** Frees the pairs whose textures were closed: a pair view keeps a closed texture's storage alive. */
    public static void prune() {
        VIEWS.entrySet().removeIf(entry -> {
            var key = entry.getKey();
            if (!key.color().isClosed() && !key.depth().isClosed()) return false;
            entry.getValue().close();
            return true;
        });
    }
}
