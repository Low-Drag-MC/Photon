package com.lowdragmc.photon.fabric.core.mixins.no_iris;

import com.google.common.collect.ImmutableList;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.emitter.data.RendererSetting;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * @author KilaBash
 * @date 2022/05/02
 * @implNote ParticleEngineMixin, inject particle postprocessing
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    private static final List<ParticleRenderType> OPAQUE_PARTICLE_RENDER_TYPES = ImmutableList.of(ParticleRenderType.TERRAIN_SHEET, ParticleRenderType.PARTICLE_SHEET_OPAQUE, ParticleRenderType.PARTICLE_SHEET_LIT, ParticleRenderType.CUSTOM, ParticleRenderType.NO_RENDER);
    @Shadow @Final private static List<ParticleRenderType> RENDER_ORDER;
    @Redirect(
            method = {"render"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;RENDER_ORDER:Ljava/util/List;"
            )
    )
    private List<ParticleRenderType> injectParticlesToRender() {
        List<ParticleRenderType> toRender = new ArrayList<>(RENDER_ORDER);
        if (PhotonParticleRenderType.getLAYER() == RendererSetting.Layer.Opaque) {
            toRender.remove(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT);
            return toRender;
        } else {
            toRender.removeAll(OPAQUE_PARTICLE_RENDER_TYPES);
        }
        return toRender;
    }
}
