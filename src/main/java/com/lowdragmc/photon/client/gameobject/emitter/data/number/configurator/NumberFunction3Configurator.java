package com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator;

import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3Config;
import lombok.Getter;
import org.appliedenergistics.yoga.YogaEdge;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaGutter;
import org.appliedenergistics.yoga.YogaWrap;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote NumberFunction3Configurator
 */
public class NumberFunction3Configurator extends ValueConfigurator<NumberFunction3> {
    @Getter
    private NumberFunction3Config config;
    private NumberFunctionConfigurator x, y, z;

    public NumberFunction3Configurator(String name, Supplier<NumberFunction3> supplier, Consumer<NumberFunction3> onUpdate, boolean forceUpdate, NumberFunction3Config config) {
        super(name, supplier, onUpdate, new NumberFunction3(
                config.xyz().length > 0 ? NumberFunction.constant(config.xyz()[0].defaultValue()) : NumberFunction.constant(config.common().defaultValue()),
                config.xyz().length > 1 ? NumberFunction.constant(config.xyz()[1].defaultValue()) : NumberFunction.constant(config.common().defaultValue()),
                config.xyz().length > 2 ? NumberFunction.constant(config.xyz()[2].defaultValue()) : NumberFunction.constant(config.common().defaultValue())),
                forceUpdate);
        this.config = config;
        if (value == null) {
            value = defaultValue;
        }

        inlineContainer.addChildren(
                x = new NumberFunctionConfigurator("x", () -> this.value.x, number -> {
                    var notifyChange = value.x != number;
                    this.value.x = number;
                    if (onUpdate != null) {
                        onUpdate.accept(value);
                    }
                    if (notifyChange) notifyChanges();
                }, true, config.xyz().length > 0 ? config.xyz()[0] : config.common()),
                y = new NumberFunctionConfigurator("y", () -> this.value.y, number -> {
                    var notifyChange = value.y != number;
                    this.value.y = number;
                    if (onUpdate != null) {
                        onUpdate.accept(value);
                    }
                    if (notifyChange) notifyChanges();
                }, true, config.xyz().length > 1 ? config.xyz()[1] : config.common()),
                z = new NumberFunctionConfigurator("z", () -> this.value.z, number -> {
                    var notifyChange = value.z != number;
                    this.value.z = number;
                    if (onUpdate != null) {
                        onUpdate.accept(value);
                    }
                    if (notifyChange) notifyChanges();
                }, true, config.xyz().length > 2 ? config.xyz()[2] : config.common())
        ).layout(layout -> {
            layout.setGap(YogaGutter.ALL, 2);
            layout.setMargin(YogaEdge.LEFT, 2);
            layout.setFlexDirection(YogaFlexDirection.ROW);
            layout.setWrap(YogaWrap.WRAP);
        });
        x.layout(layout -> {
            layout.setFlex(1);
            layout.setMinWidth(40);
        });
        y.layout(layout -> {
            layout.setFlex(1);
            layout.setMinWidth(40);
        });
        z.layout(layout -> {
            layout.setFlex(1);
            layout.setMinWidth(40);
        });
    }

    @Override
    protected void onValueUpdatePassively(NumberFunction3 newValue) {
        if (newValue == null) newValue = defaultValue;
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
    }
}
