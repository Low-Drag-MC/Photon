package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib.networking.INetworking;
import com.lowdragmc.lowdraglib.networking.LDLNetworking;
import com.lowdragmc.photon.command.BlockEffectCommand;
import com.lowdragmc.photon.command.EntityEffectCommand;
import net.minecraft.resources.ResourceLocation;

public class PhotonNetworking {

    public static final INetworking NETWORK = LDLNetworking.createNetworking(new ResourceLocation(Photon.MOD_ID, "networking"), "0.0.1");

    public static void init() {
        NETWORK.registerS2C(BlockEffectCommand.class);
        NETWORK.registerS2C(EntityEffectCommand.class);
    }

}
