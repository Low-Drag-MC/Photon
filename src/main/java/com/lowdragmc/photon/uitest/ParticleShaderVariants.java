package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Compile every {@code #ifdef} branch of {@code photon:particle.glsl} on the real driver.
 *
 * <p>The include is a multi-way {@code #ifdef} chain (one branch per GPU-instanced geometry family, plus
 * the CPU vertex layout), and every branch declares its own attribute set and builds its own
 * {@code ParticleData} — tangent frame and object&lt;-&gt;world matrices included. Nothing else in the build
 * ever compiles them: 26.1 defers pipeline compilation to the first draw, so an invalid branch surfaces
 * in-game, on whichever emitter happens to use that family, and nowhere else.
 *
 * <p>Two vertex stages include it and both are checked against every branch: the material stage
 * ({@code core/particle}) and the CustomMask sub-pass ({@code core/mask}). They read the same
 * {@code getParticleData()} but declare their own outputs, so a branch can compile in one and not the
 * other. The wireframe row is a third LINK: the same {@code core/particle} vertex stage against the
 * overlay's {@code core/inverse} fragment stage, whose varyings must still match per branch.
 *
 * <p>Driven off {@link PhotonPipelines.InstancedVariant} rather than a hand-written list, so a new variant
 * is covered the day it is added.
 */
final class ParticleShaderVariants {

    private ParticleShaderVariants() {
    }

    /** Precompile every vertex stage against every vertex layout Photon ships, reporting each. */
    static void checkAll(TestContext ctx) {
        var key = PhotonPipelines.ParticlePipelineKey.DEFAULT;
        var quads = VertexFormat.Mode.QUADS;
        check(ctx, "material / CPU vertex layout", PhotonPipelines.hdrParticle(key));
        check(ctx, "mask / CPU vertex layout", PhotonPipelines.mask(null, quads));
        check(ctx, "wireframe / CPU vertex layout",
                PhotonPipelines.hdrParticle(PhotonPipelines.ParticlePipelineKey.wireframe(quads)));
        for (var variant : PhotonPipelines.InstancedVariant.values()) {
            var defines = String.join("+", variant.defines);
            check(ctx, "material / " + defines, PhotonPipelines.instancedHdrParticle(variant, key));
            check(ctx, "mask / " + defines, PhotonPipelines.mask(variant, quads));
            check(ctx, "wireframe / " + defines, PhotonPipelines.instancedHdrParticle(variant,
                    PhotonPipelines.ParticlePipelineKey.wireframe(quads)));
        }
    }

    private static void check(TestContext ctx, String name, RenderPipeline pipeline) {
        Throwable failure = null;
        var valid = false;
        try {
            // 26.1 logs the driver's compile/link error and hands back an invalid pipeline rather than
            // throwing, so BOTH outcomes have to be treated as a failure here.
            valid = RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
        } catch (Throwable e) {
            failure = e;
        }
        ctx.check("%s compiles".formatted(name), failure == null && valid, "compiled",
                failure != null ? String.valueOf(failure.getMessage())
                        : "the driver rejected it (the GLSL error is in the log)");
    }
}
