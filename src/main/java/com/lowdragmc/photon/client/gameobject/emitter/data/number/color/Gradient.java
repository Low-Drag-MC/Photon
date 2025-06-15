package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Gradient
 */
@LDLRegisterClient(name = "gradient", registry = "photon:number_function")
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

    public Gradient(NumberFunctionConfig config) {
        this((int) config.defaultValue());
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
    public boolean equals(Object obj) {
        if (obj instanceof Gradient gradient) {
            return gradient.gradientColor.equals(gradientColor);
        }
        return super.equals(obj);
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
