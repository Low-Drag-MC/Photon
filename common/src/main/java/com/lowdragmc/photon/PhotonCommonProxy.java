package com.lowdragmc.photon;


import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.photon.gui.ParticleEditorFactory;

public class PhotonCommonProxy {
    public static void init() {
        PhotonNetworking.init();
        UIFactory.register(ParticleEditorFactory.INSTANCE);
    }

}
