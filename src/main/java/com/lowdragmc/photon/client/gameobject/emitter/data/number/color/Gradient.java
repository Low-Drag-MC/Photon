package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.minecraft.util.RandomSource;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Gradient
 */
@LDLRegisterClient(name = "gradient", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class Gradient implements NumberFunction {

    @Getter
    @Persisted
    private final GradientColor gradientColor;

    public Gradient() {
        this.gradientColor = new GradientColor();
    }

    public Gradient(int color) {
        this.gradientColor = new GradientColor(color, color);
    }

    public void loadConfig(NumberFunctionConfig config) {
        var color = (int) config.defaultValue();
        gradientColor.getAP().clear();
        gradientColor.getRgbP().clear();
        var colors = new int[]{color, color};
        for (int i = 0; i < colors.length; i++) {
            var t = i / (colors.length - 1f);
            gradientColor.getAP().add(new Vector2f(t, ColorUtils.alpha(colors[i])));
            gradientColor.getRgbP().add(new Vector4f(t, ColorUtils.red(colors[i]), ColorUtils.green(colors[i]), ColorUtils.blue(colors[i])));
        }
    }

    public Gradient(GradientColor gradientColor) {
        this.gradientColor = gradientColor;
    }

    @Override
    public Integer get(RandomSource randomSource, float t) {
        return gradientColor.getColor(t);
    }

    @Override
    public Integer get(float t, Supplier<Float> lerp) {
        return gradientColor.getColor(t);
    }

    @Override
    public NumberFunction copy() {
        return new Gradient(gradientColor.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new GradientColorConfigurator("", gradientColor::copy, gradientColor -> {
            this.gradientColor.getAP().clear();
            this.gradientColor.getAP().addAll(gradientColor.getAP());
            this.gradientColor.getRgbP().clear();
            this.gradientColor.getRgbP().addAll(gradientColor.getRgbP());
            configurator.updateValue(this);
        }, getGradientColor(), true));
    }

}
