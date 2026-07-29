package com.lowdragmc.photon.client;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.List;

@EventBusSubscriber(modid = Photon.MOD_ID, value = Dist.CLIENT)
public class PhotonClientListeners {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        List<LiteralArgumentBuilder<CommandSourceStack>> commands = ClientCommands.createClientCommands();
        commands.forEach(dispatcher::register);
    }

    /** Frame start — the only editor-screen slot with NO render pass open, so the material previews
     *  (which create a pass and may upload textures) must render here, not from the GUI draw. */
    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        com.lowdragmc.photon.client.render.MaterialPreviewRenderer.processPending();
    }

    /** Fires once per render frame (in-world and in the editor screen alike) — the post-effect
     *  system's frame boundary: recycle outputs, drop stale requests, advance the pool clock. */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        PhotonPostFX.onFrameEnd();
        // rotate the fx-slot ring buffers + free anything no drain consumed this frame
        PhotonWorldRenderState.endFrame();
    }

    /** Photon's world draw slot (the 1.21 semantics: after vanilla translucent particles, inside
     *  the main frame pass with the output targets already routed) + standalone post-effect
     *  consumption for frames without Photon particles. */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentParticles event) {
        PhotonWorldRenderState.drainWorld();
        PhotonPostFX.onLevelStageAfterParticles();
    }

    /** Per-world-frame engine-uniform upload (U_* block for custom shaders): the event's camera
     *  state carries the CPU-side projection/view matrices. Editor scenes re-upload their own
     *  values in {@code PhotonParticleManager.render}. */
    @SubscribeEvent
    public static void onFrameGraphSetup(FrameGraphSetupEvent event) {
        var camera = event.getCameraState();
        var target = Minecraft.getInstance().getMainRenderTarget();
        PhotonEngineUniforms.update(
                camera.projectionMatrix, camera.viewRotationMatrix,
                new Vector3f((float) camera.pos.x, (float) camera.pos.y, (float) camera.pos.z),
                target.width, target.height);
    }
}
