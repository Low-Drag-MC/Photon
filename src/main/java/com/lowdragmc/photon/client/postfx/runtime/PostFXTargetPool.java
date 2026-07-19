package com.lowdragmc.photon.client.postfx.runtime;


/**
 * M0 stub (original in git history, 1.21 branch). Was the pooled off-screen HDR/format target
 * allocator (LDLib2 {@code HDRTarget} + {@link com.lowdragmc.photon.client.postfx.graph.TargetFormat}
 * respec, per-frame eviction, VRAM budget).
 * <p>
 * TODO(M3): rebuilt on the 26.1 GPU abstraction — either frame-graph transient targets
 * ({@code FrameGraphBuilder.createInternal} + {@code GraphicsResourceAllocator} pooling, which the
 * frame graph gives us for free) or a Photon-owned {@code GpuTexture} pool for targets that must
 * outlive a pass. Only the frame clock survives — {@code PostEffectStack} keys its once-per-frame
 * consumption guard on it.
 */
public final class PostFXTargetPool {

    private static long FRAME_ID;

    private PostFXTargetPool() {}

    /** The current frame index — the effect stack's once-per-frame consumption guard keys on it. */
    public static long currentFrame() {
        return FRAME_ID;
    }

    /** Advance the frame clock (target eviction/budget enforcement returns in M3). */
    public static void endFrame() {
        FRAME_ID++;
    }

    /** Drop every pooled target (window resize etc.). No-op until the M3 pool returns. */
    public static void invalidateAll() {
    }
}
