package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector;
import com.lowdragmc.lowdraglib.syncdata.IAutoPersistedSerializable;
import com.lowdragmc.photon.client.fx.IFXEffect;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.integration.LDLibPlugin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


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
        return getParticles().size();
    }

    /**
     * emit to a given level.
     */
    default void emmitToLevel(Level level, double x, double y, double z) {
        self().setPos(x, y, z, true);
        self().setLevel(level);
        self().addParticle(null);
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


    /**
     * emitter name unique for one project
     */
    String getName();

    void setName(String name);

    /**
     * particles emitted from this emitter
     */
    List<LParticle> getParticles();

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
