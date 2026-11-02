package com.lowdragmc.photon.core.mixins.accessor;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

/** The pipeline cache the last shader reload installed. */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
    @Accessor("currentPipelineCache")
    @Nullable
    static PipelineCache photon$currentPipelineCache() {
        throw new AssertionError();
    }
}
