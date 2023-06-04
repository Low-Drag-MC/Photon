package com.lowdragmc.photon.core.mixins.accessor;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote MinecraftAccessor
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor
    static int getFps() {
        return 0;
    }
}
