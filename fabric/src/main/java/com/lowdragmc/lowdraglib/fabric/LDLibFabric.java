package com.lowdragmc.lowdraglib.fabric;

import io.github.fabricators_of_create.porting_lib.util.EnvExecutor;
import com.lowdragmc.lowdraglib.LDLib;
import net.fabricmc.api.ModInitializer;

public class LDLibFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        LDLib.init();
        LDLib.LOGGER.info(EnvExecutor.unsafeRunForDist(
                () -> () -> "{} is accessing Porting Lib on a Fabric client!",
                () -> () -> "{} is accessing Porting Lib on a Fabric server!"
                ), LDLib.NAME);
        // on fabric, Registrates must be explicitly finalized and registered.
//        LDLib.REGISTRATE.register();
    }
}
