package com.lowdragmc.photon.client.fx;

import lombok.Getter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.level.Level;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote EditorEffect
 */
@OnlyIn(Dist.CLIENT)
public class FXProjectEffect implements IEffect {
    @Getter
    public final Level level;

    public FXProjectEffect(Level level) {
        this.level = level;
    }

}
