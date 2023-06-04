package com.lowdragmc.photon.gui.editor.configurator;

import com.lowdragmc.lowdraglib.gui.editor.configurator.ValueConfigurator;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunction3;
import com.lowdragmc.photon.client.data.number.NumberFunction3Config;
import lombok.Getter;

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
    }

    @Override
    protected void onValueUpdate(NumberFunction3 newValue) {
        if (newValue == null) newValue = defaultValue;
        if (newValue.equals(value)) return;
        super.onValueUpdate(newValue);
    }

    @Override
    public void init(int width) {
        super.init(width);
        assert value != null;
        var w = (width - leftWidth - rightWidth) / 3;
        x = new NumberFunctionConfigurator("x", () -> this.value.x, number -> {
            this.value.x = number;
            updateValue();
        }, forceUpdate, config.xyz().length > 0 ? config.xyz()[0] : config.common());
        x.setConfigPanel(configPanel, tab);
        x.init(w);
        x.addSelfPosition(leftWidth, 0);
        addWidget(x);

        y = new NumberFunctionConfigurator("y", () -> this.value.y, number -> {
            this.value.y = number;
            updateValue();
        }, forceUpdate, config.xyz().length > 1 ? config.xyz()[1] : config.common());
        y.setConfigPanel(configPanel, tab);
        y.init(w);
        y.addSelfPosition(leftWidth + w, 0);
        addWidget(y);

        z = new NumberFunctionConfigurator("z", () -> this.value.z, number -> {
            this.value.z = number;
            updateValue();
        }, forceUpdate, config.xyz().length > 2 ? config.xyz()[2] : config.common());
        z.setConfigPanel(configPanel, tab);
        z.init(w);
        z.addSelfPosition(leftWidth + w * 2, 0);
        addWidget(z);
    }

    @Override
    public void computeHeight() {
        super.computeHeight();
        var size = getSize();
        x.computeHeight();
        y.computeHeight();
        z.computeHeight();
        setSize(new Size(size.width, Math.max(15, Math.max(x.getSize().height, Math.max(y.getSize().height, z.getSize().height)))));
    }
}
