package com.lowdragmc.photon.core.mixins.iris;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ExtendedShader.class)
public interface ExtendedShaderAccessor {
    @Accessor
    GlFramebuffer getWritingToBeforeTranslucent();
    @Accessor
    GlFramebuffer getWritingToAfterTranslucent();
    @Accessor
    IrisRenderingPipeline getParent();
}
