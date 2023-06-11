package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.syncdata.IAutoPersistedSerializable;
import com.lowdragmc.lowdraglib.utils.DummyWorld;
import com.lowdragmc.photon.client.fx.IFXEffect;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.integration.LDLibPlugin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;


/**
 * @author KilaBash
 * @date 2023/6/2
 * @implNote IParticleEmitter
 */
@Environment(EnvType.CLIENT)
public interface IParticleEmitter extends IConfigurable, IAutoPersistedSerializable {

    default LParticle self() {
        return (LParticle) this;
    }

    /**
     * reset runtime data
     */
    default void reset() {
        self().resetParticle();
    }

    /**
     * get amount of existing particle which emitted from it.
     */
    default int getParticleAmount() {
        return getParticles().values().stream().mapToInt(Collection::size).sum();
    }

    /**
     * emit to a given level.
     */
    default void emmitToLevel(Level level, double x, double y, double z) {
        self().setPos(x, y, z, true);
        self().setLevel(level);
        self().prepareForEmitting(null);
        if (level instanceof DummyWorld dummyWorld) {
            ParticleManager particleManager = dummyWorld.getParticleManager();
            if (particleManager != null) {
                particleManager.addParticle(self());
            }
        } else {
            Minecraft.getInstance().particleEngine.add(self());
        }
    }

    /**
     * emitter type
     */
    default String getEmitterType() {
        return name();
    }

    @Nullable
    static IParticleEmitter deserializeWrapper(CompoundTag tag) {
        var wrapper = LDLibPlugin.REGISTER_EMITTERS.get(tag.getString("_type"));
        if (wrapper != null) {
            var emitter = wrapper.creator().get();
            emitter.deserializeNBT(tag);
            return emitter;
        }
        return null;
    }

    default IParticleEmitter copy() {
        return copy(false);
    }

    default IParticleEmitter copy(boolean deep) {
        return deserializeWrapper(serializeNBT());
    }


    /**
     * emitter name unique for one project
     */
    String getName();

    void setName(String name);

    /**
     * particles emitted from this emitter
     */
    Map<ParticleRenderType, Queue<LParticle>> getParticles();

    /**
     * emit particle from this emitter
     */
    boolean emitParticle(LParticle particle);

    /**
     * should render particle
     */
    boolean isVisible();

    /**
     * set particle visible
     */
    void setVisible(boolean visible);

    /**
     * use bloom effect
     */
    boolean usingBloom();

    void setFXEffect(IFXEffect effect);

}
