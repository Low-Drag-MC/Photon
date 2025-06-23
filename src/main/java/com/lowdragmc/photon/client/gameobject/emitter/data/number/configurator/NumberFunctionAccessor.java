package com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator;

import com.lowdragmc.lowdraglib2.configurator.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

@LDLRegisterClient(name = "number_function", registry = "ldlib2:configurator_accessor")
public class NumberFunctionAccessor extends TypesAccessor<NumberFunction> {

    public NumberFunctionAccessor() {
        super(NumberFunction.class);
    }

    @Override
    public NumberFunction defaultValue(Field field, Class<?> type) {
        return NumberFunction.constant(0);
    }

    @Override
    public Configurator create(String name, Supplier<NumberFunction> supplier, Consumer<NumberFunction> consumer, boolean forceUpdate, Field field, Object owner) {
        return new NumberFunctionConfigurator(name, supplier, consumer, forceUpdate, field.getAnnotation(NumberFunctionConfig.class));
    }
}
