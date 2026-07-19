package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * {@link RendererSetting} plus the GPU-instancing toggle, for particle types with an instanced
 * render path (trail, beam). Tile particles keep the toggle on {@code ParticleRendererSetting}.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InstancedRendererSetting extends RendererSetting {

    @Configurable(name = "ParticleRendererSetting.useGPUInstance")
    @EqualsAndHashCode.Include
    private boolean useGPUInstance = false;

    /** Slot-based per-emitter render override (see {@link RendererSetting.Runtime}) + the {@code useGPUInstance} slot. */
    public static class Runtime extends RendererSetting.Runtime {
        public final RuntimeValue<Boolean> useGPUInstance;

        private Runtime(InstancedRendererSetting config) {
            super(config);
            this.useGPUInstance = new RuntimeValue<>(config::isUseGPUInstance);
        }

        public boolean isUseGPUInstance() {
            return useGPUInstance.get();
        }

        @Override
        public boolean hasOverride() {
            return super.hasOverride() || useGPUInstance.isOverridden();
        }

        @Override
        public void clear() {
            super.clear();
            useGPUInstance.clear();
        }

        @Override
        public boolean effectiveEquals(RendererSetting.Runtime o) {
            return super.effectiveEquals(o) && o instanceof Runtime other
                    && isUseGPUInstance() == other.isUseGPUInstance();
        }

        @Override
        public int effectiveHashCode() {
            return Objects.hash(super.effectiveHashCode(), isUseGPUInstance());
        }
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

}
