package com.lowdragmc.photon.gui.editor.configurator;

import com.lowdragmc.lowdraglib.gui.editor.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigAccessor;
import com.lowdragmc.lowdraglib.gui.editor.configurator.Configurator;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2022/12/1
 * @implNote NumberFunctionAccessor
 */
@ConfigAccessor
public class NumberFunctionAccessor extends TypesAccessor<NumberFunction> {

    public NumberFunctionAccessor() {
        super(NumberFunction.class);
    }

    @Override
    public NumberFunction defaultValue(Field field, Class<?> type) {
        return NumberFunction.constant(0);
    }

    @Override
    public Configurator create(String name, Supplier<NumberFunction> supplier, Consumer<NumberFunction> consumer, boolean forceUpdate, Field field) {
        return new NumberFunctionConfigurator(name, supplier, consumer, forceUpdate, field.getAnnotation(NumberFunctionConfig.class));
    }
}
