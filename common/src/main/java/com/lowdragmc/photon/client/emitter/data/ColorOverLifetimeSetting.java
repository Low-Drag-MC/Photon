package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote ColorOverLifetimeSetting
 */
@Environment(EnvType.CLIENT)
public class ColorOverLifetimeSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.colorOverLifetime.color")
    @NumberFunctionConfig(types = {Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Gradient();

    public int getColor(LParticle particle, float partialTicks) {
        return color.get(particle.getT(partialTicks), () -> particle.getMemRandom(this)).intValue();
    }

}
