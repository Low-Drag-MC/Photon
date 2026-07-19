package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector4f;

/**
 * Pure-data color-over-lifetime config; value-use behaviour lives on the co-located {@link Runtime}.
 *
 * @author KilaBash
 * @date 2023/5/30
 */
@Setter
@Getter
public class ColorOverLifetimeSetting extends ToggleGroup {


    @Configurable(name = "color", tips = "photon.emitter.config.colorOverLifetime.color")
    @NumberFunctionConfig(types = {Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Gradient();

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final ColorOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> color;

        public Runtime(ColorOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.color = new RuntimeValue<>(() -> config.color);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector4f getColor(IParticle particle, float partialTicks) {
            var c = color.get().get(particle.getT(partialTicks), () -> particle.getMemRandom(this)).intValue();
            return new Vector4f((c >> 16 & 0xff) / 255f, (c >> 8 & 0xff) / 255f, (c & 0xff) / 255f, (c >> 24 & 0xff) / 255f);
        }

        public void clear() {
            enable.clear();
            color.clear();
        }
    }

}
