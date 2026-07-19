package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote SizeOverLifetimeSetting
 */
@Setter
@Getter
public class SizeBySpeedSetting extends ToggleGroup {

    @Configurable(name = "NoiseSetting.size", tips = "photon.emitter.config.sizeBySpeed.size")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "speed", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(1, 1, 1);

    @Configurable(name = "ColorBySpeedSetting.speedRange", tips = "photon.emitter.config.sizeBySpeed.speedRange")
    @ConfigNumber(range = {0, 1000}, type = ConfigNumber.Type.FLOAT)
    protected Range speedRange = Range.of(0f, 1f);
    
    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final SizeBySpeedSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction3> size;
        public final RuntimeValue<Range> speedRange; // slot only (Range → no timeline binding)

        public Runtime(SizeBySpeedSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.size = new RuntimeValue<>(config::getSize);
            this.speedRange = new RuntimeValue<>(config::getSpeedRange);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector3f getSize(TileParticle particle) {
            var value = particle.getRealVelocity().length() * 20;
            var range = speedRange.get();
            var a = range.getA().floatValue();
            var b = range.getB().floatValue();
            // zero-width range would divide by zero; clamp so curves sample inside [0, 1]
            var t = b > a ? Math.clamp((value - a) / (b - a), 0f, 1f) : (value >= a ? 1f : 0f);
            return size.get().get(t, () -> particle.getMemRandom("sbs0"));
        }

        public void clear() {
            enable.clear();
            size.clear();
            speedRange.clear();
        }
    }

}
