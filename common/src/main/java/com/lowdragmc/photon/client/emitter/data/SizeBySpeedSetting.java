package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Range;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.emitter.data.number.*;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote SizeOverLifetimeSetting
 */
@Environment(EnvType.CLIENT)
public class SizeBySpeedSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "Controls the scale of size during its lifetime.")
    @NumberFunctionConfig(types = {RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "speed", yAxis = "size scale"))
    protected NumberFunction scale = new RandomConstant(0f, 1f, true);

    @Setter
    @Getter
    @Configurable(tips = "Controls the size of separated axis during its lifetime.")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "speed", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(0, 0, 0);

    @Setter
    @Getter
    @Configurable(tips = "Remaps speed in the defined range to a size.")
    @NumberRange(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);
    public Vector3 getSize(Vector3 startedSize, LParticle particle) {
        var value = particle.getVelocity().mag() * 20;
        var t = (float) ((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue()));
        return size.get(t, () -> particle.getMemRandom("sbs0")).add(startedSize)
                .multiply(scale.get(t, () -> particle.getMemRandom("sbs1")).doubleValue());
    }

}
