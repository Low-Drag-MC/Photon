package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableUIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ColorSelector;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import lombok.Getter;
import org.appliedenergistics.yoga.*;

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
            layout.setMargin(YogaEdge.LEFT, 2.5f);
            layout.setMargin(YogaEdge.RIGHT, 2.5f);
        }).addChildren(
                alphaIndicatorContainer.layout(layout -> {
                    layout.setFlexDirection(YogaFlexDirection.ROW);
                    layout.setHeight(5);
                    layout.setWidthPercent(100);
                    layout.setAlignItems(YogaAlign.CENTER);
                }).addEventListener(UIEvents.DOUBLE_CLICK, event -> createNewIndicator(event, true)),
                new UIElement().layout(layout -> {
                    layout.setHeight(10);
                    layout.setWidthPercent(100);
                }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> new GradientColorTexture(value)))),
                rgbIndicatorContainer.layout(layout -> {
                    layout.setFlexDirection(YogaFlexDirection.ROW);
                    layout.setHeight(5);
                    layout.setWidthPercent(100);
                    layout.setAlignItems(YogaAlign.CENTER);
                }).addEventListener(UIEvents.DOUBLE_CLICK, event -> createNewIndicator(event, false))
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
                    .addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> onDragIndicator(event, true, value.getAP().indexOf(alphaP)))
                    .addEventListener(UIEvents.MOUSE_DOWN, event -> onIndicatorMouseDown(event, true, value.getAP().indexOf(alphaP))));
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
                    .addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> onDragIndicator(event, false, value.getRgbP().indexOf(rgbP)))
                    .addEventListener(UIEvents.MOUSE_DOWN, event -> onIndicatorMouseDown(event, false, value.getRgbP().indexOf(rgbP))));
        }
        refreshColorSelector();
    }

    private void createNewIndicator(UIEvent event, boolean isAlpha) {
        if (event.button == 0) {
            var percent = (event.x - gradientPreview.getPositionX()) / gradientPreview.getSizeWidth();
            percent = Math.max(0, Math.min(1, percent));
            if (isAlpha) {
                value.addAlpha(percent, value.getAlpha(percent));
                notifyListeners();
            } else {
                var rgb = value.getRGB(percent);
                value.addRGB(percent, rgb.x, rgb.y, rgb.z);
                notifyListeners();
            }
            refreshGradient();
        }
    }

    private void onDragIndicator(UIEvent event, boolean isAlpha, int point) {
        var percent = (event.x - gradientPreview.getPositionX()) / gradientPreview.getSizeWidth();
        percent = Math.max(0, Math.min(1, percent));
        var offset = percent * 100;
        event.currentElement.layout(layout -> layout.setPositionPercent(YogaEdge.LEFT, offset));
        if (isAlpha) {
            if (point >= 0 && point < value.getAP().size()) {
                value.getAP().get(point).x = percent;
                value.getAP().sort((a, b) -> Float.compare(a.x, b.x));
                notifyListeners();
            }
        } else {
            if (point >= 0 && point < value.getRgbP().size()) {
                value.getRgbP().get(point).x = percent;
                value.getRgbP().sort((a, b) -> Float.compare(a.x, b.x));
                notifyListeners();
            }
        }
    }

    private void onIndicatorMouseDown(UIEvent event, boolean isSelectAlpha, int selectedPoint) {
        if (event.button == 0) {
            this.isSelectAlpha = isSelectAlpha;
            this.selectedPoint = selectedPoint;
            refreshColorSelector();
            event.currentElement.startDrag(null, null);
        } else if (event.button == 1) {
            if (isSelectAlpha && selectedPoint >= 0 && selectedPoint < value.getAP().size() && value.getAP().size() > 1) {
                value.getAP().remove(selectedPoint);
            } else if (!isSelectAlpha && selectedPoint >= 0 && selectedPoint < value.getRgbP().size() && value.getRgbP().size() > 1) {
                value.getRgbP().remove(selectedPoint);
            }
            this.isSelectAlpha = isSelectAlpha;
            this.selectedPoint = -1;
            notifyListeners();
            refreshGradient();
        }
    }

    private void refreshColorSelector() {
        if (selectedPoint >= 0) {
            if (isSelectAlpha && selectedPoint < value.getAP().size()) {
                colorSelector.setColor(ColorUtils.color(value.getAP().get(selectedPoint).y, 1, 1, 1), false);
                colorSelector.colorPreview.setVisible(false);
                colorSelector.colorSlider.setVisible(false);
                colorSelector.hsbButton.setVisible(false);
                colorSelector.alphaSlider.setVisible(true);
            } else if (!isSelectAlpha && selectedPoint < value.getRgbP().size()) {
                var rgb = value.getRgbP().get(selectedPoint);
                colorSelector.setColor(ColorUtils.color(1, rgb.y, rgb.z, rgb.w), false);
                colorSelector.colorPreview.setVisible(true);
                colorSelector.colorSlider.setVisible(true);
                colorSelector.hsbButton.setVisible(true);
                colorSelector.alphaSlider.setVisible(false);
            } else {
                colorSelector.colorPreview.setVisible(false);
                colorSelector.colorSlider.setVisible(false);
                colorSelector.hsbButton.setVisible(false);
                colorSelector.alphaSlider.setVisible(false);
            }
        } else {
            colorSelector.colorPreview.setVisible(false);
            colorSelector.colorSlider.setVisible(false);
            colorSelector.hsbButton.setVisible(false);
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
        return this;
    }
}
