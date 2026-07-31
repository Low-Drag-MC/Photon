package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.Optional;

/**
 * Writing a finished composite back into the frame.
 *
 * <p>What the chain hands us — the HDR draw target, or the post-effect chain's final target — is a
 * COMPLETE picture: a copy of the frame plus everything Photon drew onto it. So the write-back must
 * REPLACE the destination's color, never blend against it. Blending weights the result by the source's
 * alpha, and that alpha is not a coverage value: the authored particle blend modes store their own
 * alpha rather than accumulating it, and a post-effect graph may output anything at all. Wherever that
 * alpha lands near 0, an alpha-blended write-back resurrects the pre-Photon frame and erases every
 * particle already composited into it — a quad-shaped hole showing the bare scene through it.
 *
 * <p>Alpha is masked OUT of the write ({@link ColorTargetState#WRITE_COLOR}) so the destination keeps
 * its own: the editor's PIP texture carries premultiplied coverage in that channel (writing it turns
 * the transparent backdrop opaque black), and MC's main target is already opaque. That masking is also
 * why this is a draw and not a {@code PhotonFramebufferBlit} — {@code glBlitFramebuffer} bypasses the
 * fragment pipeline entirely and ignores the color mask, so it would carry the effect's alpha across.
 *
 * <p>Render thread only, outside any open render pass.</p>
 */
public final class SceneBlit {

    private static RenderPipeline pipeline;

    private SceneBlit() {}

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = PhotonFullscreenPass.builder(Photon.id("core/bloom_blit"))
                    .withLocation(Photon.id("pipeline/postfx_writeback"))
                    .withSampler("inputSampler")
                    .withShaderDefine("OUTPUT_SCALE", 1f)
                    .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_COLOR))
                    .build();
        }
        return pipeline;
    }

    /** Replace {@code to}'s RGB with {@code from}'s, leaving its alpha untouched. */
    public static void writeBack(GpuTextureView from, GpuTextureView to) {
        PhotonFullscreenPass.draw("Photon postfx write-back", pipeline(), to, pass ->
                pass.bindTexture("inputSampler", from,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
    }
}
