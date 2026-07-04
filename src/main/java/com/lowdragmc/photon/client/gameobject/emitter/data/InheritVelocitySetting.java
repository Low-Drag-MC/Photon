package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote InheritVelocitySetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class InheritVelocitySetting extends ToggleGroup {
    public enum Mode {
        CURRENT,
        INITIAL,
    }

    @Configurable(name = "InheritVelocitySetting.mode", tips = "photon.emitter.config.inheritVelocity.mode")
    protected Mode mode = Mode.INITIAL;

    @Configurable(name = "InheritVelocitySetting.multiply", tips = "photon.emitter.config.inheritVelocity.multiply")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction multiply = NumberFunction.constant(1);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final InheritVelocitySetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Mode> mode; // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction> multiply;

        public Runtime(InheritVelocitySetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.mode = new RuntimeValue<>(config::getMode);
            this.multiply = new RuntimeValue<>(config::getMultiply);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Mode getMode() {
            return mode.get();
        }

        public Vector3f getVelocity(IParticleEmitter emitter) {
            return emitter.getVelocity().mul(multiply.get().get(emitter.getT(), () -> emitter.getMemRandom(this)).floatValue());
        }

        public void clear() {
            enable.clear();
            mode.clear();
            multiply.clear();
        }
    }

}
