package com.lowdragmc.photon.forge;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonCommonProxy;
import com.lowdragmc.photon.ServerCommands;
import com.lowdragmc.photon.command.FxLocationArgument;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class CommonProxyImpl {
    static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARG_TYPES = DeferredRegister.create(ForgeRegistries.COMMAND_ARGUMENT_TYPES, Photon.MOD_ID);
    static final RegistryObject<ArgumentTypeInfo<FxLocationArgument, ?>> FX_LOCATION_ARG_TYPE = ARG_TYPES.register("fx_location", () -> SingletonArgumentInfo.contextFree(FxLocationArgument::new));

    public CommonProxyImpl() {
        // used for forge events (ClientProxy + CommonProxy)
        IEventBus eventBus = FMLJavaModLoadingContext.get().getModEventBus();
        eventBus.register(this);
        // register server commands
        ARG_TYPES.register(eventBus);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommand);
        // init common features
        PhotonCommonProxy.init();
    }

    public void registerCommand(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        ServerCommands.createServerCommands().forEach(dispatcher::register);
    }
}
