package com.lowdragmc.photon.client;

import com.lowdragmc.photon.PhotonCommonProxy;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class PhotonClientProxy extends PhotonCommonProxy {

    public PhotonClientProxy(IEventBus eventBus) {
        super(eventBus);
    }

    @SubscribeEvent
    public void clientSetup(final FMLClientSetupEvent e) {
        e.enqueueWork(() -> {
            PhotonShaders.init();
        });
    }


    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        List<LiteralArgumentBuilder<CommandSourceStack>> commands = ClientCommands.createClientCommands();
        commands.forEach(dispatcher::register);
    }
}
