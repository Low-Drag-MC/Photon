package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Range;
import com.lowdragmc.lowdraglib.utils.Vector3;
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
import net.minecraft.util.Mth;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RotationBySpeedSetting
 */
@Environment(EnvType.CLIENT)
public class RotationBySpeedSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.rotation.roll")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "speed", yAxis = "roll"))
    protected NumberFunction roll = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.rotation.pitch")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "speed", yAxis = "pitch"))
    protected NumberFunction pitch = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.rotation.yaw")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "speed", yAxis = "yaw"))
    protected NumberFunction yaw = NumberFunction.constant(0);

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.rotationBySpeed.speedRange")
    @NumberRange(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);

    public Vector3 getRotation(LParticle particle) {
        var value = particle.getVelocity().mag() * 20;
        var t = (float) ((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue()));
        return new Vector3(
                roll.get(t, () -> particle.getMemRandom("rbs0")).doubleValue(),
                pitch.get(t, () -> particle.getMemRandom("rbs1")).doubleValue(),
                yaw.get(t, () -> particle.getMemRandom("rbs2")).doubleValue()).multiply(Mth.TWO_PI / 360);
    }

}
