package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote ToggleGroup
 */
public class ToggleGroup implements IConfigurable {

    @Getter
    @Setter
    @Persisted
    protected boolean enable;

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IConfigurable.super.buildConfigurator(father);
        if (!enable) {
            father.setCanCollapse(false);
            var name = father.getNameWidget();
            if (name != null) {
                name.setTextColor(ColorPattern.GRAY.color);
            }
        } else {
            father.setCanCollapse(true);
            var name = father.getNameWidget();
            if (name != null) {
                name.setTextColor(ColorPattern.WHITE.color);
            }
        }
        father.addWidget(new SwitchWidget(father.getLeftWidth() + 12, 2, 10, 10, (cd, pressed) -> {
            enable = pressed;
            if (!enable) {
                father.setCanCollapse(false);
                father.setCollapse(true);
                var name = father.getNameWidget();
                if (name != null) {
                    name.setTextColor(ColorPattern.GRAY.color);
                }
            } else {
                father.setCanCollapse(true);
                var name = father.getNameWidget();
                if (name != null) {
                    name.setTextColor(ColorPattern.WHITE.color);
                }
            }
        })
                .setPressed(enable)
                .setTexture(new ColorBorderTexture(-1, -1).setRadius(5),
                        new GuiTextureGroup(new ColorBorderTexture(-1, -1).setRadius(5),
                                new ColorRectTexture(-1).setRadius(5).scale(0.5f)))
                .setHoverTooltips("enable/disable"));
    }

}
