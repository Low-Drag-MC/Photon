package com.lowdragmc.photon.core.mixins.accessor;

import com.mojang.blaze3d.shaders.BlendMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote BlendModeAccessor
 */
@Mixin(BlendMode.class)
public interface BlendModeAccessor {

    @Accessor
    static BlendMode getLastApplied() {
        return null;
    }

    @Accessor
    static void setLastApplied(BlendMode blendMode) {

    }

}
