package com.lowdragmc.photon.forge.core.mixins.no_iris;

import com.google.common.collect.ImmutableList;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.emitter.data.RendererSetting;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.*;

/**
 * @author KilaBash
 * @date 2022/05/02
 * @implNote ParticleEngineMixin, inject particle postprocessing
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    private static final List<ParticleRenderType> OPAQUE_PARTICLE_RENDER_TYPES = ImmutableList.of(ParticleRenderType.TERRAIN_SHEET, ParticleRenderType.PARTICLE_SHEET_OPAQUE, ParticleRenderType.PARTICLE_SHEET_LIT, ParticleRenderType.CUSTOM, ParticleRenderType.NO_RENDER);
    @Shadow @Final private Map<ParticleRenderType, Queue<Particle>> particles;

    @Redirect(
            method = {"render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;particles:Ljava/util/Map;"
            )
    )
    private Map<ParticleRenderType, Queue<Particle>> iris$selectParticlesToRender(ParticleEngine instance) {
        Map<ParticleRenderType, Queue<Particle>> toRender = new HashMap<>(this.particles);
        if (PhotonParticleRenderType.getLAYER() == RendererSetting.Layer.Opaque) {
            toRender.remove(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT);
        } else {
            for (var type : OPAQUE_PARTICLE_RENDER_TYPES) {
                toRender.remove(type);
            }
        }
        return toRender;
    }
}
