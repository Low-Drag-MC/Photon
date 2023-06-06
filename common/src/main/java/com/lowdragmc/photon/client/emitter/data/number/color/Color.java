package com.lowdragmc.photon.client.emitter.data.number.color;

import com.lowdragmc.lowdraglib.gui.editor.configurator.ColorConfigurator;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote Color
 */
public class Color extends Constant {

    public Color() {
        super(-1);
    }

    public Color(Number number) {
        super(number);
    }

    public Color(NumberFunctionConfig config) {
        super(config);
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var widget = new ColorConfigurator("", () -> getNumber().intValue(), number -> {
            setNumber(number);
            configurator.updateValue();;
        }, getNumber().intValue(), true);
        widget.setConfigPanel(configurator.getConfigPanel(), configurator.getTab());
        widget.init(group.getSize().width);
        group.addWidget(widget);
    }
}
