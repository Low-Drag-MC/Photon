package com.lowdragmc.photon.client;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonCommonProxy;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMeshCache;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;


@OnlyIn(Dist.CLIENT)
public class PhotonClientProxy extends PhotonCommonProxy {

    public PhotonClientProxy(IEventBus eventBus) {
        super(eventBus);
        eventBus.addListener(this::clientSetup);
        eventBus.addListener(this::shaderRegistry);
        eventBus.addListener(this::registerModels);
        eventBus.addListener(this::registerReloadListeners);
    }

    @SubscribeEvent
    public void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(PhotonMeshCache.INSTANCE);
    }

    @SubscribeEvent
    public void clientSetup(final FMLClientSetupEvent e) {
        e.enqueueWork(PhotonShaders::init);
        // Touch the registry to trigger annotation scanning; classes annotated with @NodeAttribute
        // bound to ShaderGraph self-register (mirrors KilaGraph's own registry bootstrap).
        Photon.LOGGER.info("Photon shader graph nodes loaded: {}",
                com.lowdragmc.photon.client.shadergraph.ShaderGraph.NODE_REGISTRY.getNodeClasses().size());
    }

    @SubscribeEvent
    public void shaderRegistry(RegisterShadersEvent event) {
        PhotonShaders.registerShaders(event);
    }

    @SubscribeEvent
    public void registerModels(ModelEvent.RegisterAdditional event) {
        // load all models under the ldlib folder
        for (var entry : Minecraft.getInstance().getResourceManager().listResources("models",
                id -> id.getNamespace().equals(Photon.MOD_ID) && id.getPath().endsWith(".json")).entrySet()) {
            var modelLocation = ResourceLocation.fromNamespaceAndPath(
                    entry.getKey().getNamespace(),
                    entry.getKey().getPath()
                            .replace("models/", "")
                            .replace(".json", ""));
            event.register(ModelResourceLocation.standalone(modelLocation));
        }
        MeshResource.INSTANCE.onAdditionalModel(event::register);
    }
}
