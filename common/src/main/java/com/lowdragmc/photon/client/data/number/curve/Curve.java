package com.lowdragmc.photon.client.data.number.curve;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.configurator.NumberConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.editor.CurvesResource;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Curve
 */
public class Curve implements NumberFunction {

    @Setter
    @Getter
    private float min, max, defaultValue;
    @Setter
    @Getter
    private ECBCurves curves;
    @Setter
    @Getter
    private String xAxis, yAxis;
    @Setter
    @Getter
    protected boolean lockControlPoint = true;
    @Setter
    @Getter
    private float lower, upper;

    public Curve() {
        this(0, 0, 0, 0, 0, "", "");
    }

    public Curve(float min, float max, float lower, float upper, float defaultValue, String xAxis, String yAxis) {
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        var y = (upper == lower) ? 0.5f : (defaultValue - lower) / (upper - lower);
        this.curves = new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y);
    }

    public Curve(NumberFunctionConfig config) {
        this(config.min(), config.max(),
                config.curveConfig().bound().length > 0 ? Math.max(config.min(), config.curveConfig().bound()[0]) : config.min(),
                config.curveConfig().bound().length > 1 ? Math.min(config.max(), config.curveConfig().bound()[1]) : config.max(),
                config.defaultValue(), config.curveConfig().xAxis(), config.curveConfig().yAxis());
    }

    @Override
    public Number get(RandomSource randomSource, float t) {
        return lower + (upper - lower) * curves.getCurveY(t);
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        return lower + (upper - lower) * curves.getCurveY(t);
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var background = ColorPattern.T_GRAY.rectTexture().setRadius(5);
        group.addWidget(new ButtonWidget(0, 2, group.getSize().width, 10, new GuiTextureGroup(background, new CurveTexture(curves)), cd -> {
            if (Editor.INSTANCE != null) {
                var size = new Size(360, 100);
                var position = group.getPosition();
                var rightPlace = group.getGui().getScreenWidth() - size.width;
                var dialog = Editor.INSTANCE.openDialog(new DialogWidget(Math.min(position.x, rightPlace), Math.max(0, position.y - size.height), size.width, size.height));
                dialog.setClickClose(true);
                dialog.addWidget(new ConfiguratorWidget(0, 0, size.width, size.height, curves -> configurator.updateValue()));
            }
        }).setDraggingConsumer(
                o -> o instanceof CurvesResource.Curves c && !c.isRandomCurve(),
                o -> background.setColor(ColorPattern.GREEN.color),
                o -> background.setColor(ColorPattern.T_GRAY.color),
                o -> {
                    if (o instanceof CurvesResource.Curves c) {
                        this.curves.deserializeNBT(c.curves0.serializeNBT());
                        configurator.updateValue();
                        background.setColor(ColorPattern.T_GRAY.color);
                    }
                }));
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.putFloat("min", min);
        tag.putFloat("max", max);
        tag.putFloat("defaultValue", defaultValue);
        tag.putFloat("lower", lower);
        tag.putFloat("upper", upper);
        tag.put("curves", curves.serializeNBT());
        tag.putString("xAxis", xAxis);
        tag.putString("yAxis", yAxis);
        tag.putBoolean("lockControlPoint", lockControlPoint);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        min = tag.getFloat("min");
        max = tag.getFloat("max");
        defaultValue = tag.getFloat("defaultValue");
        lower = tag.getFloat("lower");
        upper = tag.getFloat("upper");
        curves.deserializeNBT(tag.getList("curves", Tag.TAG_LIST));
        xAxis = tag.getString("xAxis");
        yAxis = tag.getString("yAxis");
        lockControlPoint = tag.getBoolean("lockControlPoint");
    }

    public class ConfiguratorWidget extends WidgetGroup {

        public ConfiguratorWidget(int x, int y, int width, int height, Consumer<ECBCurves> onUpdate) {
            super(x, y, width, height);

            // bound setter
            var upper = new NumberConfigurator("", () -> Curve.this.upper, value -> Curve.this.upper = value.floatValue(), defaultValue, true);
            var lower = new NumberConfigurator("", () -> Curve.this.lower, value -> Curve.this.lower = value.floatValue(), defaultValue, true);
            upper.setRange(min, max);
            lower.setRange(min, max);
            upper.init(60);
            lower.init(60);
            upper.addSelfPosition(0, 1);
            lower.addSelfPosition(0, height - 15);

            // curve line
            var curveLine = new CurveLineWidget(60, 3, width - 63, height - 7, Curve.this.curves);
            curveLine.setOnUpdate(onUpdate);
            curveLine.setLockControlPoint(lockControlPoint);
            curveLine.setGridSize(new Size(6, 2));
            curveLine.setHoverTips(coord -> Component.literal(String.valueOf(Curve.this.lower + coord.y * (Curve.this.upper - Curve.this.lower))));
            curveLine.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));

            // axis
            if (!xAxis.isBlank()) {
                this.addWidget(new ImageWidget(60, height, width - 63, 10, new TextTexture(xAxis)));
            }
            if (!yAxis.isBlank()) {
                this.addWidget(new ImageWidget(12, height / 2 - 5, 80, 10, new TextTexture(yAxis).rotate(-90)));
            }

            this.addWidget(curveLine);
            this.addWidget(upper);
            this.addWidget(lower);
        }

    }
}
