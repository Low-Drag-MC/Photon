package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import lombok.Getter;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@Getter
public class EmptyFXObject extends FXObject {

    @LDLRegisterClient(name = "empty", registry = "photon:fx_object", priority = -99)
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new EmptyFXObject();
        }

        @Override
        public IGuiTexture icon() {
            return IGuiTexture.EMPTY;
        }
    };

    public EmptyFXObject() {
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

}
