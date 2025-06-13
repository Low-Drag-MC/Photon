package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.configurator.NumberFunctionConfigurator;

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

    public Color(NumberFunctionConfig config) {
        super(config);
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        // TODO configurator
//        var widget = new ColorConfigurator("", () -> getNumber().intValue(), number -> {
//            setNumber(number);
//            configurator.updateValue(this);;
//        }, getNumber().intValue(), true);
//        widget.setConfiguratorContainer(configurator.getConfiguratorContainer());
//        widget.init(group.getSize().width);
//        group.addWidget(widget);
    }

    @Override
    public NumberFunction copy() {
        return new Color(getNumber().intValue());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Color color) {
            return color.getNumber() == getNumber();
        }
        return super.equals(obj);
    }
}
