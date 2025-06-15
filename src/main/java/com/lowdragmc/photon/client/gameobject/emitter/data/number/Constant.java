package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.gui.configurator.NumberFunctionConfigurator;
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
    private Number number;

    public Constant() {
        number = 0;
    }

    public Constant(Number number) {
        this.number = number.floatValue();
    }

    public Constant(NumberFunctionConfig config) {
        this(config.defaultValue());
    }

    public Number getNumber() {
        return number;
    }

    @Override
    public Number get(RandomSource randomSource, float t) {
        return number;
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
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
        configurator.inlineContainer.addChildren(new NumberConfigurator("", () -> number, value -> {
            setNumber(value.floatValue());
            configurator.updateValue(this);
            }, number, true)
                .setRange(configurator.getConfig().min(), configurator.getConfig().max())
                .setWheel(configurator.getConfig().wheelDur())
                .setType(configurator.getConfig().numberType()));
    }
}
