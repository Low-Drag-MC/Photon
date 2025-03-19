package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib.gui.editor.ILDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.lowdraglib.syncdata.IAutoPersistedSerializable;
import com.lowdragmc.lowdraglib.utils.DummyWorld;
import com.lowdragmc.photon.client.fx.IEffect;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.integration.PhotonLDLibPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * FXObject is a scene object that is used for FXRuntime.
 * <br>
 * e.g. {@link Emitter}
 */
public interface IFXObject extends ISceneObject, IAutoPersistedSerializable, IConfigurable, ILDLRegisterClient {

    @Nullable
    static IFXObject deserializeWrapper(CompoundTag tag) {
        var wrapper = PhotonLDLibPlugin.REGISTER_FX_OBJECTS.get(tag.getString("_type"));
        if (wrapper != null) {
            var fxObject = wrapper.creator().get();
            fxObject.deserializeNBT(tag);
            return fxObject;
        }
        return null;
    }

    /**
     * emitter name unique for one project
     */
    String getName();

    void setName(String name);

    Level getLevel();

    void setLevel(Level level);

    boolean isAlive();

    /**
     * should render particle
     */
    boolean isVisible();

    /**
     * set particle visible
     */
    void setVisible(boolean visible);

    void setEffect(IEffect effect);

    IEffect getEffect();

    /**
     * force - remove without waiting.
     */
    void remove(boolean force);

    /**
     * reset runtime data
     */
    default void reset() {

    }

    @Override
    default CompoundTag serializeNBT() {
        return IAutoPersistedSerializable.super.serializeNBT();
    }

    @Override
    default void deserializeNBT(CompoundTag tag) {
        IAutoPersistedSerializable.super.deserializeNBT(tag);
    }

    /**
     * copy this object
     */
    default IFXObject deepCopy() {
        return deserializeWrapper(serializeNBT());
    }

    default IFXObject shallowCopy() {
        return deepCopy();
    }

    /**
     * deep copy this object
     */
    default IFXObject copy(boolean deep) {
        return deep ? deepCopy() : shallowCopy();
    }

    /**
     * emit to a given level.
     */
    default void emmit(IEffect effect) {
        emmit(effect, null, null, null);
    }

    default void emmit(IEffect effect, @Nullable Vector3f position, @Nullable Quaternionf rotation, @Nullable Vector3f scale) {
        setEffect(effect);
        if (position != null) {
            updatePos(position);
        }
        if (rotation != null) {
            updateRotation(rotation);
        }
        if (scale != null) {
            updateScale(scale);
        }
        setLevel(effect.getLevel());
        if (this instanceof Particle particle) {
            if (effect.getLevel() instanceof DummyWorld dummyWorld) {
                var particleManager = dummyWorld.getParticleManager();
                if (particleManager != null) {
                    particleManager.addParticle(particle);
                }
            } else {
                Minecraft.getInstance().particleEngine.add(particle);
            }
        }
    }

    // transform
    default void updatePos(Vector3f newPos) {
        transform().position(newPos);
    }

    default void updateRotation(Quaternionf newRot) {
        transform().rotation(newRot);
    }

    default void updateRotation(Vector3f newRot) {
        transform().rotation(new Quaternionf().rotationXYZ(newRot.x, newRot.y, newRot.z));
    }

    default void updateScale(Vector3f newScale) {
        transform().scale(newScale);
    }

    default void copyTransformFrom(IFXObject fxObject) {
        copyTransformFrom(fxObject, true, true);
    }

    default void copyTransformFrom(IFXObject fxObject, boolean local, boolean copyParent) {
        if (local) {
            transform().localPosition(fxObject.transform().localPosition());
            transform().localRotation(fxObject.transform().localRotation());
            transform().localScale(fxObject.transform().localScale());
        } else {
            transform().set(fxObject.transform());
        }
        if (copyParent) {
            transform().parent(fxObject.transform().parent());
        }
    }
}
