package com.lowdragmc.photon.gui.editor.configurator;

import com.lowdragmc.lowdraglib.gui.editor.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigAccessor;
import com.lowdragmc.lowdraglib.gui.editor.configurator.Configurator;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction3Config;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2022/12/1
 * @implNote NumberFunctionAccessor
 */
@ConfigAccessor
public class NumberFunction3Accessor extends TypesAccessor<NumberFunction3> {

    public NumberFunction3Accessor() {
        super(NumberFunction3.class);
    }

    @Override
    public NumberFunction3 defaultValue(Field field, Class<?> type) {
        return new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0));
    }

    @Override
    public Configurator create(String name, Supplier<NumberFunction3> supplier, Consumer<NumberFunction3> consumer, boolean forceUpdate, Field field) {
        return new NumberFunction3Configurator(name, supplier, consumer, forceUpdate, field.getAnnotation(NumberFunction3Config.class));
    }
}
