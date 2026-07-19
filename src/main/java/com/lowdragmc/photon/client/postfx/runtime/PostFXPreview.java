package com.lowdragmc.photon.client.postfx.runtime;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.jetbrains.annotations.Nullable;

/**
 * The shared scene capture behind the render-graph editor preview. Capture is pull-based — a
 * visible preview panel calls {@link #requestCapture()} every frame it draws, and the next frame's
 * render hooks fill the capture; when no preview is open, nothing is copied.
 * <p>
 * M0 stub (original in git history, 1.21 branch): the clean-scene copy lived in an LDLib2
 * {@code HDRTarget}. TODO(M3): recapture via a Photon GpuTexture target filled from the frame
 * graph (a pass reading main color+depth before the effect chain runs).
 */
public final class PostFXPreview {

    private static long requestFrame = Long.MIN_VALUE;

    private PostFXPreview() {}

    /** Called by preview UIs each frame they draw; keeps the per-frame capture alive. */
    public static void requestCapture() {
        requestFrame = PostFXTargetPool.currentFrame();
    }

    private static boolean captureWanted() {
        return PostFXTargetPool.currentFrame() - requestFrame <= 1;
    }

    /** TODO(M3): copy the clean scene (color + depth) if a preview wants it. */
    public static void captureIfRequested(RenderTarget cleanScene) {
    }

    /** The latest clean-scene copy; always null until the M3 capture returns. */
    @Nullable
    public static RenderTarget source() {
        return null;
    }
}
