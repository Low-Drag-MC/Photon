package com.lowdragmc.photon.core.mixins.accessor;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote ParticleEngineAccessor (26.1: particles are bucketed into ParticleGroups, keyed by
 * render-type identity)
 */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor
    Map<ParticleRenderType, ParticleGroup<?>> getParticles();
}
