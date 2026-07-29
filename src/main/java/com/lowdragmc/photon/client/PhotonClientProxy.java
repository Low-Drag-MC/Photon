package com.lowdragmc.photon.client;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonCommonProxy;
import com.lowdragmc.photon.client.fx.fxpack.FXPacks;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMeshCache;
import com.lowdragmc.photon.client.render.PhotonParticleGroup;
import com.lowdragmc.photon.client.render.PhotonParticleRenderTypes;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import net.minecraft.server.packs.PackType;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;


public class PhotonClientProxy extends PhotonCommonProxy {

    public PhotonClientProxy(IEventBus eventBus) {
        super(eventBus);
        eventBus.addListener(this::clientSetup);
        eventBus.addListener(this::registerModels);
        eventBus.addListener(this::registerReloadListeners);
        eventBus.addListener(this::addPackFinders);
        eventBus.addListener(this::registerRenderPipelines);
        eventBus.addListener(this::registerParticleGroups);
    }

    @SubscribeEvent
    public void registerRenderPipelines(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent event) {
        PhotonPipelines.register(event);
    }

    /** Fired per ParticleEngine construction — world and editor scene engines all get the group. */
    @SubscribeEvent
    public void registerParticleGroups(net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent event) {
        event.register(PhotonParticleRenderTypes.FX, PhotonParticleGroup::new);
    }

    /** Mount every .fxpack as a hidden, always-on, lowest-priority resource pack; see {@link FXPacks}. */
    @SubscribeEvent
    public void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            event.addRepositorySource(FXPacks.repositorySource());
        }
    }

    @SubscribeEvent
    public void registerReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Photon.id("mesh_cache"), PhotonMeshCache.INSTANCE);
        // let custom-shader materials re-read their JSON layout + retry failed compiles after a reload
        event.addListener(Photon.id("shader_reload"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                        resourceManager -> com.lowdragmc.photon.client.render.PhotonRenderTypes.onResourceReload());
    }

    @SubscribeEvent
    public void clientSetup(final FMLClientSetupEvent e) {
        // Touch the registry to trigger annotation scanning; classes annotated with @NodeAttribute
        // bound to ShaderGraph self-register (mirrors KilaGraph's own registry bootstrap).
        Photon.LOGGER.info("Photon shader graph nodes loaded: {}",
                com.lowdragmc.photon.client.shadergraph.ShaderGraph.NODE_REGISTRY.getNodeClasses().size());
    }

    @SubscribeEvent
    public void registerModels(ModelEvent.RegisterStandalone event) {
        // TODO(M2): standalone model registration for mesh particles. Blocked on the LDLib2 26.1
        // renderer/model path (its own RegisterStandalone body is still `// TODO RENDERER`); the old
        // ModelResourceLocation.standalone API is gone in favor of StandaloneModelKey/UnbakedStandaloneModel.
        // See MeshResource.onAdditionalModel for the editor-side counterpart, disabled the same way.
    }
}
