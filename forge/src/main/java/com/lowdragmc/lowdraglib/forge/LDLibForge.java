package com.lowdragmc.lowdraglib.forge;

import com.lowdragmc.lowdraglib.LDLib;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(LDLib.MOD_ID)
public class LDLibForge {
    public LDLibForge() {
        LDLib.init();
        // registrate must be given the mod event bus on forge before registration
        IEventBus eventBus = FMLJavaModLoadingContext.get().getModEventBus();
//        LDLib.REGISTRATE.registerEventListeners(eventBus);
    }
}
