package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.editor_outdated.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.ui.BindableUIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ColorSelector;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import lombok.Getter;
import org.appliedenergistics.yoga.*;
import org.joml.Vector2f;

import java.util.function.Consumer;

public class GradientColorSelector extends BindableUIElement<GradientColor> {
    public final UIElement gradientPreview = new UIElement();
    public final UIElement alphaIndicatorContainer = new UIElement();
    public final UIElement rgbIndicatorContainer = new UIElement();
    public final ColorSelector colorSelector = new ColorSelector();
    @Getter
    protected GradientColor value = new GradientColor();

    // runtime
    private boolean isSelectAlpha = false;
    private int selectedPoint = -1;

    public GradientColorSelector() {
        getLayout().setGap(YogaGutter.ALL, 1);
        gradientPreview.layout(layout -> {
            layout.setWidthPercent(100);
        }).addChildren(
                alphaIndicatorContainer.layout(layout -> {
                    layout.setFlexDirection(YogaFlexDirection.ROW);
                    layout.setHeight(5);
                    layout.setWidthPercent(100);
                    layout.setAlignItems(YogaAlign.CENTER);
                }),
                new UIElement().layout(layout -> {
                    layout.setHeight(10);
                    layout.setWidthPercent(100);
                }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> new GradientColorTexture(value)))),
                rgbIndicatorContainer.layout(layout -> {
                    layout.setFlexDirection(YogaFlexDirection.ROW);
                    layout.setHeight(5);
                    layout.setWidthPercent(100);
                    layout.setAlignItems(YogaAlign.CENTER);
                })
        );
        colorSelector.setOnColorChangeListener(this::onColorChanged);
        refreshGradient();
        addChildren(gradientPreview, colorSelector);
    }

    private void onColorChanged(int color) {
        if (selectedPoint >= 0) {
            if (isSelectAlpha && selectedPoint < value.getAP().size()) {
                value.getAP().get(selectedPoint).y = ColorUtils.alpha(color);
                notifyListeners();
            } else if (!isSelectAlpha && selectedPoint < value.getRgbP().size()) {
                var rgbP = value.getRgbP().get(selectedPoint);
                rgbP.y = ColorUtils.red(color);
                rgbP.z = ColorUtils.green(color);
                rgbP.w = ColorUtils.blue(color);
                notifyListeners();
            }
        }
    }

    private void refreshGradient() {
        alphaIndicatorContainer.clearAllChildren();
        rgbIndicatorContainer.clearAllChildren();
        for (var alphaP : value.getAP()) {
            alphaIndicatorContainer.addChild(new UIElement().layout(layout -> {
                layout.setPositionType(YogaPositionType.ABSOLUTE);
                layout.setPositionPercent(YogaEdge.LEFT, alphaP.x * 100);
                layout.setMargin(YogaEdge.LEFT, -2.5f);
                layout.setWidth(5);
                layout.setHeight(5);
            }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> (isSelectAlpha && selectedPoint == value.getAP().indexOf(alphaP)) ?
                    Icons.DOWN_ARROW_NO_BAR_S : Icons.DOWN_ARROW_NO_BAR_S_WHITE)))
                    .addEventListener(UIEvents.MOUSE_DOWN, event -> setSelected(true, value.getAP().indexOf(alphaP))));
        }
        for (var rgbP : value.getRgbP()) {
            rgbIndicatorContainer.addChild(new UIElement().layout(layout -> {
                layout.setPositionType(YogaPositionType.ABSOLUTE);
                layout.setPositionPercent(YogaEdge.LEFT, rgbP.x * 100);
                layout.setMargin(YogaEdge.LEFT, -2.5f);
                layout.setWidth(5);
                layout.setHeight(5);
            }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> (!isSelectAlpha && selectedPoint == value.getRgbP().indexOf(rgbP)) ?
                    Icons.UP_ARROW_NO_BAR_S : Icons.UP_ARROW_NO_BAR_S_WHITE)))
                    .addEventListener(UIEvents.MOUSE_DOWN, event -> setSelected(false, value.getRgbP().indexOf(rgbP))));
        }
        refreshColorSelector();
    }

    private void setSelected(boolean isSelectAlpha, int selectedPoint) {
        this.isSelectAlpha = isSelectAlpha;
        this.selectedPoint = selectedPoint;
        refreshColorSelector();
    }

    private void refreshColorSelector() {
        if (selectedPoint >= 0) {
            if (isSelectAlpha && selectedPoint < value.getAP().size()) {
                colorSelector.setColor(ColorUtils.color(value.getAP().get(selectedPoint).y, 1, 1, 1), false);
                colorSelector.colorPreview.setVisible(false);
                colorSelector.colorSlider.setVisible(false);
                colorSelector.alphaSlider.setVisible(true);
            } else if (!isSelectAlpha && selectedPoint < value.getRgbP().size()) {
                var rgb = value.getRgbP().get(selectedPoint);
                colorSelector.setColor(ColorUtils.color(1, rgb.y, rgb.z, rgb.w), false);
                colorSelector.colorPreview.setVisible(true);
                colorSelector.colorSlider.setVisible(true);
                colorSelector.alphaSlider.setVisible(false);
            } else {
                colorSelector.colorPreview.setVisible(false);
                colorSelector.colorSlider.setVisible(false);
                colorSelector.alphaSlider.setVisible(false);
            }
        } else {
            colorSelector.colorPreview.setVisible(false);
            colorSelector.colorSlider.setVisible(false);
            colorSelector.alphaSlider.setVisible(false);
        }
    }

    public GradientColorSelector setOnColorGradientChangeListener(Consumer<GradientColor> listener) {
        registerValueListener(listener);
        return this;
    }

    @Override
    public GradientColorSelector setValue(GradientColor value, boolean notify) {
        if (this.value == value) return this;
        this.value = value;
        if (notify) {
            notifyListeners();
        }
        refreshGradient();
        return null;
    }
}
