package com.lowdragmc.photon;

import com.lowdragmc.photon.client.ClientCommands;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

public class PhotonCommonProxy {

    public PhotonCommonProxy(IEventBus eventBus) {
        eventBus.addListener(PhotonNetworking::registerPayloads);
        eventBus.register(this);
        PhotonRegistries.init();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        List<LiteralArgumentBuilder<CommandSourceStack>> commands = ServerCommands.createServerCommands();
        commands.forEach(dispatcher::register);
    }

}
