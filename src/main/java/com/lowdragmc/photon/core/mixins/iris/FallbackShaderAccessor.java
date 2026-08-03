package com.lowdragmc.photon.core.mixins.iris;

import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.FallbackShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Iris synthesises a {@link FallbackShader} when the pack ships none of the programs in a fallback
 * chain. It carries the same framebuffer fields as {@code ExtendedShader}, so without this accessor
 * Photon would treat such a pack as "no shader pack" and draw into MC's main render target in the
 * middle of the world render — i.e. the FX simply never appear.
 */
@Mixin(FallbackShader.class)
public interface FallbackShaderAccessor {
    @Accessor
    GlFramebuffer getWritingToBeforeTranslucent();

    @Accessor
    GlFramebuffer getWritingToAfterTranslucent();

    @Accessor
    IrisRenderingPipeline getParent();

    @Accessor
    BlendModeOverride getBlendModeOverride();
}
