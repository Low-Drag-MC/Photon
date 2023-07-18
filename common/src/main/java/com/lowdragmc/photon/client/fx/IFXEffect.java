package com.lowdragmc.photon.client.fx;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote IFXEffect
 */
@Environment(EnvType.CLIENT)
public interface IFXEffect extends IEffect{
    /**
     * get all emitters included in this effect.
     */
    FX getFx();

    /**
     * set effect offset
     */
    void setOffset(double x, double y, double z);

    /**
     * set effect rotation
     */
    void setRotation(double x, double y, double z);

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
     * start effect。
     */
    void start();

}
