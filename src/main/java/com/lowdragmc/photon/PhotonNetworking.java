package com.lowdragmc.photon;

import com.lowdragmc.photon.command.BlockEffectCommand;
import com.lowdragmc.photon.command.EntityEffectCommand;
import com.lowdragmc.photon.command.RemoveBlockEffectCommand;
import com.lowdragmc.photon.command.RemoveEntityEffectCommand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class PhotonNetworking {

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Photon.MOD_ID);

        registrar.playToClient(BlockEffectCommand.TYPE, BlockEffectCommand.CODEC, BlockEffectCommand::execute);
        registrar.playToClient(EntityEffectCommand.TYPE, EntityEffectCommand.CODEC, EntityEffectCommand::execute);
        registrar.playToClient(RemoveBlockEffectCommand.TYPE, RemoveBlockEffectCommand.CODEC, RemoveBlockEffectCommand::execute);
        registrar.playToClient(RemoveEntityEffectCommand.TYPE, RemoveEntityEffectCommand.CODEC, RemoveEntityEffectCommand::execute);
    }
}
