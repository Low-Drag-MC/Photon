package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import lombok.EqualsAndHashCode;
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
@EqualsAndHashCode(callSuper = false)
public class Curve implements NumberFunction {
    @Setter
    @Getter
    @Persisted
    private float min, max;
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
    @EqualsAndHashCode.Exclude
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
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        var y = (upper == lower) ? 0.5f : (defaultValue - lower) / (upper - lower);
        this.curves = new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y);
    }

    public Curve(float min, float max, float lower, float upper, String xAxis, String yAxis, ECBCurves curves) {
        this.min = min;
        this.max = max;
        this.lower = lower;
        this.upper = upper;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.curves = curves;
    }

    public void loadConfig(NumberFunctionConfig config) {
        this.min = config.min();
        this.max = config.max();
        this.lower = config.curveConfig().bound().length > 0 ? Math.max(config.min(), config.curveConfig().bound()[0]) : config.min();
        this.upper = config.curveConfig().bound().length > 1 ? Math.min(config.max(), config.curveConfig().bound()[1]) : config.max();
        this.xAxis = config.curveConfig().xAxis();
        this.yAxis = config.curveConfig().yAxis();
        var y = (upper == lower) ? 0.5f : ((float)config.defaultValue() - lower) / (upper - lower);
        this.curves.getSegments().clear();
        this.curves.getSegments().addAll(new ECBCurves(0, y, 0.1f, y, 0.9f, y, 1, y).getSegments());
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
    public Curve copy() {
        return new Curve(min, max, lower, upper, xAxis, yAxis, curves.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new CurveConfigurator("", () -> this, curves -> configurator.updateValue(this), this, true));
    }
}
