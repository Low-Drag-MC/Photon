package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The OIT composite when Photon's FX are in it and bloom: vanilla resolves onto the 8-bit main target, clamping the
 * overbright colour Photon's bloom needs, so the sum is resolved into Photon's HDR target with vanilla's shader, bloomed
 * and copied back, and vanilla's composite draw is skipped ({@code LevelRendererMixin}). Render thread, no pass open.
 */
public final class PhotonOitComposite {

    @Nullable
    private static RenderPipeline hdrComposite;
    private static boolean reported;

    private PhotonOitComposite() {
    }

    /** Vanilla's composite into an HDR target: same shader, defines and depth write, only the format differs. */
    private static RenderPipeline hdrComposite() {
        if (hdrComposite == null) {
            hdrComposite = RenderPipelines.OIT_COMPOSITE.toBuilder()
                    .withLocation(Photon.id("pipeline/oit_composite_hdr"))
                    .withColorTargetState(0, new ColorTargetState(
                            Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), PhotonPipelines.HDR_FORMAT,
                            ColorTargetState.WRITE_ALL))
                    .build();
        }
        return hdrComposite;
    }

    /**
     * Resolves this frame's OIT sum over {@code main} in HDR and blooms it; false (and nothing done) when vanilla's
     * composite should run instead.
     */
    public static boolean composite(LevelTargetBundle targets, RenderTarget main) {
        if (!PhotonOit.drew() || !PhotonOit.blooms()) {
            return false;
        }
        try {
            return resolve(targets, main);
        } catch (RuntimeException e) {
            // the main target is only written last: vanilla's composite takes over
            if (!reported) {
                reported = true;
                Photon.LOGGER.error("Photon's OIT composite failed; vanilla's resolves the frame", e);
            }
            return false;
        }
    }

    private static boolean resolve(LevelTargetBundle targets, RenderTarget main) {
        var color = main.getColorTextureView();
        var depth = main.getDepthTextureView();
        var pipeline = PhotonPipelines.compiled(hdrComposite());
        if (color == null || depth == null || pipeline == null) {
            return false;
        }
        var width = color.getWidth(0);
        var height = color.getHeight(0);
        var target = PhotonDrawTarget.acquire(width, height);
        target.copyFrom(color, null);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon OIT composite (HDR)", target.view(), Optional.empty(), depth, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Sampler0", targets.accumulate.get().getColorTextureView(), nearest);
            for (int i = 0; i < LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT; i++) {
                pass.setUniform("Coeff" + i, targets.transmittance.get(i).get().getColorTextureView(), nearest);
            }
            // the bounds before the cull, as vanilla's composite reads them: their nearest depth is what it writes
            pass.setUniform("DepthBoundsSampler", targets.depthBounds.get().getColorTextureView(), nearest);
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
        }
        PhotonBloom.acquire(width, height).run(target.view());
        target.compositeTo(color, null);
        PhotonOit.noteComposited();
        return true;
    }
}
