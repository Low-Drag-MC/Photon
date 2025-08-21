package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.appliedenergistics.yoga.YogaEdge;

import java.util.function.Supplier;

@Accessors(chain = true, fluent = true)
public class SceneToggleBuilder {
    private final Supplier<Boolean> valueGetter;
    private final BooleanConsumer valueSetter;
    private IGuiTexture enabledIcon;
    private IGuiTexture disabledIcon;
    @Setter
    private String tooltipKey;

    @Setter
    private IGuiTexture baseTexture = Sprites.BORDER1_RT1_DARK;
    @Setter
    private IGuiTexture hoverTexture = Sprites.BORDER1_RT1;
    @Setter
    private float iconScale = 0.6f;
    private int disabledColor = ColorPattern.GRAY.color;
    
    public SceneToggleBuilder(Supplier<Boolean> valueGetter, BooleanConsumer valueSetter) {
        this.valueGetter = valueGetter;
        this.valueSetter = valueSetter;
    }
    
    public SceneToggleBuilder icon(IGuiTexture enabledIcon, IGuiTexture disabledIcon) {
        this.enabledIcon = enabledIcon;
        this.disabledIcon = disabledIcon;
        return this;
    }
    
    public SceneToggleBuilder icon(IGuiTexture icon) {
        this.enabledIcon = icon;
        this.disabledIcon = icon.copy().setColor(disabledColor);
        return this;
    }
    
    public SceneToggleBuilder disabledColor(int color) {
        this.disabledColor = color;
        // 更新禁用图标颜色
        if (this.enabledIcon != null && this.disabledIcon == null) {
            this.disabledIcon = this.enabledIcon.copy().setColor(color);
        }
        return this;
    }
    

    public Toggle build() {
        if (enabledIcon == null || disabledIcon == null) {
            throw new IllegalStateException("require an icon");
        }
        
        return (Toggle) new Toggle()
            .setText("")
            .setOn(valueGetter.get(), false)
            .toggleButton(button -> button.layout(layout -> {
                layout.setWidthPercent(100);
                layout.setHeightPercent(100);
            }))
            .setOnToggleChanged(valueSetter)
            .toggleStyle(style -> {
                style.baseTexture(baseTexture);
                style.hoverTexture(hoverTexture);
                style.unmarkTexture(disabledIcon.copy().scale(iconScale));
                style.markTexture(enabledIcon.copy().scale(iconScale));
            })
            .layout(layout -> {
                layout.setPadding(YogaEdge.ALL, 0);
                layout.setHeightPercent(100);
                layout.setAspectRatio(1f);
            })
            .addEventListener(UIEvents.TICK, event -> {
                if (event.currentElement instanceof Toggle toggle) {
                    if (toggle.getValue() != valueGetter.get()) {
                        toggle.setValue(valueGetter.get(), false);
                    }
                }
            })
            .style(style -> {
                if (tooltipKey != null) {
                    style.tooltips(tooltipKey);
                }
            });
    }
}
