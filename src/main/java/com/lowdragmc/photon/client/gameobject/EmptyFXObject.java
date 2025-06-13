package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import lombok.Getter;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "empty", registry = "photon:fx_object", priority = -99)
@Getter
public class EmptyFXObject extends FXObject {

    public EmptyFXObject() {
    }

}
