package com.lowdragmc.photon.core.mixins.accessor;

import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote ShaderInstanceAccessor
 */
@Mixin(ShaderInstance.class)
public interface ShaderInstanceAccessor {
    @Accessor
    BlendMode getBlend();

    @Accessor
    Map<String, Object> getSamplerMap();

    @Accessor
    Map<String, Uniform> getUniformMap();
}
