package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.annotation.Nullable;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote IEffect
 */
@Environment(EnvType.CLIENT)
public interface IEffect {
    /**
     * get all emitters included in this effect.
     */
    List<IParticleEmitter> getEmitters();

    /**
     * update each emitter during their duration,
     * @param emitter emitter
     * @return true - block emitter origin tick logic.
     */
    boolean updateEmitter(IParticleEmitter emitter);

    /**
     * Get emitter by name
     */
    @Nullable
    default IParticleEmitter getEmitterByName(String name) {
        for (var emitter : getEmitters()) {
            if (emitter.getName().equals(name)) return emitter;
        }
        return null;
    };
}
