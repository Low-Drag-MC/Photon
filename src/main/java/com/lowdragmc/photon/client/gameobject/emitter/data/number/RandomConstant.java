package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import lombok.Setter;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote RandomConstant
 */
@LDLRegisterClient(name = "random_constant", registry = "photon:number_function")
public class RandomConstant implements NumberFunction {
    @Setter
    @Getter
    @Persisted
    private float a, b;

    public RandomConstant() {
        a = 0;
        b = 0;
    }

    public RandomConstant(Number a, Number b) {
        this.a = a.floatValue();
        this.b = b.floatValue();
    }

    public RandomConstant(NumberFunctionConfig config) {
        this(config.defaultValue(), config.defaultValue());
    }

    @Override
    public Float get(float t, Supplier<Float> lerp) {
        float min = Math.min(a, b);
        float max = Math.max(a, b);
        if (min == max) return max;
        return (min + lerp.get() * (max - min));
    }

    @Override
    public NumberFunction copy() {
        return new RandomConstant(a, b);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RandomConstant constant) {
            return a == constant.a && b == constant.b;
        }
        return super.equals(obj);
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
//        var size = group.getSize();
//        int width;
//        WidgetGroup aGroup, bGroup;
//        if (size.width > 60) {
//            width = size.width / 2;
//            aGroup = new WidgetGroup(0, 0, width, size.height);
//            bGroup = new WidgetGroup(width, 0, width, size.height);
//        } else {
//            width = size.width;
//            aGroup = new WidgetGroup(0, 0, width, size.height);
//            bGroup = new WidgetGroup(0, 15, width, size.height);
//            group.setSize(new Size(size.width, size.height + 15));
//        }
//
//        group.addWidget(aGroup);
//        group.addWidget(bGroup);
//        setupNumberConfigurator(configurator, width, aGroup, new NumberConfigurator("", () -> isDecimals ? a.floatValue() : a.intValue(), number -> {
//            setA(number);
//            configurator.updateValue(this);
//        }, a, true));
//        setupNumberConfigurator(configurator, width, bGroup, new NumberConfigurator("", () -> isDecimals ? b.floatValue() : b.intValue(), number -> {
//            setB(number);
//            configurator.updateValue(this);
//        }, b, true));

    }

//    private void setupNumberConfigurator(NumberFunctionConfigurator configurator, int width, WidgetGroup group, NumberConfigurator widget) {
//        group.addWidget(widget
//                .setRange(configurator.getConfig().min(), configurator.getConfig().max())
//                .setWheel(configurator.getConfig().isDecimals() ? configurator.getConfig().wheelDur() : Math.max(1, (int) configurator.getConfig().wheelDur())));
//        widget.setConfiguratorContainer(configurator.getConfiguratorContainer());
//        widget.init(width);
//    }

}
