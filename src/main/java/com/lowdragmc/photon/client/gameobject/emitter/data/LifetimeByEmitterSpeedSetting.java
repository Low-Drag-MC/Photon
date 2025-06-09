package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.utils.Range;
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

    @Configurable(tips = "photon.emitter.config.lifetimeByEmitterSpeed.multiplier")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "multiplier", yAxis = "emitter velocity"))
    protected NumberFunction multiplier = NumberFunction.constant(1);

    @Configurable(tips = "photon.emitter.config.lifetimeByEmitterSpeed.speedRange")
    @ConfigNumber(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);

    public int getLifetime(IParticle particle, IParticleEmitter emitter, int initialLifetime) {
        var value = emitter.getVelocity().length() * 20;
        return (int) (multiplier.get((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue()), () -> particle.getMemRandom(this)).floatValue() * initialLifetime);
    }

}
