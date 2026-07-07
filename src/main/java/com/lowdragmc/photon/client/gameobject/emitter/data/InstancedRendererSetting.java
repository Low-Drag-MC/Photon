package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * {@link RendererSetting} plus the GPU-instancing toggle, for particle types with an instanced
 * render path (trail, beam). Tile particles keep the toggle on {@code ParticleRendererSetting}.
 */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InstancedRendererSetting extends RendererSetting {

    @Configurable(name = "ParticleRendererSetting.useGPUInstance")
    @EqualsAndHashCode.Include
    private boolean useGPUInstance = false;

}
