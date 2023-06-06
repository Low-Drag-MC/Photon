package com.lowdragmc.photon.client.emitter.data.number.color;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.GradientColorWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.GradientColor;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.editor.GradientsResource;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Gradient
 */
public class Gradient implements NumberFunction {

    @Getter
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

    @Override
    public Number get(RandomSource randomSource, float t) {
        return gradientColor.getColor(t);
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        return gradientColor.getColor(t);
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var background = ColorPattern.T_GRAY.borderTexture(1);
        group.addWidget(new ButtonWidget(0, 2, group.getSize().width, 10, new GuiTextureGroup(background, new GradientColorTexture(gradientColor)), cd -> {
            if (Editor.INSTANCE != null) {
                var size = new Size(160, 150 + 15 + 20 + 3);
                var position = group.getPosition();
                var rightPlace = group.getGui().getScreenWidth() - size.width;
                var gradientWidget = new GradientColorWidget(5, 0, 150, gradientColor);
                gradientWidget.setOnUpdate(g -> configurator.updateValue());
                var dialog = Editor.INSTANCE.openDialog(new DialogWidget(Math.min(position.x, rightPlace), Math.max(0, position.y - size.height), size.width, size.height));
                dialog.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
                dialog.setClickClose(true);
                dialog.addWidget(gradientWidget);
            }
        }).setDraggingConsumer(
                o -> o instanceof GradientsResource.Gradients g && !g.isRandomGradient(),
                o -> background.setColor(ColorPattern.GREEN.color),
                o -> background.setColor(ColorPattern.T_GRAY.color),
                o -> {
                    if (o instanceof GradientsResource.Gradients g) {
                        this.gradientColor.deserializeNBT(g.gradient0.serializeNBT());
                        configurator.updateValue();
                        background.setColor(ColorPattern.T_GRAY.color);
                    }
                }));
    }

    @Override
    public CompoundTag serializeNBT() {
        return gradientColor.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        gradientColor.deserializeNBT(nbt);
    }
}
