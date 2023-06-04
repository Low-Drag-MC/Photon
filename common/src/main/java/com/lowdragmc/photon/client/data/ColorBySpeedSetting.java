package com.lowdragmc.photon.client.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Range;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.data.number.color.Gradient;
import com.lowdragmc.photon.client.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote ColorBySpeedSetting
 */
@Environment(EnvType.CLIENT)
public class ColorBySpeedSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "Apply force. (e.g. gravity)")
    @NumberFunctionConfig(types = {Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Gradient();
    @Setter
    @Getter
    @Configurable(tips = "Remaps speed in the defined range to a color.")
    @NumberRange(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);

    public int getColor(LParticle particle) {
        var value = particle.getVelocity().mag() * 20;
        return color.get((float) ((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue())), () -> particle.getMemRandom(this)).intValue();
    }

}
