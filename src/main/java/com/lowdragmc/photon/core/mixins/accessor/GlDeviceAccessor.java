package com.lowdragmc.photon.core.mixins.accessor;

import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The GL device's compiled programs and shader objects. 26.1 only frees them all at once, on a resource reload; Photon
 * frees those of a pipeline it will never draw again ({@code PhotonPipelines.release}).
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public interface GlDeviceAccessor {
    @Accessor("pipelineCache")
    Map<RenderPipeline, GlRenderPipeline> photon$pipelineCache();

    /** Keyed by {@link ShaderKeyAccessor}s. */
    @Accessor("shaderCache")
    Map<Object, GlShaderModule> photon$shaderCache();
}
