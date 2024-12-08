package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Range;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;

import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote ColorBySpeedSetting
 */
@Environment(EnvType.CLIENT)
@Setter
@Getter
public class ColorBySpeedSetting extends ToggleGroup {

    @Configurable(tips = "photon.emitter.config.colorBySpeed.color")
    @NumberFunctionConfig(types = {Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Gradient();

    @Configurable(tips = "photon.emitter.config.colorBySpeed.speedRange")
    @NumberRange(range = {0, 1000})
    protected Range speedRange = new Range(0f, 1f);

    public Vector4f getColor(TileParticle particle) {
        var value = particle.getRealVelocity().length() * 20;
        var c = color.get(((value - speedRange.getA().floatValue()) / (speedRange.getB().floatValue() - speedRange.getA().floatValue())), () -> particle.getMemRandom(this)).intValue();
        return new Vector4f((c >> 16 & 0xff) / 255f, (c >> 8 & 0xff) / 255f, (c & 0xff) / 255f, (c >> 24 & 0xff) / 255f);
    }

}
