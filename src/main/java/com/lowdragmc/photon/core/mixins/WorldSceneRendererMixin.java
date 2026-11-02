package com.lowdragmc.photon.core.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.lowdragmc.lowdraglib2.client.scene.SceneOitTargets;
import com.lowdragmc.lowdraglib2.client.scene.SceneRenderContext;
import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.photon.client.render.PhotonFrameSeams;
import com.lowdragmc.photon.client.render.PhotonOit;
import com.lowdragmc.photon.client.render.PhotonOitComposite;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame seams of an LDLib2 scene ({@link PhotonFrameSeams}): its one pass is split before the translucent features
 * and before the see-through ones, so the editor's gizmos and overlays still draw over the FX. The rest of
 * {@code drawWorld} continues in the replacement pass ({@code pass}, {@code dispatchCtx}). A scene drawn with improved
 * transparency has no pass open before its OIT phases, and its composite is Photon's when Photon's draws bloom.
 */
@Mixin(WorldSceneRenderer.class)
public abstract class WorldSceneRendererMixin {

    @WrapMethod(method = "drawWorld")
    private void photon$sceneView(RenderBuffers buffers, GpuTextureView color, @Nullable GpuTextureView depth,
                                  Operation<Void> original) {
        try (var ignored = PhotonFrameSeams.beginScene(color, depth)) {
            original.call(buffers, color, depth);
        }
    }

    @Inject(method = "drawWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private void photon$beginScenePrepare(RenderBuffers buffers, GpuTextureView color, @Nullable GpuTextureView depth,
                                          CallbackInfo ci) {
        var scene = PhotonFrameSeams.currentScene();
        if (scene != null) PhotonFrameSeams.beginPrepare(scene, ((WorldSceneRenderer) (Object) this).isDrawingWithOit());
    }

    @Inject(method = "drawTranslucentOit", at = @At("HEAD"))
    private void photon$beforeOit(CallbackInfo ci) {
        var scene = PhotonFrameSeams.currentScene();
        if (scene != null) {
            PhotonOit.beginLevel();
            PhotonFrameSeams.afterOpaque(scene);
        }
    }

    @WrapMethod(method = "compositeOit")
    private void photon$compositeOit(SceneOitTargets targets, GpuTextureView color, GpuTextureView depth,
                                     Operation<Void> original) {
        if (PhotonFrameSeams.currentScene() == null || !PhotonOitComposite.composite(targets.accumulate(),
                targets.transmittance(), targets.depthBounds(), color, depth)) {
            original.call(targets, color, depth);
        }
    }

    @Inject(method = "drawWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            shift = At.Shift.AFTER))
    private void photon$endScenePrepare(RenderBuffers buffers, GpuTextureView color, @Nullable GpuTextureView depth,
                                        CallbackInfo ci) {
        var scene = PhotonFrameSeams.currentScene();
        if (scene != null) PhotonFrameSeams.endPrepare(scene);
    }

    @WrapOperation(method = "drawWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void photon$afterOpaque(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
                                    Operation<Void> original,
                                    @Local(argsOnly = true, ordinal = 0) GpuTextureView color,
                                    @Local(argsOnly = true, ordinal = 1) GpuTextureView depth,
                                    @Local(name = "pass") LocalRef<RenderPass> passRef,
                                    @Local(name = "dispatchCtx") LocalRef<SceneRenderContext> contextRef) {
        var scene = PhotonFrameSeams.currentScene();
        var next = scene == null ? pass
                : PhotonFrameSeams.splitAfterOpaque(scene, pass, color, depth, "LDLib2 scene (after Photon's opaque FX)");
        photon$continueIn(next, pass, passRef, contextRef);
        original.call(frame, next);
    }

    @WrapOperation(method = "drawWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSeeThrough(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void photon$afterTranslucent(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
                                         Operation<Void> original,
                                         @Local(argsOnly = true, ordinal = 0) GpuTextureView color,
                                         @Local(argsOnly = true, ordinal = 1) GpuTextureView depth,
                                         @Local(name = "pass") LocalRef<RenderPass> passRef,
                                         @Local(name = "dispatchCtx") LocalRef<SceneRenderContext> contextRef) {
        var scene = PhotonFrameSeams.currentScene();
        var next = scene == null ? pass
                : PhotonFrameSeams.splitAfterTranslucent(scene, pass, color, depth, "LDLib2 scene (after Photon's FX)");
        photon$continueIn(next, pass, passRef, contextRef);
        original.call(frame, next);
    }

    /** The rest of the method draws into {@code next}: its pass local (which it closes) and its hooks' context. */
    @Unique
    private static void photon$continueIn(RenderPass next, RenderPass previous, LocalRef<RenderPass> passRef,
                                          LocalRef<SceneRenderContext> contextRef) {
        if (next == previous) return;
        passRef.set(next);
        var context = contextRef.get();
        if (context != null) {
            contextRef.set(new SceneRenderContext(context.renderer(), context.poseStack(), context.submitStorage(),
                    context.cameraState(), context.partialTicks(), next));
        }
    }
}
