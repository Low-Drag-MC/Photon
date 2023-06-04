package com.lowdragmc.photon.client.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector;
import com.lowdragmc.lowdraglib.syncdata.IAutoPersistedSerializable;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.integration.LDLibPlugin;
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
        var wrapper = LDLibPlugin.REGISTER_SHAPES.get(type);
        if (wrapper != null) {
            var shape = wrapper.creator().get();
            shape.deserializeNBT(tag);
            return shape;
        }
        return null;
    }

    void nextPosVel(LParticle particle, Vector3 position, Vector3 rotation, Vector3 scale);
}
