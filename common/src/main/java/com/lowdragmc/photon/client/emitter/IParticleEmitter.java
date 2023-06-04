package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector;
import com.lowdragmc.lowdraglib.syncdata.IAutoPersistedSerializable;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.integration.LDLibPlugin;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.Map;


/**
 * @author KilaBash
 * @date 2023/6/2
 * @implNote IParticleEmitter
 */
public interface IParticleEmitter extends IConfigurable, IAutoPersistedSerializable {

    default LParticle self() {
        return (LParticle) this;
    }

    default void reset() {
        self().resetParticle();
    }

    default int getParticleAmount() {
        var amount = 0;
        for (var list : getParticles().values()) {
            amount += list.size();
        }
        return amount;
    }

    default void emmitToLevel(Level level, double x, double y, double z) {
        self().setPos(x, y, z, true);
        self().setLevel(level);
        self().addParticle(null);
    }

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
    Map<ParticleRenderType, LinkedList<LParticle>> getParticles();

    /**
     * emit particle from this emitter
     */
    boolean emitParticle(LParticle particle);

    boolean isVisible();
    void setVisible(boolean visible);
}
