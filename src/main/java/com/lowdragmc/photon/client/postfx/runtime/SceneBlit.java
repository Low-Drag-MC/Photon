package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.client.shader.LDLibShaders;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * Writing a finished composite back into the frame.
 *
 * <p>What the pipeline hands us — {@code DRAW_TARGET}, or the post-effect chain's final target — is a
 * COMPLETE picture: a copy of the frame plus everything Photon drew onto it. So the write-back must
 * REPLACE the destination's color, never blend against it. Blending weights the result by the source's
 * alpha, and that alpha is not a coverage value: the authored particle blend modes store their own
 * alpha rather than accumulating it ({@code dstAlpha = ZERO}, so every draw overwrites what was there),
 * and a post-effect graph may output anything at all. Wherever that alpha lands near 0, an
 * alpha-blended write-back resurrects the pre-Photon frame and erases every particle already
 * composited into it — a quad-shaped hole showing the bare scene through it.
 *
 * <p>The builtin bloom hid this: its final pass ends on {@code alpha = 1}, which makes
 * {@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA} degenerate into exactly the replace done here. Turn bloom off
 * (or let a custom effect run last) and the holes appear.
 *
 * <p>Alpha is masked out of the write so the destination keeps its own — Iris gbuffers carry meaning
 * in that channel, and MC's main target is already opaque. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class SceneBlit {

    private SceneBlit() {}

    /** Replace {@code to}'s color with {@code from}'s. */
    public static void writeBack(RenderTarget from, RenderTarget to) {
        to.bindWrite(true);
        writeBackToBound(from.getColorTextureId());
    }

    public static void writeBackToBound(int colorTexture) {
        writeBackToBound(colorTexture, null);
    }

    /**
     * Same, into whatever framebuffer the caller already bound — Iris keeps its own framebuffers and
     * binds them itself, so the binding is left untouched here.
     *
     * <p><b>Applying the shader comes first, the render state second — never the other way round.</b>
     * Applying a shader changes render state behind our back: vanilla re-applies the blend mode declared
     * in the shader JSON, and under a shader pack Iris <i>locks</i> the depth and colour masks at that
     * moment so that shaders it does not manage cannot write into its gbuffers — while that lock is held
     * it silently swallows every {@code GlStateManager._colorMask} call. State set before apply() is
     * therefore discarded, and the blit writes nothing at all.
     *
     * <p>{@code afterShaderApply} is the seam between the two: Iris uses it to release the lock that
     * apply() just took.
     *
     * <p>Leaves the render state {@code ShaderUtils.fastBlit} used to leave (depth write + test on,
     * full color mask, blend enabled on the default func): every call site was written against that
     * contract.
     */
    public static void writeBackToBound(int colorTexture, @Nullable Runnable afterShaderApply) {
        RenderSystem.assertOnRenderThread();
        var shader = LDLibShaders.getBlitShader();
        shader.setSampler("DiffuseSampler", colorTexture);
        shader.apply();
        if (afterShaderApply != null) {
            afterShaderApply.run();
        }

        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        drawFullscreenQuad();
        shader.clear();

        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableDepthTest();
        GlStateManager._enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public static void drawFullscreenQuad() {
        var tesselator = RenderSystem.renderThreadTesselator();
        var buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1, 1, 0);
        buffer.addVertex(-1, -1, 0);
        buffer.addVertex(1, -1, 0);
        buffer.addVertex(1, 1, 0);
        BufferUploader.draw(buffer.buildOrThrow());
    }
}
