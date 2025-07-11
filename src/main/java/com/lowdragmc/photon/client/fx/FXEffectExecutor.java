package com.lowdragmc.photon.client.fx;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * @author KilaBash
 * @date 2023/7/19
 * @implNote FXEffect
 */
public abstract class FXEffectExecutor implements IFXEffectExecutor {
    @Getter
    public final FX fx;
    @Getter
    public final Level level;
    @Setter
    protected Vector3f offset = new Vector3f();
    @Setter
    protected Quaternionf rotation = new Quaternionf();
    @Setter
    protected Vector3f scale = new Vector3f(1, 1, 1);
    @Setter
    protected int delay;
    @Setter
    protected boolean forcedDeath;
    @Setter
    protected boolean allowMulti;

    //runtime
    @Getter
    @Nullable
    protected FXRuntime runtime;

    protected FXEffectExecutor(FX fx, Level level) {
        this.fx = fx;
        this.level = level;
    }
}
