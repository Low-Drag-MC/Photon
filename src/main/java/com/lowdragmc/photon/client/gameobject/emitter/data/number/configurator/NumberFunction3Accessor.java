package com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator;

import com.lowdragmc.lowdraglib2.configurator.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3Config;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@LDLRegisterClient(name = "number_function3", registry = "ldlib2:configurator_accessor")
public class NumberFunction3Accessor extends TypesAccessor<NumberFunction3> {

    public NumberFunction3Accessor() {
        super(NumberFunction3.class);
    }

    @Override
    public NumberFunction3 defaultValue(Field field, Class<?> type) {
        return new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0));
    }

    @Override
    public Configurator create(String name, Supplier<NumberFunction3> supplier, Consumer<NumberFunction3> consumer, boolean forceUpdate, Field field, Object owner) {
        var config = field.getAnnotation(NumberFunction3Config.class);
        var value = supplier.get();
        Consumer<NumberFunction> singleConsumer = number -> {
            var previous = supplier.get();
            consumer.accept(new NumberFunction3(
                    config.affectX() ? (previous.x == number ? number : NumberFunction.copy(number)) : previous.x,
                    config.affectY() ? (previous.y == number ? number : NumberFunction.copy(number)) : previous.y,
                    config.affectZ() ? (previous.z == number ? number : NumberFunction.copy(number)) : previous.z));
        };
        Supplier<NumberFunction> singleSupplier = () -> {
            if (config.affectX()) return supplier.get().x;
            if (config.affectY()) return supplier.get().y;
            return supplier.get().z;
        };
        if (config.allowSeperated()) {
            AtomicBoolean isSeperated = new AtomicBoolean(config.isSeperatedDefault());
            if (!NumberFunction.isEqual(value.x, value.y) || !NumberFunction.isEqual(value.y, value.z)) {
                isSeperated.set(true);
            }
            return new ConfiguratorSelectorConfigurator<>(name, isSeperated::get, isSeperated::set, isSeperated.get(), true,
                    List.of(true, false), v -> v ? "photon.separated axes" : "photon.all_in_one", (v, father) -> {
                if (v) {
                    father.addConfigurators(new NumberFunction3Configurator("", supplier, consumer, forceUpdate, config));
                } else {
                    singleConsumer.accept(singleSupplier.get());
                    father.addConfigurators(new NumberFunctionConfigurator("", singleSupplier, singleConsumer, forceUpdate, config.common()));
                }
            });
        } else {
            if (config.isSeperatedDefault()) {
                return new NumberFunction3Configurator(name, supplier, consumer, forceUpdate, config);
            } else {
                return new NumberFunctionConfigurator(name, singleSupplier, singleConsumer, forceUpdate, config.common());
            }
        }
    }
}
