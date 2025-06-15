package com.lowdragmc.photon;

import com.lowdragmc.photon.command.EntityEffectCommand;
import com.lowdragmc.photon.command.FxLocationArgument;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class PhotonCommonProxy {
    static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARG_TYPES = DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, Photon.MOD_ID);
    static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<FxLocationArgument>> FX_LOCATION_ARG_TYPE
            = ARG_TYPES.register("fx_location", () -> ArgumentTypeInfos.registerByClass(FxLocationArgument.class,
            SingletonArgumentInfo.contextFree(FxLocationArgument::new)));
    static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<EntityEffectCommand.AutoRotateType>> AUTO_ROTATE_ARG_TYPE
            = ARG_TYPES.register("fx_auto_rotate", () -> ArgumentTypeInfos.registerByClass(EntityEffectCommand.AutoRotateType.class,
            SingletonArgumentInfo.contextFree(EntityEffectCommand.AutoRotateType::new)));

    public PhotonCommonProxy(IEventBus eventBus) {
        eventBus.addListener(PhotonNetworking::registerPayloads);
        ARG_TYPES.register(eventBus);
        PhotonRegistries.init();
    }
}
