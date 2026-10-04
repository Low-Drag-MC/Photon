package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;

/**
 * Pure-data rotation-over-lifetime config; value-use behaviour lives on the co-located {@link Runtime}.
 *
 * @author KilaBash
 * @date 2023/5/30
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class RotationOverLifetimeSetting extends ToggleGroup {

    @Configurable(name = "RotationOverLifetimeSetting.space", tips = "photon.emitter.config.rotationOverLifetime.space")
    @ConfigSelector(subConfiguratorBuilder = "buildSpaceConfigurator")
    protected ValueSpace space = ValueSpace.Local;
    @Persisted
    public final TransformRef customSpace = new TransformRef();

    @Configurable(name = "RotationBySpeedSetting.roll", tips = "photon.emitter.config.rotation.roll")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "roll"))
    protected NumberFunction roll = NumberFunction.constant(0);

    @Configurable(name = "RotationBySpeedSetting.pitch", tips = "photon.emitter.config.rotation.pitch")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "pitch"))
    protected NumberFunction pitch = NumberFunction.constant(0);

    @Configurable(name = "RotationBySpeedSetting.yaw", tips = "photon.emitter.config.rotation.yaw")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "yaw"))
    protected NumberFunction yaw = NumberFunction.constant(0);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    private void buildSpaceConfigurator(ValueSpace space, ConfiguratorGroup group) {
        if (space == ValueSpace.Custom) {
            group.addConfigurator(CustomSpace.configurator("ParticleConfig.customSpace", customSpace,
                    "photon.emitter.config.valueCustomSpace.tips"));
        }
    }

    public static class Runtime {
        private final RotationOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<ValueSpace> space; // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction> roll;
        public final RuntimeValue<NumberFunction> pitch;
        public final RuntimeValue<NumberFunction> yaw;

        public Runtime(RotationOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.space = new RuntimeValue<>(() -> config.space);
            this.roll = new RuntimeValue<>(() -> config.roll);
            this.pitch = new RuntimeValue<>(() -> config.pitch);
            this.yaw = new RuntimeValue<>(() -> config.yaw);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public boolean isLocal() {
            var value = space.get();
            return value == null || value == ValueSpace.Local;
        }

        @Nullable
        public Quaternionf getFrameRotation(IScene scene) {
            if (space.get() != ValueSpace.Custom) return null;
            var transform = config.customSpace.getTransform(scene);
            return transform == null ? null : transform.rotation();
        }

        public Vector3f getRotation(IParticle particle, float partialTicks) {
            var t = particle.getT(partialTicks);
            return new Vector3f(
                    yaw.get().get(t, () -> particle.getMemRandom("rol2")).floatValue(),
                    pitch.get().get(t, () -> particle.getMemRandom("rol1")).floatValue(),
                    roll.get().get(t, () -> particle.getMemRandom("rol0")).floatValue()).mul(Mth.TWO_PI / 360);
        }

        public void clear() {
            enable.clear();
            space.clear();
            roll.clear();
            pitch.clear();
            yaw.clear();
        }
    }
}
