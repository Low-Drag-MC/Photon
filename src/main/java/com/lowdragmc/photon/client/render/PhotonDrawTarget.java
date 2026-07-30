package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Photon's own RGBA16F scene target — the 26.1 form of 1.21's {@code DRAW_TARGET}, and the reason
 * HDR survives at all: fx colors above 1.0 are the whole point of the {@code hdr} material multiplier,
 * and writing them straight into the engine's RGBA8 output clamps them away before bloom can see them.
 * <p>
 * The cycle per drain is 1.21's: <b>copy</b> the current output color in (so blended fx composite over
 * the real scene), <b>draw</b> every job into this target paired with the ENGINE's depth view (a
 * {@code RenderPass} takes color and depth independently, and {@code GlTextureView.getFbo} assembles an
 * FBO from exactly that pair — so HDR color and correct occlusion are not a trade-off), then
 * <b>composite</b> back over the output, where the RGBA8 store clamps once, at the end.
 * <p>
 * Depth is NOT owned here: sharing the engine's means Photon fx depth-test and depth-write against the
 * same buffer the world does, and anything MC draws afterwards sorts against our fx for free.
 * <p>
 * One instance per output size (world and each editor scene differ), evicted when unused — the same
 * pooling {@link PhotonBloom} uses. GL backend only, like the rest of Photon's render layer; a failed
 * allocation is reported once per size and the drain skips rather than silently falling back to a
 * second, LDR rendering path. Render thread only.
 */
public final class PhotonDrawTarget implements AutoCloseable {

    /**
     * Deliberately WITHOUT the copy usages, even though this target is copied both ways: declaring
     * {@code COPY_SRC} would make {@link PhotonSceneCapture} prefer the engine's
     * {@code copyTextureToTexture}, which calls {@code _disableScissorTest()} and never restores it —
     * fine for the world, but it would silently unclip the editor's PIP scene mid-frame. Photon's own
     * blits go through {@link PhotonFramebufferBlit}, which needs no usage flag and leaves the scissor
     * box alone (so the seed and the composite are clipped to the same region the fx draws were).
     */
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static final Map<Long, PhotonDrawTarget> INSTANCES = new ConcurrentHashMap<>();
    /** Sizes whose allocation failed — keeps the warning to once per size instead of once per frame. */
    private static final java.util.Set<Long> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static long frameCounter;

    /**
     * The target for an output of this size, or null when RGBA16F is unavailable (the caller must skip
     * drawing — there is deliberately no LDR fallback path).
     */
    @Nullable
    public static PhotonDrawTarget acquire(int width, int height) {
        var key = ((long) width << 32) | (height & 0xFFFFFFFFL);
        var existing = INSTANCES.get(key);
        if (existing != null) {
            existing.lastUsedFrame = frameCounter;
            return existing;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        var texture = PhotonFloatTextures.createRgba16f("Photon draw target", USAGE,
                Math.max(1, width), Math.max(1, height));
        if (texture == null) {
            FAILED.add(key);
            Photon.LOGGER.error("Photon could not allocate its {}x{} RGBA16F draw target — fx will not "
                    + "render at this size", width, height);
            return null;
        }
        var target = new PhotonDrawTarget(texture);
        INSTANCES.put(key, target);
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

    private PhotonDrawTarget(GpuTexture texture) {
        this.texture = texture;
        this.view = RenderSystem.getDevice().createTextureView(texture);
    }

    /** What the drain's render passes use as their color attachment. */
    public GpuTextureView view() {
        return view;
    }

    /** Seed this target with the scene as it stands, so blended fx composite over the real background. */
    public void copyFrom(GpuTextureView output) {
        PhotonFramebufferBlit.color(output.texture(), texture,
                texture.getWidth(0), texture.getHeight(0));
    }

    /** Write the finished HDR content back over the output; the RGBA8 store clamps here, once. */
    public void compositeTo(GpuTextureView output) {
        PhotonFramebufferBlit.color(texture, output.texture(),
                texture.getWidth(0), texture.getHeight(0));
    }

    @Override
    public void close() {
        view.close();
        texture.close();
    }
}
