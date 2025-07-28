package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.util.Mth;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RotationOverLifetimeSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class RotationOverLifetimeSetting extends ToggleGroup {

    @Configurable(name = "RotationBySpeedSetting.roll", tips = "photon.emitter.config.rotation.roll")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "roll"))
    protected NumberFunction roll = NumberFunction.constant(0);

    @Configurable(name = "RotationBySpeedSetting.pitch", tips = "photon.emitter.config.rotation.pitch")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "pitch"))
    protected NumberFunction pitch = NumberFunction.constant(0);

    @Configurable(name = "RotationBySpeedSetting.yaw", tips = "photon.emitter.config.rotation.yaw")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "yaw"))
    protected NumberFunction yaw = NumberFunction.constant(0);

    public Vector3f getRotation(IParticle particle, float partialTicks) {
        var t = particle.getT(partialTicks);
        return new Vector3f(
                yaw.get(t, () -> particle.getMemRandom("rol2")).floatValue(),
                pitch.get(t, () -> particle.getMemRandom("rol1")).floatValue(),
                roll.get(t, () -> particle.getMemRandom("rol0")).floatValue()).mul(Mth.TWO_PI / 360);
    }

}
