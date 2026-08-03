package com.lowdragmc.photon.client;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.compat.iris.IrisOverlay;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import com.lowdragmc.photon.client.postfx.runtime.PostFXPreview;
import com.lowdragmc.photon.client.render.IPhotonFXCollector;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;
import com.lowdragmc.photon.client.render.OpaqueDepthCapture;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonStage;
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
import net.neoforged.neoforge.client.event.RenderGuiEvent;
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

    /** Frame start — before the GUI builds its draw list, so previews rendered here are ready for it.
     *  Note this is NOT a guaranteed pass-free slot: anything that clears/uploads must still do it
     *  before opening its own pass (see {@code MaterialPreviewRenderer.renderInto}). */
    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        MaterialPreviewRenderer.processPending();
        PostFXPreview.processPending();
    }

    /** Fires once per render frame (in-world and in the editor screen alike) — the post-effect
     *  system's frame boundary: recycle outputs, drop stale requests, advance the pool clock. */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        PhotonPostFX.onFrameEnd();
        // rotate the fx-slot ring buffers + free anything no drain consumed this frame
        PhotonWorldRenderState.endFrame();
    }

    /**
     * The world view's collector. {@code LevelRenderer} takes its storage straight off the
     * {@code FeatureRenderDispatcher} the {@code GameRenderer} built, so this public getter IS the
     * instance particle submission goes into — no accessor needed. {@code SubmitNodeStorageMixin}
     * makes it an {@link IPhotonFXCollector}.
     */
    private static IPhotonFXCollector worldCollector() {
        return (IPhotonFXCollector) Minecraft.getInstance().gameRenderer.getSubmitNodeStorage();
    }

    /** Photon's opaque draw slot: right after vanilla's solid feature pass, so these draws write
     *  depth before translucent world geometry is drawn against it (RendererSetting.Layer.Opaque). */
    @SubscribeEvent
    public static void onRenderLevelStageAfterOpaque(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        worldCollector().drain(PhotonStage.AFTER_OPAQUE_FEATURES);
        // Snapshot the opaque-only depth FXCompositeMode.LATE tests against, INCLUDING the opaque FX
        // just drawn (they are as solid as the terrain). 26.1 has no AfterBlockEntities event and this
        // is the last seam before the translucent chunk layer, so it is both the correct content and
        // the correct moment — taken here rather than in its own subscriber because the order relative
        // to the drain above matters and same-event subscriber order is not guaranteed.
        OpaqueDepthCapture.capture();
    }

    /** Photon's translucent draw slot (the 1.21 semantics: after vanilla translucent particles,
     *  inside the main frame pass with the output targets already routed) + standalone post-effect
     *  consumption for frames without Photon particles. */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentParticles event) {
        worldCollector().drain(PhotonStage.AFTER_TRANSLUCENT_PARTICLES);
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

    /**
     * Past the whole level render — the clouds, the weather and (in Fabulous) the transparency chain are
     * already on the frame, and under a shader pack Iris has run its composite and final passes from
     * inside {@code LevelRenderer.renderLevel}. 26.1 fires this event from {@code GameRenderer} AFTER
     * that call returns, which is exactly the seam 1.21 had to hand-write a mixin for.
     *
     * <p>Draw the deferred FX here, then let {@code PhotonPostFX} merge the layer and run any effect
     * chain that was held back with it — composite first, effects second, so effects see the FX.
     */
    @SubscribeEvent
    public static void onRenderLevelStageAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        // Composite only — NOTHING is drawn here. The deferred layer's geometry was rendered back at
        // the translucent seam, inside the level pass where the camera state is valid; all that waits
        // for this point is the fullscreen blend (past the clouds and the weather) and the effect chain
        // that had to wait for it.
        PhotonPostFX.onLevelRenderComplete();
    }

    /** Opt-in shader-pack layout readout (/photon_iris overlay). */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        IrisOverlay.render(event.getGuiGraphics());
    }
}
