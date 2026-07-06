package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote LifetimeByEmitterSpeed
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class LifetimeByEmitterSpeedSetting extends ToggleGroup {

    @Configurable(name = "LifetimeByEmitterSpeedSetting.multiplier", tips = "photon.emitter.config.lifetimeByEmitterSpeed.multiplier")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "multiplier", yAxis = "emitter velocity"))
    protected NumberFunction multiplier = NumberFunction.constant(1);

    @Configurable(name = "ColorBySpeedSetting.speedRange", tips = "photon.emitter.config.lifetimeByEmitterSpeed.speedRange")
    @ConfigNumber(range = {0, 1000}, type = ConfigNumber.Type.FLOAT)
    protected Range speedRange = Range.of(0f, 1f);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final LifetimeByEmitterSpeedSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> multiplier;
        public final RuntimeValue<Range> speedRange; // slot only (Range → no timeline binding)

        public Runtime(LifetimeByEmitterSpeedSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.multiplier = new RuntimeValue<>(config::getMultiplier);
            this.speedRange = new RuntimeValue<>(config::getSpeedRange);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public int getLifetime(IParticle particle, IParticleEmitter emitter, int initialLifetime) {
            var value = emitter.getVelocity().length() * 20;
            var range = speedRange.get();
            var min = range.getMin().floatValue();
            var max = range.getMax().floatValue();
            // zero-width range would divide by zero; clamp so curves sample inside [0, 1]
            var t = max > min ? Math.clamp((value - min) / (max - min), 0f, 1f) : (value >= min ? 1f : 0f);
            return (int) (multiplier.get().get(t, () -> particle.getMemRandom(this)).floatValue() * initialLifetime);
        }

        public void clear() {
            enable.clear();
            multiplier.clear();
            speedRange.clear();
        }
    }

}
