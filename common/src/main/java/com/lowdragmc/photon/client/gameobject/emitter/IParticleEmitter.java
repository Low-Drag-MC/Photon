package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.photon.client.fx.FXProjectEffect;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.function.Function;


/**
 * @author KilaBash
 * @date 2023/6/2
 * @implNote IParticleEmitter
 */
@Environment(EnvType.CLIENT)
public interface IParticleEmitter extends IFXObject, IConfigurable {

    default Emitter self() {
        return (Emitter) this;
    }

    /**
     * get amount of existing particle which emitted from it.
     */
    int getParticleAmount();

    Vector3f getVelocity();

    /**
     * get the box of cull.
     * <br>
     * return null - culling disabled.
     */
    @Nullable
    default AABB getCullBox(float partialTicks) {
        return null;
    }

    default boolean isDev() {
        return getEffect() instanceof FXProjectEffect;
    }

    int getAge();

    void setAge(int age);

    boolean isLooping();

    void setRGBAColor(Vector4f color);

    Vector4f getRGBAColor();

    float getT();

    float getT(float partialTicks);

    float getMemRandom(Object object);

    float getMemRandom(Object object, Function<RandomSource, Float> randomFunc);

    RandomSource getRandomSource();

}
