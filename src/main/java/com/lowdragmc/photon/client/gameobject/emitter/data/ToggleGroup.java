package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IToggleConfigurable;
import lombok.Getter;
import lombok.Setter;

public class ToggleGroup implements IToggleConfigurable {
    @Getter
    @Setter
    protected boolean enable;

}
