package com.lowdragmc.photon.client.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.data.number.*;
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
 * @date 2023/5/30
 * @implNote SizeOverLifetimeSetting
 */
@Environment(EnvType.CLIENT)
public class SizeOverLifetimeSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "Controls the scale of size during its lifetime.")
    @NumberFunctionConfig(types = {RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "size scale"))
    protected NumberFunction scale = new RandomConstant(0f, 1f, true);


    @Setter
    @Getter
    @Configurable(tips = "Controls the size of separated axis during its lifetime.")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(0.1, 0.1, 0.1);

    public Vector3 getSize(LParticle particle, float partialTicks) {
        return size.get(particle.getT(partialTicks), () -> particle.getMemRandom("sol0"))
                .multiply(scale.get(particle.getT(partialTicks), () -> particle.getMemRandom("sol1")).doubleValue());
    }

}
