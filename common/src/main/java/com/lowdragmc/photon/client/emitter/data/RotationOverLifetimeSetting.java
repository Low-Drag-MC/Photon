package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
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
 * @implNote RotationOverLifetimeSetting
 */
@Environment(EnvType.CLIENT)
public class RotationOverLifetimeSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "The roll of particles. (degree)")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "roll"))
    protected NumberFunction roll = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "The pitch of particles. (degree)")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "pitch"))
    protected NumberFunction pitch = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "The yaw of particles. (degree)")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "lifetime", yAxis = "yaw"))
    protected NumberFunction yaw = NumberFunction.constant(0);

    public Vector3 getRotation(LParticle particle, float partialTicks) {
        var t = particle.getT(partialTicks);
        return new Vector3(
                roll.get(t, () -> particle.getMemRandom("rol0")).doubleValue(),
                pitch.get(t, () -> particle.getMemRandom("rol1")).doubleValue(),
                yaw.get(t, () -> particle.getMemRandom("rol2")).doubleValue()).multiply(Mth.TWO_PI / 360);
    }

}
