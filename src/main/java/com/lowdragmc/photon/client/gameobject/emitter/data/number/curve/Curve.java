package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Curve
 */
@LDLRegisterClient(name = "curve", registry = "photon:number_function")
public class Curve implements NumberFunction {
    @Setter
    @Getter
    @Persisted
    private float min, max, defaultValue;
    @Getter
    @Persisted
    private final ECBCurves curves;
    @Setter
    @Getter
    @Persisted
    private String xAxis, yAxis;
    @Setter
    @Getter
    @Persisted
    protected boolean lockControlPoint = true;
    @Setter
    @Getter
    @Persisted
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

    public Curve(float min, float max, float lower, float upper, float defaultValue, String xAxis, String yAxis, ECBCurves curves) {
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.curves = curves;
    }

    public Curve(NumberFunctionConfig config) {
        this(config.min(), config.max(),
                config.curveConfig().bound().length > 0 ? Math.max(config.min(), config.curveConfig().bound()[0]) : config.min(),
                config.curveConfig().bound().length > 1 ? Math.min(config.max(), config.curveConfig().bound()[1]) : config.max(),
                config.defaultValue(), config.curveConfig().xAxis(), config.curveConfig().yAxis());
    }

    @Override
    public Float get(RandomSource randomSource, float t) {
        return lower + (upper - lower) * curves.getCurveY(t);
    }

    @Override
    public Float get(float t, Supplier<Float> lerp) {
        return lower + (upper - lower) * curves.getCurveY(t);
    }

    @Override
    public NumberFunction copy() {
        return new Curve(min, max, lower, upper, defaultValue, xAxis, yAxis, curves.copy());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Curve curve) {
            return min == curve.min && max == curve.max && defaultValue == curve.defaultValue && lower == curve.lower && upper == curve.upper && curves.equals(curve.curves) && xAxis.equals(curve.xAxis) && yAxis.equals(curve.yAxis);
        }
        return super.equals(obj);
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        // TODO Configurator
//        var background = ColorPattern.T_GRAY.rectTexture().setRadius(5);
//        group.addWidget(new ButtonWidget(0, 2, group.getSize().width, 10, new GuiTextureGroup(background, new CurveTexture(curves)), cd -> {
//            if (Editor.INSTANCE != null) {
//                var size = new Size(360, 100);
//                var position = group.getPosition();
//                var rightPlace = group.getGui().getScreenWidth() - size.width;
//                var dialog = Editor.INSTANCE.openDialog(new DialogWidget(Math.min(position.x, rightPlace), Math.max(0, position.y - size.height), size.width, size.height));
//                dialog.setClickClose(true);
//                dialog.addWidget(new ConfiguratorWidget(0, 0, size.width, size.height, curves -> configurator.updateValue(this)));
//            }
//        }).setDraggingConsumer(
//                o -> o instanceof CurvesResource.Curves c && !c.isRandomCurve(),
//                o -> background.setColor(ColorPattern.GREEN.color),
//                o -> background.setColor(ColorPattern.T_GRAY.color),
//                o -> {
//                    if (o instanceof CurvesResource.Curves c) {
//                        this.curves.deserializeNBT(c.curves0.serializeNBT());
//                        configurator.updateValue(this);
//                        background.setColor(ColorPattern.T_GRAY.color);
//                    }
//                }));
    }

//    public class ConfiguratorWidget extends WidgetGroup {
//
//        public ConfiguratorWidget(int x, int y, int width, int height, Consumer<ECBCurves> onUpdate) {
//            super(x, y, width, height);
//
//            // bound setter
//            var upper = new NumberConfigurator("", () -> Curve.this.upper, value -> Curve.this.upper = value.floatValue(), defaultValue, true);
//            var lower = new NumberConfigurator("", () -> Curve.this.lower, value -> Curve.this.lower = value.floatValue(), defaultValue, true);
//            upper.setRange(min, max);
//            lower.setRange(min, max);
//            upper.init(60);
//            lower.init(60);
//            upper.addSelfPosition(0, 1);
//            lower.addSelfPosition(0, height - 15);
//
//            // curve line
//            var curveLine = new CurveLineWidget(60, 3, width - 63, height - 7, Curve.this.curves);
//            curveLine.setOnUpdate(onUpdate);
//            curveLine.setLockControlPoint(lockControlPoint);
//            curveLine.setGridSize(new Size(6, 2));
//            curveLine.setHoverTips(coord -> Component.literal(String.valueOf(Curve.this.lower + coord.y * (Curve.this.upper - Curve.this.lower))));
//            curveLine.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
//
//            // axis
//            if (!xAxis.isBlank()) {
//                this.addWidget(new ImageWidget(60, height, width - 63, 10, new TextTexture(xAxis)));
//            }
//            if (!yAxis.isBlank()) {
//                this.addWidget(new ImageWidget(12, height / 2 - 5, 80, 10, new TextTexture(yAxis).rotate(-90)));
//            }
//
//            this.addWidget(curveLine);
//            this.addWidget(upper);
//            this.addWidget(lower);
//        }
//
//    }
}
