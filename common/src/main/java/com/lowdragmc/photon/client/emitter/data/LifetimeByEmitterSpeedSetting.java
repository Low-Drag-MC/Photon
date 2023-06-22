package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Range;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote LifetimeByEmitterSpeed
 */
@Environment(EnvType.CLIENT)
public class LifetimeByEmitterSpeedSetting extends ToggleGroup {
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.lifetimeByEmitterSpeed.multiplier")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "multiplier", yAxis = "emitter velocity"))
    protected NumberFunction multiplier = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.lifetimeByEmitterSpeed.speedRange")
    @NumberRange(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);

    public int getLifetime(LParticle particle, LParticle emitter, int initialLifetime) {
        var value = emitter.getVelocity().mag() * 20;
        return (int) (multiplier.get((float) ((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue())), () -> particle.getMemRandom(this)).floatValue() * initialLifetime);
    }

}
