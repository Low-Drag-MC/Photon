package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
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
 * @date 2023/6/1
 * @implNote LightSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class LightOverLifetimeSetting extends ToggleGroup {

    @Configurable(name = "LightOverLifetimeSetting.skyLight", tips = "photon.emitter.config.lights.skyLight")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = ConfigNumber.Type.INTEGER, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction skyLight = NumberFunction.constant(15);

    @Configurable(name = "LightOverLifetimeSetting.blockLight", tips = "photon.emitter.config.lights.blockLight")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = ConfigNumber.Type.INTEGER, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction blockLight = NumberFunction.constant(15);

    public LightOverLifetimeSetting() {
        this.enable = true;
    }

    public int getLight(IParticle particle, float partialTicks) {
        int sky = skyLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("sky-light")).intValue();
        int block = blockLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("block-light")).intValue();
        return sky << 20 | block << 4;
    }
}
