package com.lowdragmc.photon.fabric.core.mixins;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.fx.BlockEffect;
import com.lowdragmc.photon.client.fx.EntityEffect;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static com.lowdragmc.photon.client.emitter.PhotonParticleRenderType.makeParticleRenderTypeComparator;

/**
 * @author KilaBash
 * @date 2022/05/02
 * @implNote ParticleEngineMixin, inject particle postprocessing
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    private static final List<ParticleRenderType> DEFAULT_RENDER_ORDER = List.of(ParticleRenderType.TERRAIN_SHEET, ParticleRenderType.PARTICLE_SHEET_OPAQUE, ParticleRenderType.PARTICLE_SHEET_LIT, ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT, ParticleRenderType.CUSTOM);
    @Mutable
    @Shadow @Final private static List<ParticleRenderType> RENDER_ORDER;

    @Shadow @Final private Map<ParticleRenderType, Queue<Particle>> particles;

    @Shadow @Final private TextureManager textureManager;

    /**
     * reset render order to support our custom particle types.
     */
    @Inject(method = "render",
            at = @At(value = "HEAD"))
    private void injectRenderHead(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float partialTicks, CallbackInfo ci) {
        RENDER_ORDER = new ArrayList<>(this.particles.keySet());
        RENDER_ORDER.sort(makeParticleRenderTypeComparator(DEFAULT_RENDER_ORDER));
    }

    /**
     * notify finish render switch to next render layer
     */
    @Inject(method = "render",
            at = @At(value = "RETURN"))
    private void injectRenderReturn(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float partialTicks, CallbackInfo ci) {
        PhotonParticleRenderType.finishRender();
    }

    /**
     * fine, if you install shader mod, we have to render our custom particles ourselves.
     */
    @Inject(method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                    shift = At.Shift.BEFORE,
                    by = 1
            ))
    private void injectAfterRender(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float partialTicks, CallbackInfo ci) {
        if (PhotonParticleRenderType.getLAYER() == RendererSetting.Layer.Opaque && Photon.isShaderModInstalled()) {
            for (var type : particles.keySet()) {
                if (type instanceof PhotonParticleRenderType) {
                    var iterable = this.particles.get(type);
                    if (iterable == null) continue;
                    RenderSystem.setShader(GameRenderer::getParticleShader);
                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                    Tesselator tesselator = Tesselator.getInstance();
                    BufferBuilder bufferBuilder = tesselator.getBuilder();
                    type.begin(bufferBuilder, textureManager);
                    for (Particle particle : iterable) {
                        try {
                            particle.render(bufferBuilder, camera, partialTicks);
                        }
                        catch (Throwable throwable) {
                            CrashReport crashReport = CrashReport.forThrowable(throwable, "Rendering Particle");
                            CrashReportCategory crashReportCategory = crashReport.addCategory("Particle being rendered");
                            crashReportCategory.setDetail("Particle", particle::toString);
                            crashReportCategory.setDetail("Particle Type", type::toString);
                            throw new ReportedException(crashReport);
                        }
                    }
                    type.end(tesselator);
                }
            }
        }
    }

    /**
     * clear effect cache while level changes.
     */
    @Inject(method = "setLevel",
            at = @At(value = "RETURN"))
    private void injectSetLevel(ClientLevel level, CallbackInfo ci) {
        EntityEffect.CACHE.clear();
        BlockEffect.CACHE.clear();
    }
}
