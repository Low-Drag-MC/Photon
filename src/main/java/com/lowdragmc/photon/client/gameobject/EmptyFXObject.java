package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegisterClient;
import lombok.Getter;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "empty", group = "fxObject", priority = -99)
@Getter
public class EmptyFXObject extends FXObject {

    public EmptyFXObject() {
    }

}
