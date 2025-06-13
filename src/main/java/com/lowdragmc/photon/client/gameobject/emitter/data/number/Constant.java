package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Setter;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Constant
 */
@LDLRegisterClient(name = "constant", registry = "photon:number_function")
public class Constant implements NumberFunction {
    @Setter
    @Persisted
    private float number;

    public Constant() {
        number = 0;
    }

    public Constant(Number number) {
        this.number = number.floatValue();
    }

    public Constant(NumberFunctionConfig config) {
        this(config.isDecimals() ? config.defaultValue() : ((int) config.defaultValue()));
    }

    public Float getNumber() {
        return number;
    }

    @Override
    public Float get(RandomSource randomSource, float t) {
        return number;
    }

    @Override
    public Float get(float t, Supplier<Float> lerp) {
        return number;
    }

    @Override
    public NumberFunction copy() {
        return new Constant(number);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Constant constant) {
            return number == constant.number;
        }
        return super.equals(obj);
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        // TODO Configurator
//        var widget = new NumberConfigurator("", () -> configurator.getConfig().isDecimals() ? number.floatValue() : number.intValue(), number -> {
//            setNumber(number);
//            configurator.updateValue(this);
//        }, number, true);
//        group.addWidget(widget);
//        widget.setRange(configurator.getConfig().min(), configurator.getConfig().max());
//        widget.setWheel(configurator.getConfig().isDecimals() ? configurator.getConfig().wheelDur() : Math.max(1, (int) configurator.getConfig().wheelDur()));
//        widget.setConfiguratorContainer(configurator.getConfiguratorContainer());
//        widget.init(group.getSize().width);
    }
}
