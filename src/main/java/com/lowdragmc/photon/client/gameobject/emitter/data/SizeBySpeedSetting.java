package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote SizeOverLifetimeSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class SizeBySpeedSetting extends ToggleGroup {

    @Configurable(name = "NoiseSetting.size", tips = "photon.emitter.config.sizeBySpeed.size")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "speed", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(1, 1, 1);

    @Configurable(name = "ColorBySpeedSetting.speedRange", tips = "photon.emitter.config.sizeBySpeed.speedRange")
    @ConfigNumber(range = {0, 1000}, type = ConfigNumber.Type.FLOAT)
    protected Range speedRange = Range.of(0f, 1f);
    
    public Vector3f getSize(TileParticle particle) {
        var value = particle.getRealVelocity().length() * 20;
        var t = (value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue());
        return size.get(t, () -> particle.getMemRandom("sbs0"));
    }

}
