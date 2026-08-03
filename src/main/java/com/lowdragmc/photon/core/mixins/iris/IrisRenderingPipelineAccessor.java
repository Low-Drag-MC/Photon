package com.lowdragmc.photon.core.mixins.iris;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code defaultFB} / {@code defaultFBAlt} are built as
 * {@code createFramebufferWritingTo{Main,Alt}(new int[]{ packDirectives.getFallbackTex() })} — a
 * single-attachment framebuffer on the pack's scene-colour target with the ping-pong flip already
 * resolved. That makes {@code defaultFB(Alt).getColorAttachment(0)} an exact, free answer to "which
 * texture currently holds the scene colour", with no need to reason about the flipped sets.
 */
@Mixin(IrisRenderingPipeline.class)
public interface IrisRenderingPipelineAccessor {
    @Accessor("renderTargets")
    RenderTargets photon$renderTargets();

    @Accessor("isRenderingWorld")
    boolean photon$isRenderingWorld();

    @Accessor("defaultFB")
    GlFramebuffer photon$defaultFB();

    @Accessor("defaultFBAlt")
    GlFramebuffer photon$defaultFBAlt();
}
