package com.lowdragmc.photon.core.mixins.accessor;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The pipeline cache of a shader reload: what it has compiled, and the shader source it compiles against — the
 * sources {@code CustomShaderAdapter} reads a custom shader's GLSL from.
 */
@Mixin(PipelineCache.class)
public interface PipelineCacheAccessor {
    @Accessor("cache")
    Map<RenderPipeline, CompiledRenderPipeline> photon$cache();

    @Accessor("shaderSource")
    ShaderSource photon$shaderSource();
}
