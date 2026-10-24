package com.lowdragmc.photon.core.mixins.accessor;

import net.minecraft.client.renderer.ShaderDefines;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A key of {@link DeviceCachesAccessor#photon$shaderCache()}; the same record on both backends. */
@Mixin(targets = {"com.mojang.blaze3d.opengl.GlDevice$ShaderCompilationKey",
        "com.mojang.blaze3d.vulkan.VulkanDevice$ShaderCompilationKey"})
public interface ShaderKeyAccessor {
    @Accessor("id")
    Identifier photon$id();

    @Accessor("type")
    ShaderType photon$type();

    @Accessor("defines")
    ShaderDefines photon$defines();
}
