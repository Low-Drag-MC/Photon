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
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote RandomGradient
 */
@LDLRegisterClient(name = "random_gradient", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class RandomGradient implements NumberFunction {

    @Getter
    @Persisted
    private final GradientColor gradientColor0, gradientColor1;

    public RandomGradient() {
        this.gradientColor0 = new GradientColor();
        this.gradientColor1 = new GradientColor();
    }

    public RandomGradient(int color) {
        this.gradientColor0 = new GradientColor(color, color);
        this.gradientColor1 = new GradientColor(color, color);
    }

    public RandomGradient(GradientColor a, GradientColor b) {
        this.gradientColor0 = a;
        this.gradientColor1 = b;
    }

    public void loadConfig(NumberFunctionConfig config) {
        var color = (int) config.defaultValue();
        gradientColor0.getAP().clear();
        gradientColor0.getRgbP().clear();
        gradientColor1.getAP().clear();
        gradientColor1.getRgbP().clear();
        var colors = new int[]{color, color};
        for (int i = 0; i < colors.length; i++) {
            var t = i / (colors.length - 1f);
            gradientColor0.getAP().add(new Vector2f(t, ColorUtils.alpha(colors[i])));
            gradientColor0.getRgbP().add(new Vector4f(t, ColorUtils.red(colors[i]), ColorUtils.green(colors[i]), ColorUtils.blue(colors[i])));
            gradientColor1.getAP().add(new Vector2f(t, ColorUtils.alpha(colors[i])));
            gradientColor1.getRgbP().add(new Vector4f(t, ColorUtils.red(colors[i]), ColorUtils.green(colors[i]), ColorUtils.blue(colors[i])));
        }
    }

    @Override
    public Integer get(float t, Supplier<Float> lerp) {
        int color0 = gradientColor0.getColor(t);
        int color1 = gradientColor1.getColor(t);
        return ColorUtils.blendColor(color0, color1, lerp.get());
    }

    @Override
    public NumberFunction copy() {
        return new RandomGradient(gradientColor0.copy(), gradientColor1.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new RandomGradientColorConfigurator("", () -> Pair.of(gradientColor0.copy(), gradientColor1.copy()), gradientColors -> {
            this.gradientColor0.getAP().clear();
            this.gradientColor0.getAP().addAll(gradientColors.getLeft().getAP());
            this.gradientColor0.getRgbP().clear();
            this.gradientColor0.getRgbP().addAll(gradientColors.getLeft().getRgbP());

            this.gradientColor1.getAP().clear();
            this.gradientColor1.getAP().addAll(gradientColors.getRight().getAP());
            this.gradientColor1.getRgbP().clear();
            this.gradientColor1.getRgbP().addAll(gradientColors.getRight().getRgbP());
            configurator.updateValue(this);
        }, Pair.of(gradientColor0.copy(), gradientColor1.copy()), true));
    }

}
