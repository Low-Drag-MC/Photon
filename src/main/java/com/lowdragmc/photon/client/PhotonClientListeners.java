package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.fx.FXPreloader;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.VoxelWorld;
import com.lowdragmc.photon.client.light.dev.LightCommands;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisOverlay;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import com.lowdragmc.photon.client.postfx.runtime.PostFXPreview;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;
import com.lowdragmc.photon.client.render.OpaqueDepthCapture;
import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonFeatureRenderer;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Vector3f;

import java.util.List;

@EventBusSubscriber(modid = Photon.MOD_ID, value = Dist.CLIENT)
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

    /** A chunk (re)arriving may differ from what was voxelised or snapshotted for collision. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            var pos = event.getChunk().getPos();
            DynamicLightManager.chunkLoaded(level, pos.x(), pos.z());
            CollisionWorld.chunkChanged(level, pos.x(), pos.z());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            var pos = event.getChunk().getPos();
            CollisionWorld.chunkChanged(level, pos.x(), pos.z());
        }
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) return;
        VoxelWorld.clearMasks();
        BlockFilter.tagsChanged();
        var level = Minecraft.getInstance().level;
        if (level != null) DynamicLightManager.blocksReplaced(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            DynamicLightRenderer.release(level);
            PhotonLights.onLevelUnload(level);
            CollisionWorld.levelUnloaded(level);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        PhotonLights.tick();
        DynamicLightRenderer.tick();
    }

    @SubscribeEvent
    public static void onRegisterFeatureRenderers(RegisterFeatureRenderersEvent event) {
        PhotonFeatureRenderer.register(event);
    }

    /** Frame start — AFTER the GUI built its draw list (26.2 extracts first) but before it draws, so a preview
     *  rendered here shows this frame, while one dropped here must stay open until the next (the draw list
     *  holds its view). Note this is NOT a guaranteed pass-free slot: anything that clears/uploads must still
     *  do it before opening its own pass (see {@code MaterialPreviewRenderer.renderInto}). */
    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        FXPreloader.onFrameStart();
        MaterialPreviewRenderer.processPending();
        PostFXPreview.processPending();
    }

    /** Fires once per render frame (in-world and in the editor screen alike) — the post-effect
     *  system's frame boundary: recycle outputs, drop stale requests, advance the pool clock. */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        PhotonPostFX.onFrameEnd();
        PhotonWorldRenderState.endFrame();
    }

    /**
     * After the solid features, Photon's opaque stage among them: the opaque scene is down and nothing translucent
     * is — what the dynamic lights light. Then the opaque-only depth {@code FXCompositeMode.LATE} tests against.
     */
    @SubscribeEvent
    public static void onRenderLevelStageAfterOpaque(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        IrisCompat.runWithoutPackPrograms(DynamicLightRenderer::afterOpaque);
        OpaqueDepthCapture.capture();
    }

    /** Standalone post-effect consumption for frames without Photon particles. */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentParticles event) {
        IrisCompat.runWithoutPackPrograms(() -> {
            PhotonPostFX.onLevelStageAfterParticles();
            DynamicLightRenderer.afterParticles();
        });
    }

    /**
     * Per-world-frame engine-uniform upload (U_* block for custom shaders) and the frame's dynamic lights — culled,
     * ranked, uploaded, their visibility maps drawn — after the features prepared and before any pass of the frame.
     * The projection is the one the level is drawn with, view bob included. Editor scenes upload their own in
     * {@code PhotonParticleManager.render}.
     */
    @SubscribeEvent
    public static void onFrameGraphSetup(FrameGraphSetupEvent event) {
        var camera = event.getCameraState();
        var projection = PhotonCameraUtils.levelProjection();
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        PhotonEngineUniforms.update(
                projection, camera.viewRotationMatrix,
                new Vector3f((float) camera.pos.x, (float) camera.pos.y, (float) camera.pos.z),
                target.width, target.height);
        IrisCompat.runWithoutPackPrograms(() -> DynamicLightRenderer.prepareWorld(camera.pos,
                event.getModelViewMatrix(), projection, event.getFrustum(),
                event.getDeltaTracker().getGameTimeDeltaPartialTick(false), camera.fogData));
    }

    /**
     * Past the whole level render — the clouds, the weather and (in Fabulous) the transparency chain are
     * already on the frame, and under a shader pack Iris has run its composite and final passes from
     * inside {@code LevelRenderer.renderLevel}.
     *
     * <p>Draw the deferred FX here, then let {@code PhotonPostFX} merge the layer and run any effect
     * chain that was held back with it — composite first, effects second, so effects see the FX.
     */
    @SubscribeEvent
    public static void onRenderLevelStageAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        // No FX geometry is drawn here: the deferred layer's was rendered at the translucent seam, inside the level
        // pass where the camera state is valid, and only its fullscreen blend and the held-back effects wait for this.
        // Under a shader pack the dynamic lights go on here, over the pack's image — before the late FX composite,
        // or those particles would be lit as the wall behind them — and the haze goes on last, over everything.
        IrisCompat.runWithoutPackPrograms(() -> {
            DynamicLightRenderer.onLevelRendered();
            PhotonPostFX.onLevelRenderComplete();
            DynamicLightRenderer.onFrameComposited();
        });
    }

    /** Opt-in shader-pack layout readout (/photon_iris overlay). */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        IrisOverlay.render(event.getGuiGraphics());
    }
}
