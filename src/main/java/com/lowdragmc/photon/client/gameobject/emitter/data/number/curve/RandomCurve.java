package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import lombok.Setter;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RandomCurve
 */
@LDLRegisterClient(name = "random_curve", registry = "photon:number_function")
public class RandomCurve implements NumberFunction {

    @Setter
    @Getter
    @Persisted
    private float min, max, defaultValue;
    @Getter
    @Persisted
    private final ECBCurves curves0, curves1;
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

    public RandomCurve() {
        this(0, 0, 0, 0, 0, "", "");
    }

    public RandomCurve(float min, float max, float lower, float upper, float defaultValue, String xAxis, String yAxis) {
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        var y = (upper == lower) ? 0.5f : (defaultValue - lower) / (upper - lower);
        this.curves0 = new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y);
        this.curves1 = new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y);
    }

    public RandomCurve(float min, float max, float lower, float upper, float defaultValue, String xAxis, String yAxis, ECBCurves curves0, ECBCurves curves1) {
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.curves0 = curves0;
        this.curves1 = curves1;
    }

    public RandomCurve(NumberFunctionConfig config) {
        this(config.min(), config.max(),
                config.curveConfig().bound().length > 0 ? Math.max(config.min(), config.curveConfig().bound()[0]) : config.min(),
                config.curveConfig().bound().length > 1 ? Math.min(config.max(), config.curveConfig().bound()[1]) : config.max(),
                config.defaultValue(), config.curveConfig().xAxis(), config.curveConfig().yAxis());
    }

    @Override
    public Float get(float t, Supplier<Float> lerp) {
        var a = curves0.getCurveY(t);
        var b = curves1.getCurveY(t);
        var randomY = a == b ? a : (Math.min(a, b) + lerp.get() * Math.abs(a - b));
        return lower + (upper - lower) * randomY;
    }

    @Override
    public NumberFunction copy() {
        return new RandomCurve(min, max, lower, upper, defaultValue, xAxis, yAxis, curves0.copy(), curves1.copy());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RandomCurve curve) {
            return curves0.equals(curve.curves0) &&
                    curves1.equals(curve.curves1) &&
                    xAxis.equals(curve.xAxis) &&
                    yAxis.equals(curve.yAxis) &&
                    lower == curve.lower &&
                    upper == curve.upper &&
                    min == curve.min &&
                    max == curve.max &&
                    defaultValue == curve.defaultValue;
        }
        return super.equals(obj);
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
//        var background = ColorPattern.T_GRAY.rectTexture().setRadius(5);
//        group.addWidget(new ButtonWidget(0, 2, group.getSize().width, 10, new GuiTextureGroup(background, new RandomCurveTexture(curves0, curves1)), cd -> {
//            if (Editor.INSTANCE != null) {
//                var size = new Size(360, 100);
//                var position = group.getPosition();
//                var rightPlace = group.getGui().getScreenWidth() - size.width;
//                var dialog = Editor.INSTANCE.openDialog(new DialogWidget(Math.min(position.x, rightPlace), Math.max(0, position.y - size.height), size.width, size.height));
//                dialog.setClickClose(true);
//                dialog.addWidget(new ConfiguratorWidget(0, 0, size.width, size.height, curvesPair -> configurator.updateValue(this)));
//            }
//        }).setDraggingConsumer(
//                o -> o instanceof CurvesResource.Curves c && c.isRandomCurve(),
//                o -> background.setColor(ColorPattern.GREEN.color),
//                o -> background.setColor(ColorPattern.T_GRAY.color),
//                o -> {
//                    if (o instanceof CurvesResource.Curves c && c.curves1 != null) {
//                        this.curves0.deserializeNBT(c.curves0.serializeNBT());
//                        this.curves1.deserializeNBT(c.curves1.serializeNBT());
//                        configurator.updateValue(this);
//                        background.setColor(ColorPattern.T_GRAY.color);
//                    }
//                }));
    }

//
//    public class ConfiguratorWidget extends WidgetGroup {
//
//        public ConfiguratorWidget(int x, int y, int width, int height, Consumer<Pair<ECBCurves, ECBCurves>> onUpdate) {
//            super(x, y, width, height);
//
//            // bound setter
//            var upper = new NumberConfigurator("", () -> RandomCurve.this.upper, value -> RandomCurve.this.upper = value.floatValue(), defaultValue, true);
//            var lower = new NumberConfigurator("", () -> RandomCurve.this.lower, value -> RandomCurve.this.lower = value.floatValue(), defaultValue, true);
//            upper.setRange(min, max);
//            lower.setRange(min, max);
//            upper.init(60);
//            lower.init(60);
//            upper.addSelfPosition(0, 1);
//            lower.addSelfPosition(0, height - 15);
//
//            // axis
//            if (!xAxis.isBlank()) {
//                this.addWidget(new ImageWidget(60, height, width - 63, 10, new TextTexture(xAxis)));
//            }
//            if (!yAxis.isBlank()) {
//                this.addWidget(new ImageWidget(12, height / 2 - 5, 80, 10, new TextTexture(yAxis).rotate(-90)));
//            }
//
//            // curve line
//            var curveLine = new RandomCurveLineWidget(60, 3, width - 63, height - 7, curves0, curves1);
//            curveLine.setOnUpdate(onUpdate);
//            curveLine.setLockControlPoint(lockControlPoint);
//            curveLine.setGridSize(new Size(6, 2));
//            curveLine.setHoverTips(coord -> Component.literal(String.valueOf(RandomCurve.this.lower + coord.y * (RandomCurve.this.upper - RandomCurve.this.lower))));
//            curveLine.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
//            addWidget(curveLine);
//
//            this.addWidget(upper);
//            this.addWidget(lower);
//        }
//
//    }
}
