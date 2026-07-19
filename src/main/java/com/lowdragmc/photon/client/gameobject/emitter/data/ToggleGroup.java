package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IToggleConfigurable;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

public class ToggleGroup implements IToggleConfigurable {
    @Getter
    @Setter
    protected boolean enable;

    // Bridge overrides so subclasses can extend serialization via super.serialize/deserialize —
    // Java only allows Interface.super calls from a DIRECT implementor.
    @Override
    public void serialize(@NotNull ValueOutput output) {
        IToggleConfigurable.super.serialize(output);
    }

    @Override
    public void deserialize(@NotNull ValueInput input) {
        IToggleConfigurable.super.deserialize(input);
    }
}
