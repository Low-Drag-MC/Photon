package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
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
 * Pure-data light-over-lifetime config; value-use behaviour lives on the co-located {@link Runtime}.
 *
 * @author KilaBash
 * @date 2023/6/1
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class LightOverLifetimeSetting extends ToggleGroup {

    @Configurable(name = "LightOverLifetimeSetting.skyLight", tips = "photon.emitter.config.lights.skyLight")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = ConfigNumber.Type.INTEGER, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "sky light"))
    protected NumberFunction skyLight = NumberFunction.constant(15);

    @Configurable(name = "LightOverLifetimeSetting.blockLight", tips = "photon.emitter.config.lights.blockLight")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = ConfigNumber.Type.INTEGER, defaultValue = 15, min = 0, max = 15, wheelDur = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "block light"))
    protected NumberFunction blockLight = NumberFunction.constant(15);

    public LightOverLifetimeSetting() {
        // intentionally enabled by default: photon particles render full-bright (15/15)
        // unless the author disables this group to pick up world lighting
        this.enable = true;
    }

    /** Plain (non-overridable) read used by emitter kinds without a runtime layer (Beam/Trail). */
    public int getLight(IParticle particle, float partialTicks) {
        int sky = skyLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("sky-light")).intValue();
        int block = blockLight.get(particle.getT(partialTicks), () -> particle.getMemRandom("block-light")).intValue();
        return sky << 20 | block << 4;
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final LightOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> skyLight;
        public final RuntimeValue<NumberFunction> blockLight;

        public Runtime(LightOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.skyLight = new RuntimeValue<>(() -> config.skyLight);
            this.blockLight = new RuntimeValue<>(() -> config.blockLight);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public int getLight(IParticle particle, float partialTicks) {
            int sky = skyLight.get().get(particle.getT(partialTicks), () -> particle.getMemRandom("sky-light")).intValue();
            int block = blockLight.get().get(particle.getT(partialTicks), () -> particle.getMemRandom("block-light")).intValue();
            return sky << 20 | block << 4;
        }

        public void clear() {
            enable.clear();
            skyLight.clear();
            blockLight.clear();
        }
    }
}
