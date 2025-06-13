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
    public Float get(RandomSource randomSource, float t) {
        return (float) gradientColor.getColor(t);
    }

    @Override
    public Float get(float t, Supplier<Float> lerp) {
        return (float) gradientColor.getColor(t);
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
        // TODO configurator
//        var background = ColorPattern.T_GRAY.borderTexture(1);
//        group.addWidget(new ButtonWidget(0, 2, group.getSize().width, 10, new GuiTextureGroup(background, new GradientColorTexture(gradientColor)), cd -> {
//            if (Editor.INSTANCE != null) {
//                var size = new Size(160, 150 + 15 + 20 + 3);
//                var position = group.getPosition();
//                var rightPlace = group.getGui().getScreenWidth() - size.width;
//                var gradientWidget = new GradientColorWidget(5, 0, 150, gradientColor);
//                gradientWidget.setOnUpdate(g -> configurator.updateValue(this));
//                var dialog = Editor.INSTANCE.openDialog(new DialogWidget(Math.min(position.x, rightPlace), Math.max(0, position.y - size.height), size.width, size.height));
//                dialog.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
//                dialog.setClickClose(true);
//                dialog.addWidget(gradientWidget);
//            }
//        }).setDraggingConsumer(
//                o -> o instanceof GradientsResource.Gradients g && !g.isRandomGradient(),
//                o -> background.setColor(ColorPattern.GREEN.color),
//                o -> background.setColor(ColorPattern.T_GRAY.color),
//                o -> {
//                    if (o instanceof GradientsResource.Gradients g) {
//                        this.gradientColor.deserializeNBT(g.gradient0.serializeNBT());
//                        configurator.updateValue(this);
//                        background.setColor(ColorPattern.T_GRAY.color);
//                    }
//                }));
    }

}
