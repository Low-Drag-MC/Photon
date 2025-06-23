package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RandomCurve
 */
@LDLRegisterClient(name = "random_curve", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class RandomCurve implements NumberFunction {
    @Setter
    @Getter
    @Persisted
    private float min, max;
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
    @EqualsAndHashCode.Exclude
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
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        var y = (upper == lower) ? 0.5f : (defaultValue - lower) / (upper - lower);
        this.curves0 = new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y);
        this.curves1 = new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y);
    }

    public RandomCurve(float min, float max, float lower, float upper, String xAxis, String yAxis, ECBCurves curves0, ECBCurves curves1) {
        this.min = min;
        this.max = max;
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.curves0 = curves0;
        this.curves1 = curves1;
    }

    public void loadConfig(NumberFunctionConfig config) {
        this.min = config.min();
        this.max = config.max();
        this.lower = config.curveConfig().bound().length > 0 ? Math.max(config.min(), config.curveConfig().bound()[0]) : config.min();
        this.upper = config.curveConfig().bound().length > 1 ? Math.min(config.max(), config.curveConfig().bound()[1]) : config.max();
        this.xAxis = config.curveConfig().xAxis();
        this.yAxis = config.curveConfig().yAxis();
        var y = (upper == lower) ? 0.5f : ((float)config.defaultValue() - lower) / (upper - lower);
        this.curves0.getSegments().clear();
        this.curves0.getSegments().addAll(new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y).getSegments());
        this.curves1.getSegments().clear();
        this.curves1.getSegments().addAll(new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y).getSegments());
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
        return new RandomCurve(min, max, lower, upper, xAxis, yAxis, curves0.copy(), curves1.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new RandomCurveConfigurator("", () -> this, curves -> configurator.updateValue(this), this, true));
    }
}
