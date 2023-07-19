package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/7/19
 * @implNote FXEffect
 */
public abstract class FXEffect implements IFXEffect {
    @Getter
    public final FX fx;
    @Getter
    public final Level level;
    @Setter
    protected double xOffset, yOffset, zOffset;
    @Setter
    protected double xRotation, yRotation, zRotation;
    @Setter
    protected int delay;
    @Setter
    protected boolean forcedDeath;
    @Setter
    protected boolean allowMulti;

    //runtime
    @Getter
    protected final List<IParticleEmitter> emitters = new ArrayList<>();
    protected final Map<String, IParticleEmitter> cache = new HashMap<>();

    protected FXEffect(FX fx, Level level) {
        this.fx = fx;
        this.level = level;
    }

    @Override
    public void setOffset(double x, double y, double z) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
    }

    @Override
    public void setRotation(double x, double y, double z) {
        this.xRotation = x;
        this.yRotation = y;
        this.zRotation = z;
    }

    @Nullable
    @Override
    public IParticleEmitter getEmitterByName(String name) {
        return cache.computeIfAbsent(name, s -> IFXEffect.super.getEmitterByName(name));
    }
}
