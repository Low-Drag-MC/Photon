package com.lowdragmc.photon.client.fx;

import lombok.Getter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.Level;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote EditorEffect
 */
@Environment(EnvType.CLIENT)
public class FXProjectEffect implements IEffect {
    @Getter
    public final Level level;

    public FXProjectEffect(Level level) {
        this.level = level;
    }

}
