package com.lowdragmc.photon;


import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.networking.LDLNetworking;
import com.lowdragmc.photon.command.BlockEffectCommand;
import com.lowdragmc.photon.command.EntityEffectCommand;
import com.lowdragmc.photon.gui.ParticleEditorFactory;

public class CommonProxy {
    public static void init() {
        UIFactory.register(ParticleEditorFactory.INSTANCE);
        LDLNetworking.NETWORK.registerS2C(BlockEffectCommand.class);
        LDLNetworking.NETWORK.registerS2C(EntityEffectCommand.class);
    }

}
