package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;

import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector4f;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote ColorBySpeedSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class ColorBySpeedSetting extends ToggleGroup {

    @Configurable(name = "color", tips = "photon.emitter.config.colorBySpeed.color")
    @NumberFunctionConfig(types = {Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Gradient();

    @Configurable(name = "ColorBySpeedSetting.speedRange", tips = "photon.emitter.config.colorBySpeed.speedRange")
    @ConfigNumber(range = {0, 1000})
    protected Range speedRange = Range.of(0f, 1f);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final ColorBySpeedSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> color;
        public final RuntimeValue<Range> speedRange; // slot only (Range → no timeline binding)

        public Runtime(ColorBySpeedSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.color = new RuntimeValue<>(config::getColor);
            this.speedRange = new RuntimeValue<>(config::getSpeedRange);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector4f getColor(TileParticle particle) {
            var value = particle.getRealVelocity().length() * 20;
            var range = speedRange.get();
            var a = range.getA().floatValue();
            var b = range.getB().floatValue();
            // zero-width range would divide by zero; clamp so curves/gradients sample inside [0, 1]
            var t = b > a ? Math.clamp((value - a) / (b - a), 0f, 1f) : (value >= a ? 1f : 0f);
            var c = color.get().get(t, () -> particle.getMemRandom(this)).intValue();
            return new Vector4f((c >> 16 & 0xff) / 255f, (c >> 8 & 0xff) / 255f, (c & 0xff) / 255f, (c >> 24 & 0xff) / 255f);
        }

        public void clear() {
            enable.clear();
            color.clear();
            speedRange.clear();
        }
    }

}
