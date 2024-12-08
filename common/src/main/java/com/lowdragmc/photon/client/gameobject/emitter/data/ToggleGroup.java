package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IToggleConfigurable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote ToggleGroup
 */
public class ToggleGroup implements IToggleConfigurable {

    @Getter
    @Setter
    @Persisted
    protected boolean enable;

}
