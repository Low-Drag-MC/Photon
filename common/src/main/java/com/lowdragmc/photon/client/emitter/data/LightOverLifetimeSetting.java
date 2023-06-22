package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
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
 * @date 2023/6/1
 * @implNote LightSetting
 */
@Environment(EnvType.CLIENT)
public class LightOverLifetimeSetting extends ToggleGroup {
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.lights.skyLight")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction skyLight = NumberFunction.constant(15);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.lights.blockLight")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction blockLight = NumberFunction.constant(15);

    public LightOverLifetimeSetting() {
        this.enable = true;
    }

    public int getLight(LParticle particle, float partialTicks) {
        int sky = skyLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("sky-light")).intValue();
        int block = blockLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("block-light")).intValue();
        return sky << 20 | block << 4;
    }
}
