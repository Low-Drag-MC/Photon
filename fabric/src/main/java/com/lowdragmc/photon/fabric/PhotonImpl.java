package com.lowdragmc.photon.fabric;

import com.lowdragmc.photon.CommonProxy;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.ServerCommands;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class PhotonImpl implements ModInitializer {

    @Override
    public void onInitialize() {
        Photon.init();
        // register server commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ServerCommands.createServerCommands().forEach(dispatcher::register));
        // init common features
        CommonProxy.init();
    }

    public static boolean isStencilEnabled(RenderTarget fbo) {
        return false;
    }

    public static boolean useCombinedDepthStencilAttachment() {
        return false;
    }

}
