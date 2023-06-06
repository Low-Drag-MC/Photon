package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote IFXEffect
 */
@Environment(EnvType.CLIENT)
public interface IFXEffect {

    /**
     * set effect offset
     */
    void setOffset(double x, double y, double z);

    /**
     * set effect delay
     */
    void setDelay(int delay);

    /**
     * Whether to remove particles directly when the bound object invalid.
     * <br>
     * default - wait for particles death.
     */
    void setForcedDeath(boolean forcedDeath);

    /**
     * Allows multiple identical effects to be bound to a same object。
     */
    void setAllowMulti(boolean allowMulti);

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
     * start effect。
     */
    void start();

}
