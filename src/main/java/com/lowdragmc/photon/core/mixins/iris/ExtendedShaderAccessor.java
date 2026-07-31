package com.lowdragmc.photon.core.mixins.iris;

import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ExtendedShader.class)
public interface ExtendedShaderAccessor {
    @Accessor
    GlFramebuffer getWritingToBeforeTranslucent();
    @Accessor
    GlFramebuffer getWritingToAfterTranslucent();
    @Accessor
    IrisRenderingPipeline getParent();

    /**
     * The pack's {@code blend.<program>} directive. {@link BlendModeOverride#OFF} means the program
     * <b>overwrites</b> its draw buffers — which is how a pack says "this target holds encoded
     * gbuffer data, not colour you may blend into".
     */
    @Accessor
    BlendModeOverride getBlendModeOverride();

    /** The pack's per-buffer {@code blend.<program>.colortexN} directives. */
    @Accessor
    List<BufferBlendOverride> getBufferBlendOverrides();
}
