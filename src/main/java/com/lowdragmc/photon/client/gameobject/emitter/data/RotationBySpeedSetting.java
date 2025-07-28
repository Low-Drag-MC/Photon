package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.util.Mth;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RotationBySpeedSetting
 */
@OnlyIn(Dist.CLIENT)
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

    public Vector3f getRotation(TileParticle particle) {
        var value = particle.getRealVelocity().length() * 20;
        var t = ((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue()));
        return new Vector3f(
                roll.get(t, () -> particle.getMemRandom("rbs0")).floatValue(),
                pitch.get(t, () -> particle.getMemRandom("rbs1")).floatValue(),
                yaw.get(t, () -> particle.getMemRandom("rbs2")).floatValue()).mul(Mth.TWO_PI / 360);
    }

}
