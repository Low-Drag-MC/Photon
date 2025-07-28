package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
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

    public int getLifetime(IParticle particle, IParticleEmitter emitter, int initialLifetime) {
        var value = emitter.getVelocity().length() * 20;
        var min = speedRange.getMin().floatValue();
        var max = speedRange.getMax().floatValue();
        return (int) (multiplier.get((value - min) / (max - min), () -> particle.getMemRandom(this)).floatValue() * initialLifetime);
    }

}
