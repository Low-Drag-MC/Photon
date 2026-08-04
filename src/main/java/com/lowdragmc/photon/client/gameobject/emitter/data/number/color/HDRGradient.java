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
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * The HDR counterpart of {@link Gradient}. Storage is the very same {@link GradientColor} — its rgb
 * stops are already unclamped floats — the difference is that here they hold <b>premultiplied</b>
 * values that may exceed 1, and the editor splits each stop back into a base colour + intensity (see
 * {@link GradientColorSelector}).
 *
 * <p>Sampling goes through {@link GradientColor#getRGB} rather than {@code getColor}, which would
 * clamp the result back into 8-bit ARGB.
 */
@LDLRegisterClient(name = "hdr_gradient", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class HDRGradient implements HDRColorFunction {

    @Getter
    @Persisted
    private final GradientColor gradientColor;

    public HDRGradient() {
        this.gradientColor = new GradientColor();
    }

    public HDRGradient(int color) {
        this.gradientColor = new GradientColor(color, color);
    }

    public HDRGradient(GradientColor gradientColor) {
        this.gradientColor = gradientColor;
    }

    @Override
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

    @Override
    public void sampleHDR(float t, Supplier<Float> lerp, Vector4f out) {
        var rgb = gradientColor.getRGB(t);
        out.set(rgb.x, rgb.y, rgb.z, gradientColor.getAlpha(t));
    }

    @Override
    public NumberFunction copy() {
        return new HDRGradient(gradientColor.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new GradientColorConfigurator("", gradientColor::copy, gradientColor -> {
            this.gradientColor.getAP().clear();
            this.gradientColor.getAP().addAll(gradientColor.getAP());
            this.gradientColor.getRgbP().clear();
            this.gradientColor.getRgbP().addAll(gradientColor.getRgbP());
            configurator.updateValue(this);
        }, getGradientColor(), true, true));
    }

}
