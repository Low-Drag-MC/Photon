package com.lowdragmc.photon.client.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.photon.client.data.number.Constant;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.data.number.RandomConstant;
import com.lowdragmc.photon.client.data.number.curve.Curve;
import com.lowdragmc.photon.client.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.data.number.curve.RandomCurve;
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
    @Configurable(tips = "Sky Light value for lighting map. (0-15)")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction skyLight = NumberFunction.constant(15);
    @Setter
    @Getter
    @Configurable(tips = "Block Light value for lighting map. (0-15)")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction blockLight = NumberFunction.constant(15);

    public int getLight(LParticle particle, float partialTicks) {
        int sky = skyLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("sky-light")).intValue();
        int block = blockLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("block-light")).intValue();
        return sky << 20 | block << 4;
    }
}
