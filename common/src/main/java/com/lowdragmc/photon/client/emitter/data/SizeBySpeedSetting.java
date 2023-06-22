package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Range;
import org.joml.Vector3f;
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
    @Configurable(tips = "photon.emitter.config.sizeBySpeed.scale")
    @NumberFunctionConfig(types = {RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "speed", yAxis = "size scale"))
    protected NumberFunction scale = new RandomConstant(0f, 1f, true);

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.sizeBySpeed.size")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "speed", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(0, 0, 0);

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.sizeBySpeed.speedRange")
    @NumberRange(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);
    public Vector3f getSize(Vector3f startedSize, LParticle particle) {
        var value = particle.getVelocity().length() * 20;
        var t = (value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue());
        return size.get(t, () -> particle.getMemRandom("sbs0")).add(startedSize)
                .mul(scale.get(t, () -> particle.getMemRandom("sbs1")).floatValue());
    }

}
