package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Photon's RGBA16F scene target (1.21's {@code DRAW_TARGET}): seeded from the output, drawn into against the
 * engine's depth, then composited back. Both copies are format-converting draws clipped to the view's scissor.
 * One instance per output size.
 */
public final class PhotonDrawTarget implements AutoCloseable {

    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

    private static final Map<Long, PhotonDrawTarget> INSTANCES = new ConcurrentHashMap<>();
    private static long frameCounter;

    public static PhotonDrawTarget acquire(int width, int height) {
        var key = ((long) width << 32) | (height & 0xFFFFFFFFL);
        var target = INSTANCES.computeIfAbsent(key, k -> new PhotonDrawTarget(Math.max(1, width), Math.max(1, height)));
        target.lastUsedFrame = frameCounter;
        return target;
    }

    /** Frame boundary: drop targets nothing rendered into for a while (closed editors, resizes). */
    public static void endFrame() {
        frameCounter++;
        INSTANCES.values().removeIf(target -> {
            if (frameCounter - target.lastUsedFrame > 120) {
                target.close();
                return true;
            }
            return false;
        });
    }

    private final GpuTexture texture;
    private final GpuTextureView view;
    private long lastUsedFrame;

    private PhotonDrawTarget(int width, int height) {
        var device = RenderSystem.getDevice();
        this.texture = device.createTexture(() -> "Photon draw target", USAGE, PhotonPipelines.HDR_FORMAT,
                width, height, 1, 1);
        this.view = device.createTextureView(texture);
    }

    public GpuTextureView view() {
        return view;
    }

    /** Seed this target with the scene as it stands, so blended fx composite over the real background. */
    public void copyFrom(GpuTextureView output, @Nullable ScissorState scissor) {
        PhotonFullscreenPass.copy("Photon draw target seed", output, view, scissor);
    }

    /** Write the finished HDR content back over the output; the RGBA8 store clamps here, once. */
    public void compositeTo(GpuTextureView output, @Nullable ScissorState scissor) {
        PhotonFullscreenPass.copy("Photon draw target composite", view, output, scissor);
    }

    @Override
    public void close() {
        view.close();
        texture.close();
    }
}
