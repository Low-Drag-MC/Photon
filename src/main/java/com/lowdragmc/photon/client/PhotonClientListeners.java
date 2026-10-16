package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.compat.iris.IrisOverlay;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.OpaqueDepthCapture;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.VoxelWorld;
import com.lowdragmc.photon.client.light.dev.LightCommands;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import com.lowdragmc.photon.client.postfx.runtime.PostFXCamera;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.List;

@EventBusSubscriber(modid = Photon.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class PhotonClientListeners {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        List<LiteralArgumentBuilder<CommandSourceStack>> commands = ClientCommands.createClientCommands();
        commands.forEach(dispatcher::register);
        if (Platform.isDevEnv()) {
            dispatcher.register(LightCommands.create());
        }
    }

    /** Fires once per render frame (in-world and in the editor screen alike) — the post-effect
     *  system's frame boundary: recycle outputs, drop stale requests, advance the pool clock. */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        PhotonPostFX.onFrameEnd();
    }

    /** A chunk (re)arriving may differ from what was voxelised. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            DynamicLightManager.chunkLoaded(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) return;
        VoxelWorld.clearMasks();
        var level = Minecraft.getInstance().level;
        if (level != null) DynamicLightManager.blocksReplaced(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            DynamicLightRenderer.release(level);
            PhotonLights.onLevelUnload(level);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        PhotonLights.tick();
        DynamicLightRenderer.tick();
    }

    /**
     * Two seams in the level render ({@link DynamicLightRenderer#onStage} picks its own):
     *
     * <ul>
     *   <li><b>AFTER_BLOCK_ENTITIES</b> — the last stage before {@code RenderType.translucent()} goes
     *       down. Snapshot the opaque-only depth {@code FXCompositeMode.LATE} depth-tests against, so
     *       a water surface cannot slice an effect in half.</li>
     *   <li><b>AFTER_PARTICLES</b> — standalone post-effect consumption for frames without Photon
     *       particles (the particle pipeline seam never runs then).</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // The frame's camera, for post-processing passes that reconstruct world space. Captured on every
        // stage (two matrix copies) because the consumers run at different points in the level render, and
        // LevelRenderer pops the camera off the model-view stack before the last of them — see PostFXCamera.
        // Unconditional on purpose: under a shader pack the chain runs from onLevelRenderComplete, which is
        // outside every stage, and PhotonPostFX's own stage hook early-returns there.
        PostFXCamera.capture(event.getModelViewMatrix(), event.getProjectionMatrix(),
                event.getCamera().getPosition());
        DynamicLightRenderer.onStage(event);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            OpaqueDepthCapture.capture();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            PhotonPostFX.onLevelStageAfterParticles();
        }
    }

    /** Opt-in shader-pack layout readout (/photon_iris overlay). */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        IrisOverlay.render(event.getGuiGraphics());
    }
}
