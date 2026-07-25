package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;

/**
 * The shared scene capture behind the render-graph editor preview: a color+depth copy of the
 * CLEAN world frame (taken right before the production effect chain runs, so open editors preview
 * against an un-effected scene). Capture is pull-based — a visible preview panel calls
 * {@link #requestCapture()} every frame it draws, and the next frame's render hooks fill
 * {@link #source()}; when no preview is open, nothing is copied. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class PostFXPreview {

    /** "No preview has ever asked" — kept out of the frame arithmetic below, subtracting it
     *  overflows and made every frame look requested. */
    private static final long NEVER = Long.MIN_VALUE;

    @Nullable
    private static HDRTarget SOURCE;
    private static long requestFrame = NEVER;
    private static long capturedFrame = NEVER;
    private static boolean hasCapture;

    private PostFXPreview() {}

    /** Called by preview UIs each frame they draw; keeps the per-frame capture alive. */
    public static void requestCapture() {
        requestFrame = PostFXTargetPool.currentFrame();
    }

    private static boolean captureWanted() {
        return requestFrame != NEVER && PostFXTargetPool.currentFrame() - requestFrame <= 1;
    }

    /**
     * Copy the clean scene (color + depth) if a preview wants it — called with the effect chain's
     * input before any effect runs (both the particle-pipeline and the standalone path), at most
     * once per frame.
     */
    public static void captureIfRequested(RenderTarget cleanScene) {
        if (!captureWanted()) return;
        long frame = PostFXTargetPool.currentFrame();
        if (capturedFrame == frame) return;
        capturedFrame = frame;
        SOURCE = RenderPassPipeline.resize(SOURCE, cleanScene.width, cleanScene.height, true);
        // the blit copy ends on framebuffer 0 — restore the caller's binding (raw bind, not
        // bindWrite: the viewport must stay untouched). A caller that returns right after us
        // would otherwise leave the world drawing into the backbuffer.
        int boundFramebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        SOURCE.copyDepthAndColorFrom(cleanScene);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundFramebuffer);
        hasCapture = true;
    }

    /** The latest clean-scene copy, or null when no world frame has been captured yet. */
    @Nullable
    public static HDRTarget source() {
        return hasCapture ? SOURCE : null;
    }
}
