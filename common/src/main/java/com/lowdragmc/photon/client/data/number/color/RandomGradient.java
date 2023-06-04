package com.lowdragmc.photon.client.data.number.color;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.GradientColorWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.GradientColor;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.editor.GradientsResource;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote RandomGradient
 */
public class RandomGradient implements NumberFunction {

    @Getter
    private final GradientColor gradientColor0, gradientColor1;

    public RandomGradient() {
        this.gradientColor0 = new GradientColor();
        this.gradientColor1 = new GradientColor();
    }

    public RandomGradient(int color) {
        this.gradientColor0 = new GradientColor(color, color);
        this.gradientColor1 = new GradientColor(color, color);
    }

    public RandomGradient(NumberFunctionConfig config) {
        this((int) config.defaultValue());
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        int color0 = gradientColor0.getColor(t);
        int color1 = gradientColor1.getColor(t);
        return ColorUtils.blendColor(color0, color1, lerp.get());
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var background = ColorPattern.T_GRAY.borderTexture(1);
        group.addWidget(new ButtonWidget(0, 2, group.getSize().width, 10, new GuiTextureGroup(background, new RandomGradientColorTexture(gradientColor0, gradientColor1)), cd -> {
            if (Editor.INSTANCE != null) {
                var size = new Size(315, 150 + 15 + 20 + 3);
                var position = group.getPosition();
                var rightPlace = group.getGui().getScreenWidth() - size.width;
                var gradientWidget0 = new GradientColorWidget(5, 0, 150, gradientColor0);
                gradientWidget0.setOnUpdate(g -> configurator.updateValue());

                var gradientWidget1 = new GradientColorWidget(160, 0, 150, gradientColor1);
                gradientWidget1.setOnUpdate(g -> configurator.updateValue());

                var dialog = Editor.INSTANCE.openDialog(new DialogWidget(Math.min(position.x, rightPlace), Math.max(0, position.y - size.height), size.width, size.height));
                dialog.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
                dialog.setClickClose(true);
                dialog.addWidget(gradientWidget0);
                dialog.addWidget(gradientWidget1);
            }
        }).setDraggingConsumer(
                o -> o instanceof GradientsResource.Gradients g && g.isRandomGradient(),
                o -> background.setColor(ColorPattern.GREEN.color),
                o -> background.setColor(ColorPattern.T_GRAY.color),
                o -> {
                    if (o instanceof GradientsResource.Gradients g && g.gradient1 != null) {
                        this.gradientColor0.deserializeNBT(g.gradient0.serializeNBT());
                        this.gradientColor1.deserializeNBT(g.gradient1.serializeNBT());
                        configurator.updateValue();
                        background.setColor(ColorPattern.T_GRAY.color);
                    }
                }));
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.put("a", gradientColor0.serializeNBT());
        tag.put("b", gradientColor1.serializeNBT());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        gradientColor0.deserializeNBT(tag.getCompound("a"));
        gradientColor1.deserializeNBT(tag.getCompound("b"));
    }
}
