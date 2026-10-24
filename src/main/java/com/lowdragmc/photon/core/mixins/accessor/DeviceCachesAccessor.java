package com.lowdragmc.photon.core.mixins.accessor;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * A device backend's compiled pipelines and shader modules, on either backend. Both only free them all at once, on a
 * resource reload; Photon frees those of a pipeline it will never draw again ({@code PhotonPipelineReleases}).
 */
@Mixin(targets = {"com.mojang.blaze3d.opengl.GlDevice", "com.mojang.blaze3d.vulkan.VulkanDevice"})
public interface DeviceCachesAccessor {
    /** Values are the backend's {@code CompiledRenderPipeline}. */
    @Accessor("pipelineCache")
    Map<RenderPipeline, Object> photon$pipelineCache();

    /** Keyed by {@link ShaderKeyAccessor}s. */
    @Accessor("shaderCache")
    Map<Object, Object> photon$shaderCache();
}
