package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.accessors.Vector3fAccessor;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import lombok.EqualsAndHashCode;
import org.joml.Vector3f;

import java.lang.reflect.Field;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FacingDirectionSetting implements IConfigurable, IPersistedSerializable {

    public final ParticleRendererSetting particleRendererSetting;

    public FacingDirectionSetting(ParticleRendererSetting particleRendererSetting) {
        this.particleRendererSetting = particleRendererSetting;
    }

    public enum Mode {
        DERIVE_FROM_VELOCITY,
        CUSTOM_DIRECTION
    }

    @Configurable(name = "FacingDirectionSetting.mode", tips = "photon.emitter.config.renderer.facingDirection.mode")
    @ConfigSelector(subConfiguratorBuilder = "buildModeConfigurator")
    @Persisted
    @EqualsAndHashCode.Include
    private Mode mode = Mode.DERIVE_FROM_VELOCITY;
    @Persisted
    @EqualsAndHashCode.Include
    private float minSpeedThreshold = 0.01f;
    @Persisted
    @EqualsAndHashCode.Include
    private Vector3f customDirection = new Vector3f(0, 1, 0);

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        if (mode == this.mode) return;
        this.mode = mode;
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    public float getMinSpeedThreshold() {
        return minSpeedThreshold;
    }

    public void setMinSpeedThreshold(float minSpeedThreshold) {
        if (minSpeedThreshold == this.minSpeedThreshold) return;
        this.minSpeedThreshold = minSpeedThreshold;
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    public Vector3f getCustomDirection() {
        return customDirection;
    }

    public void setCustomDirection(Vector3f customDirection) {
        if (customDirection.equals(this.customDirection)) return;
        this.customDirection = customDirection;
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    @Override
    public void afterDeserialize() {
        IPersistedSerializable.super.afterDeserialize();
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup parent) {
        IConfigurable.super.buildConfigurator(parent);
    }

    private void buildModeConfigurator(Mode selectedMode, ConfiguratorGroup group) {
        if (selectedMode == Mode.DERIVE_FROM_VELOCITY) {
            group.addConfigurators(
                    new NumberConfigurator(
                            "FacingDirectionSetting.minSpeedThreshold",
                            () -> (Number) minSpeedThreshold,
                            v -> setMinSpeedThreshold(v.floatValue()),
                            0.01f,
                            true
                    ).setTips("photon.emitter.config.renderer.facingDirection.minSpeedThreshold")
            );
        } else {
            try {
                Field field = FacingDirectionSetting.class.getDeclaredField("customDirection");
                group.addConfigurators(
                        new Vector3fAccessor().create(
                                "FacingDirectionSetting.customDirection",
                                this::getCustomDirection,
                                this::setCustomDirection,
                                true,
                                field,
                                this
                        ).setTips("photon.emitter.config.renderer.facingDirection.customDirection")
                );
            } catch (NoSuchFieldException ignored) {
            }
        }
    }
}
