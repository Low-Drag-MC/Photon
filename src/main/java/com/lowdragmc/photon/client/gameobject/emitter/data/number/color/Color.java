package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.configurator.ui.ColorConfigurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;

import java.util.Objects;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote Color
 */
@LDLRegisterClient(name = "color", registry = "photon:number_function")
public class Color extends Constant {

    public Color() {
        super(-1);
    }

    public Color(int number) {
        super(number);
    }

    public void loadConfig(NumberFunctionConfig config) {
        setNumber((int) config.defaultValue());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new ColorConfigurator("", () -> getNumber().intValue(), color -> {
            setNumber(color);
            configurator.updateValue(this);
            }, getNumber().intValue(), true));
    }

    @Override
    public NumberFunction copy() {
        return new Color(getNumber().intValue());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;
        if (obj instanceof Color color) {
            return Objects.equals(color.getNumber(), getNumber());
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNumber());
    }
}
