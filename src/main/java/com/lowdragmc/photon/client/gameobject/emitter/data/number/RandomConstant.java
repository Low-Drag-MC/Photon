package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import dev.vfyjxf.taffy.style.FlexWrap;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import dev.vfyjxf.taffy.style.FlexDirection;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote RandomConstant
 */
@LDLRegisterClient(name = "random_constant", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class RandomConstant implements NumberFunction {
    @Setter
    @Getter
    @Persisted
    private Number a, b;

    public RandomConstant() {
        a = 0;
        b = 0;
    }

    public RandomConstant(Number a, Number b) {
        this.a = a;
        this.b = b;
    }

    public void loadConfig(NumberFunctionConfig config) {
        a = switch (config.numberType()) {
            case INTEGER -> (int) config.defaultValue();
            case FLOAT -> (float) config.defaultValue();
            case LONG -> (long) config.defaultValue();
            case SHORT -> (short) config.defaultValue();
            case BYTE -> (byte) config.defaultValue();
            default -> config.defaultValue();
        };
        b = switch (config.numberType()) {
            case INTEGER -> (int) config.defaultValue();
            case FLOAT -> (float) config.defaultValue();
            case LONG -> (long) config.defaultValue();
            case SHORT -> (short) config.defaultValue();
            case BYTE -> (byte) config.defaultValue();
            default -> config.defaultValue();
        };
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        var min = Math.min(a.doubleValue(), b.doubleValue());
        var max = Math.max(a.doubleValue(), b.doubleValue());
        if (min == max) return max;
        return (min + lerp.get() * (max - min));
    }

    @Override
    public NumberFunction copy() {
        return new RandomConstant(a, b);
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        NumberConfigurator x, y;

        configurator.inlineContainer.addChildren(
                x = new NumberConfigurator("a", () -> a,
                        value -> {
                            setA(value.floatValue());
                            configurator.updateValue(this);
                        },
                        a, true)
                        .setRange(configurator.getConfig().min(), configurator.getConfig().max())
                        .setWheel(configurator.getConfig().wheelDur())
                        .setType(configurator.getConfig().numberType()),
                y = new NumberConfigurator("b", () -> b,
                        value -> {
                            setB(value.floatValue());
                            configurator.updateValue(this);
                        },
                        b, true)
                        .setRange(configurator.getConfig().min(), configurator.getConfig().max())
                        .setWheel(configurator.getConfig().wheelDur())
                        .setType(configurator.getConfig().numberType())
        ).layout(layout -> {
            layout.gapAll(2);
            layout.marginLeft(2);
            layout.flexDirection(FlexDirection.ROW);
            layout.wrap(FlexWrap.WRAP);
        });
        x.layout(layout -> {
            layout.flex(1);
            layout.minWidth(40);
            layout.height(14);
        });
        y.layout(layout -> {
            layout.flex(1);
            layout.minWidth(40);
            layout.height(14);
        });
    }

}
