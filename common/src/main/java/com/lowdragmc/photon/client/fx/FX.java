package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote FX
 */
public record FX(ResourceLocation location, List<IParticleEmitter> emitters) {

    public Collection<? extends IParticleEmitter> generateEmitters() {
        List<IParticleEmitter> list = new ArrayList<>(emitters.size());
        for (IParticleEmitter emitter : emitters) {
            list.add(emitter.copy());
        }
        return list;
    }

    @Override
    public int hashCode() {
        return location.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof FX fx) {
            return fx.location.equals(location);
        }
        return false;
    }
}
