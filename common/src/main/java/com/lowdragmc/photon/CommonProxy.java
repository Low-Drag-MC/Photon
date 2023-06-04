package com.lowdragmc.photon;


import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.photon.gui.ParticleEditorFactory;

public class CommonProxy {
    public static void init() {
        UIFactory.register(ParticleEditorFactory.INSTANCE);
    }

}
