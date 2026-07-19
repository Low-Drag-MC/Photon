package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RotationBySpeedSetting
 */
@Setter
@Getter
public class RotationBySpeedSetting extends ToggleGroup {

    @Configurable(name = "RotationBySpeedSetting.roll", tips = "photon.emitter.config.rotation.roll")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "speed", yAxis = "roll"))
    protected NumberFunction roll = NumberFunction.constant(0);

    @Configurable(name = "RotationBySpeedSetting.pitch", tips = "photon.emitter.config.rotation.pitch")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "speed", yAxis = "pitch"))
    protected NumberFunction pitch = NumberFunction.constant(0);

    @Configurable(name = "RotationBySpeedSetting.yaw", tips = "photon.emitter.config.rotation.yaw")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "speed", yAxis = "yaw"))
    protected NumberFunction yaw = NumberFunction.constant(0);

    @Configurable(name = "ColorBySpeedSetting.speedRange", tips = "photon.emitter.config.rotationBySpeed.speedRange")
    @ConfigNumber(range = {0, 1000}, type = ConfigNumber.Type.FLOAT)
    protected Range speedRange = Range.of(0f, 1f);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final RotationBySpeedSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> roll;
        public final RuntimeValue<NumberFunction> pitch;
        public final RuntimeValue<NumberFunction> yaw;
        public final RuntimeValue<Range> speedRange; // slot only (Range → no timeline binding)

        public Runtime(RotationBySpeedSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.roll = new RuntimeValue<>(config::getRoll);
            this.pitch = new RuntimeValue<>(config::getPitch);
            this.yaw = new RuntimeValue<>(config::getYaw);
            this.speedRange = new RuntimeValue<>(config::getSpeedRange);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector3f getRotation(TileParticle particle) {
            var value = particle.getRealVelocity().length() * 20;
            var range = speedRange.get();
            var a = range.getA().floatValue();
            var b = range.getB().floatValue();
            // zero-width range would divide by zero; clamp so curves sample inside [0, 1]
            var t = b > a ? Math.clamp((value - a) / (b - a), 0f, 1f) : (value >= a ? 1f : 0f);
            // component order must match RotationOverLifetimeSetting: (yaw, pitch, roll) -> (x, y, z),
            // so "roll" lands on Z — the axis billboards actually spin around
            return new Vector3f(
                    yaw.get().get(t, () -> particle.getMemRandom("rbs2")).floatValue(),
                    pitch.get().get(t, () -> particle.getMemRandom("rbs1")).floatValue(),
                    roll.get().get(t, () -> particle.getMemRandom("rbs0")).floatValue()).mul(Mth.TWO_PI / 360);
        }

        public void clear() {
            enable.clear();
            roll.clear();
            pitch.clear();
            yaw.clear();
            speedRange.clear();
        }
    }

}
