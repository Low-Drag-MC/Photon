package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.syncdata.IAutoPersistedSerializable;
import com.lowdragmc.photon.integration.PhotonLDLibPlugin;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.particle.LParticle;
import net.minecraft.nbt.CompoundTag;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote IShape
 */
public interface IShape extends IConfigurable, IAutoPersistedSerializable {

    static CompoundTag serializeWrapper(IShape shape) {
        return shape.serializeNBT();
    }

    static IShape deserializeWrapper(CompoundTag tag) {
        var type = tag.getString("_type");
        var wrapper = PhotonLDLibPlugin.REGISTER_SHAPES.get(type);
        if (wrapper != null) {
            var shape = wrapper.creator().get();
            shape.deserializeNBT(tag);
            return shape;
        }
        return null;
    }

    void nextPosVel(LParticle particle, Vector3f position, Vector3f rotation, Vector3f scale);
}
