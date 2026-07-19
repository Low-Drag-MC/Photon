package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;

/**
 * Pure-data size-over-lifetime config; value-use behaviour lives on the co-located {@link Runtime}.
 *
 * @author KilaBash
 * @date 2023/5/30
 */
@Setter
@Getter
public class SizeOverLifetimeSetting extends ToggleGroup {

    @Configurable(name = "NoiseSetting.size", tips = "photon.emitter.config.sizeOverLifetime.size")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(1, 1, 1);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final SizeOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction3> size;

        public Runtime(SizeOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.size = new RuntimeValue<>(() -> config.size);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector3f getSize(IParticle particle, float partialTicks) {
            return size.get().get(particle.getT(partialTicks), () -> particle.getMemRandom("sol0"));
        }

        public void clear() {
            enable.clear();
            size.clear();
        }
    }
}
