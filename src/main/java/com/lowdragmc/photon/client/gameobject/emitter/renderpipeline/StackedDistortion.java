package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import javax.annotation.Nullable;

/**
 * Screen distortions that add up: a stacked distortion adds its push to a frame-sized offset buffer instead of
 * reading the scene, and the frame is bent by the sum once the effects are down. Alpha keeps the nearest depth.
 */
@OnlyIn(Dist.CLIENT)
public final class StackedDistortion {
    /** what the depth channel clears to: must stay above kila_stack.fsh's 60000 cutoff and within fp16 */
    private static final float FAR = 65000f;
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
    private static final Matrix4f DRAW_PROJECTION = new Matrix4f();
    @Nullable
    private static HDRTarget offsets;
    @Nullable
    private static HDRTarget frameCopy;
    /** offsets were added since the last bend */
    private static boolean pending;

    private StackedDistortion() {
    }

    /** Whether a distortion drawing now can stack: in a build, off a shader pack. */
    public static boolean available() {
        var pipeline = RenderPassPipeline.getCurrent();
        return pipeline != null && pipeline.supportsStackedDistortion();
    }

    /** Before a stacked distortion draws: onto the offset buffer, tested against the pass's depth, adding up. */
    public static void beginDraw() {
        var target = RenderPassPipeline.drawTarget();
        if (target == null) return;
        offsets = RenderPassPipeline.resize(offsets, target.width, target.height, false);
        int depth = target.hasOtherAttachedDepthTexture() ? target.getAttachedDepthTexture() : target.getDepthTextureId();
        if (offsets.getAttachedDepthTexture() != depth) offsets.attachDepthBuffer(depth);
        offsets.bindWrite(false);
        if (!pending) {
            // colour only: the depth attached is the scene's
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._clearColor(0f, 0f, 0f, FAR);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            pending = true;
        }
        DRAW_PROJECTION.set(RenderSystem.getProjectionMatrix());
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        // pushes add up; the alpha keeps the nearest depth
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_MIN);
        RenderSystem.depthMask(false);
    }

    /** After it: back onto the build's own target. */
    public static void endDraw() {
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        var target = RenderPassPipeline.drawTarget();
        if (target != null) target.bindWrite(false);
    }

    /** Bends {@code frame} by the summed offsets and starts over; {@code depthTexture} 0 for none. Leaves {@code frame} bound. */
    public static void bend(RenderTarget frame, int depthTexture) {
        if (!pending || offsets == null) return;
        pending = false;
        var shader = PhotonShaders.getKilaStackShader();
        if (shader == null) return;
        frameCopy = RenderPassPipeline.resize(frameCopy, frame.width, frame.height, false);
        frameCopy.copyColorFrom(frame);
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        frame.bindWrite(false);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        shader.setSampler("DiffuseSampler", frameCopy.getColorTextureId());
        shader.setSampler("OffsetSampler", offsets.getColorTextureId());
        if (depthTexture > 0) shader.setSampler("DepthSampler", depthTexture);
        shader.safeGetUniform("U_InverseProjectionMatrix").set(DRAW_PROJECTION.invert(INVERSE_PROJECTION));
        shader.safeGetUniform("KilaStackParams").set(depthTexture > 0 ? 1f : 0f, 0f, 0f, 0f);
        shader.apply();
        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        SceneBlit.drawFullscreenQuad();
        shader.clear();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableDepthTest();
        GlStateManager._enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Frame boundary: offsets a frame never bent must not land on the next one. */
    public static void endFrame() {
        pending = false;
    }
}
