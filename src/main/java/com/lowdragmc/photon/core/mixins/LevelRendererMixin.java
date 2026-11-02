package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.PhotonFrameSeams;
import com.lowdragmc.photon.client.render.PhotonOit;
import com.lowdragmc.photon.client.render.PhotonOitComposite;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The world's frame seams ({@link PhotonFrameSeams}). With improved transparency both points are free: the head of
 * {@code executeOit} and its return, past the composite, which is Photon's when its OIT draws bloom
 * ({@link PhotonOitComposite}). With classic transparency the solid and translucent draws share one pass: it is split
 * before the translucent draws, and closed after them for the after-translucent point. Iris and KilaGraph split the
 * same pass; closing a closed pass is a no-op, so the splits chain in any order.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private LevelTargetBundle targets;

    /** The pass Photon opened in place of the classic one it split, closed with the method. */
    @Unique
    private @Nullable RenderPass photon$translucentPass;

    /** Photon resolved this frame's OIT sum itself; vanilla's composite draw is skipped. */
    @Unique
    private boolean photon$oitComposited;

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private void photon$beginWorldPrepare(CallbackInfo ci) {
        PhotonFrameSeams.beginPrepare(PhotonFrameSeams.world());
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            shift = At.Shift.AFTER))
    private void photon$endWorldPrepare(CallbackInfo ci) {
        PhotonFrameSeams.endPrepare(PhotonFrameSeams.world());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void photon$endWorldFrame(CallbackInfo ci) {
        PhotonFrameSeams.endView(PhotonFrameSeams.world());
    }

    @Inject(method = "executeOit", at = @At("HEAD"))
    private void photon$afterOpaqueOit(ChunkSectionsToRender chunkSectionsToRender,
                                       FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
        PhotonOit.beginLevel();
        PhotonFrameSeams.afterOpaque(PhotonFrameSeams.world());
    }

    /** Every OIT stage is down and the composite is next: no pass is open. */
    @Inject(method = "executeOit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getSamplerCache()Lcom/mojang/blaze3d/systems/SamplerCache;",
            ordinal = 0))
    private void photon$compositeOit(ChunkSectionsToRender chunkSectionsToRender,
                                     FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
        photon$oitComposited = PhotonOitComposite.composite(targets,
                Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }

    /** The composite's draw — the only draw {@code executeOit} makes itself. */
    @WrapOperation(method = "executeOit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V"))
    private void photon$skipVanillaOitComposite(RenderPass pass, int vertexCount, int instanceCount, int firstVertex,
                                                int firstInstance, Operation<Void> original) {
        if (photon$oitComposited) {
            photon$oitComposited = false;
            return;
        }
        original.call(pass, vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Inject(method = "executeOit", at = @At("RETURN"))
    private void photon$afterTranslucentOit(ChunkSectionsToRender chunkSectionsToRender,
                                            FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
        PhotonFrameSeams.afterTranslucent(PhotonFrameSeams.world());
    }

    @ModifyVariable(method = "executeClassicTransparency", at = @At("HEAD"), argsOnly = true)
    private RenderPass photon$splitAfterOpaque(RenderPass renderPass) {
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var pass = PhotonFrameSeams.splitAfterOpaque(PhotonFrameSeams.world(), renderPass,
                main.getColorTextureView(), main.getDepthTextureView(), "Main (after Photon's opaque FX)");
        if (pass != renderPass) {
            photon$translucentPass = pass;
        }
        return pass;
    }

    /** Around the call, as Iris wraps it: every injector into the method has drawn by the time it returns. */
    @WrapOperation(method = "lambda$addMainPass$0", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void photon$afterTranslucentClassic(LevelRenderer self, ChunkSectionsToRender chunkSectionsToRender,
                                                FeatureRenderDispatcher.PreparedFrame featureFrame,
                                                RenderPass renderPass, Operation<Void> original) {
        original.call(self, chunkSectionsToRender, featureFrame, renderPass);
        var own = photon$translucentPass;
        photon$translucentPass = null;
        var world = PhotonFrameSeams.world();
        if (PhotonFrameSeams.wantsAfterTranslucent(world)) {
            // nothing draws into the main pass after the method
            renderPass.close();
            if (own != null) own.close();
            PhotonFrameSeams.afterTranslucent(world);
        } else if (own != null) {
            own.close();
        }
    }
}
