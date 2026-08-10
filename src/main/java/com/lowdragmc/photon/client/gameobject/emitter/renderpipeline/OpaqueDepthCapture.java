package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.postfx.runtime.FormatTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;

/**
 * A snapshot of the frame's depth buffer taken <b>before</b> the translucent chunk layer, i.e.
 * containing terrain, entities and block entities but not water, glass or ice.
 *
 * <p>This is what {@link FXCompositeMode#LATE} depth-tests against. Testing against the live buffer
 * is what makes a water surface slice an effect in half: water is drawn before the particle pass and
 * writes depth, so every FX fragment behind it is rejected outright. Against the opaque snapshot the
 * effect stays whole and simply blends over the water.
 *
 * <p>Vanilla does exactly this copy itself in Fabulous mode
 * ({@code particlesTarget.copyDepthFrom(mainRenderTarget)} in {@code LevelRenderer.renderLevel}), so
 * the once-per-frame full-screen depth blit is a cost the engine already considers acceptable.
 *
 * <p>Attaching the snapshot to the draw target has a second, quieter benefit: a material with
 * {@code depthMask} on then writes into this throwaway copy instead of MC's real depth buffer, so an
 * author can use depth writes for FX-vs-FX occlusion without the writes leaking into the clouds,
 * weather and hand rendered after us.
 */
@OnlyIn(Dist.CLIENT)
public final class OpaqueDepthCapture {

    /** Only the depth attachment is ever read. The colour buffer is dead weight that {@code
     *  RenderTarget} insists on, so it is allocated at R8 rather than the RGBA16F an {@code
     *  HDRTarget} would default to — one byte per pixel instead of eight. */
    @Nullable
    private static FormatTarget target;
    /** Frame ordinal of the last successful {@link #capture()}; {@code -1} = never. */
    private static long capturedFrame = -1;
    private static long frame = 0;
    /** Set by {@link RenderPassPipeline} while a pass resolves to {@link FXCompositeMode#LATE}, read
     *  by the next frame's {@link #capture()}. See {@link #demand()}. */
    private static boolean demandedThisFrame = false;
    private static boolean demandedLastFrame = false;

    private OpaqueDepthCapture() {
    }

    /**
     * Copy MC's current depth into the snapshot. Called from {@code AFTER_BLOCK_ENTITIES} — the last
     * render stage before {@code RenderType.translucent()} goes down.
     *
     * <p>By then every solid and cutout sheet has been flushed (terrain, entities, block entities),
     * so what the snapshot misses is the handful of batches vanilla deliberately keeps for last:
     * breaking decals, block outlines and debug lines, and the translucent entity sheets (banners,
     * shields, glints, {@code waterMask}). Those are exactly the surfaces this mode is supposed to
     * draw through, so the slightly earlier stage costs nothing — vanilla's own Fabulous snapshot
     * sits a few lines further down, right before the translucent chunk layer.
     */
    public static void capture() {
        if (!isWanted()) return;
        var mainTarget = UISurface.currentTarget();
        if (mainTarget.width <= 0 || mainTarget.height <= 0) return;
        // EVERYTHING below can leave a foreign framebuffer bound, so the save/restore wraps the whole
        // method rather than just the allocation. HDRTarget.copyFromInternal ends on
        // _glBindFramebuffer(GL_FRAMEBUFFER, 0) — leaving that in place here redirected the entire
        // rest of the level render (the translucent chunk layer, i.e. water, then clouds and
        // weather) into the default framebuffer. Every other caller in Photon happens to bindWrite
        // immediately afterwards, which is why this only ever bit the one that did not.
        int framebuffer = GlStateManager.getBoundFramebuffer();
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();

        allocate(mainTarget.width, mainTarget.height);
        assert target != null;
        target.copyDepthFrom(mainTarget);
        capturedFrame = frame;

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
    }

    /**
     * Whether this frame has any use for a snapshot. A frame that answers "no" must not touch GL at
     * all — Photon has no business perturbing the vanilla pipeline of a frame it does not draw in.
     *
     * <p>Two independent gates:
     *
     * <ul>
     *   <li><b>Are there Photon FX on screen?</b> The queue is already populated by this stage
     *       (particles are added on tick, not during the render), so this is an honest answer rather
     *       than a guess.</li>
     *   <li><b>Does anything actually want LATE?</b> The global default answers that outright. An
     *       emitter-level override while the global default is VANILLA is answered by the previous
     *       frame's routing — that emitter renders in place for one frame and correctly from the
     *       next, which beats blitting depth every frame for a mode nobody asked for.</li>
     * </ul>
     */
    private static boolean isWanted() {
        if (!ParticleQueueRenderType.TRANSLUCENT_QUEUE.hasQueuedParticles()) return false;
        return demandedLastFrame
                || PhotonConfig.INSTANCE.fxCompositeMode.get() == FXCompositeMode.LATE;
    }

    /** Record that a pass asked for {@link FXCompositeMode#LATE} this frame, so the next frame
     *  captures even when the global default is VANILLA. */
    public static void demand() {
        demandedThisFrame = true;
    }

    /**
     * Allocate or re-shape the snapshot. Callers are inside {@link #capture()}'s save/restore, so
     * this may leave the binding and viewport wherever {@code RenderTarget}'s constructor puts them.
     */
    private static void allocate(int width, int height) {
        if (target != null && target.width == width && target.height == height) return;
        if (target == null) {
            target = new FormatTarget(width, height, GL11.GL_NEAREST, TargetFormat.R8, true);
            target.setClearColor(0f, 0f, 0f, 0f);
        } else {
            target.resize(width, height, Minecraft.ON_OSX);
        }
    }

    /**
     * The captured depth texture, or {@code 0} when this frame has no valid snapshot — the level
     * render never reached {@code AFTER_BLOCK_ENTITIES} (another mod cancelled the stage, or we are
     * drawing outside a level render at all, e.g. in the editor scene). Callers must fall back to
     * {@link FXCompositeMode#VANILLA} rather than depth-test against a stale frame.
     */
    public static int depthTexture() {
        if (target == null || capturedFrame != frame) return 0;
        return target.getDepthTextureId();
    }

    public static boolean hasCaptureThisFrame() {
        return depthTexture() != 0;
    }

    /** Frame boundary — invalidates the snapshot so the next frame cannot reuse it silently, and
     *  rolls the demand latch over. */
    public static void endFrame() {
        frame++;
        demandedLastFrame = demandedThisFrame;
        demandedThisFrame = false;
    }
}
