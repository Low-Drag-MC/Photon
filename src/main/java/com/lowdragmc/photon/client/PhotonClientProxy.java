package com.lowdragmc.photon.client;

import com.lowdragmc.photon.PhotonCommonProxy;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public class PhotonClientProxy extends PhotonCommonProxy {

    public PhotonClientProxy(IEventBus eventBus) {
        super(eventBus);
        eventBus.addListener(this::clientSetup);
        eventBus.addListener(this::shaderRegistry);
    }

    @SubscribeEvent
    public void clientSetup(final FMLClientSetupEvent e) {
        e.enqueueWork(PhotonShaders::init);
    }

    @SubscribeEvent
    public void shaderRegistry(RegisterShadersEvent event) {
        PhotonShaders.registerShaders(event);
    }
}
