package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.HDRColorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableUIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ColorSelector;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class GradientColorSelector extends BindableUIElement<GradientColor> {
    public final UIElement gradientPreview = new UIElement();
    public final UIElement alphaIndicatorContainer = new UIElement();
    public final UIElement rgbIndicatorContainer = new UIElement();
    public final ColorSelector colorSelector = new ColorSelector();
    /**
     * HDR mode. The rgb stops then hold <b>premultiplied</b> values that may exceed 1, and the picker
     * gains an intensity field. Storage is unchanged — see {@link #selectedIntensity} for the
     * canonicalisation contract.
     */
    @Getter
    protected final boolean hdr;
    /** HDR mode only: the selected rgb stop's intensity. */
    @Nullable
    public final NumberConfigurator intensityConfigurator;
    @Getter
    protected GradientColor value = new GradientColor();

    // runtime
    private boolean isSelectAlpha = false;
    private int selectedPoint = -1;
    /**
     * The intensity of the currently selected rgb stop, split out of its premultiplied value by
     * {@link HDRColor#fromPremultiplied}. Held here because the split isn't recoverable once the
     * picker rounds the base color to 8 bits — editing the color keeps this multiplier.
     */
    private float selectedIntensity = 1f;

    public GradientColorSelector() {
        this(false);
    }

    public GradientColorSelector(boolean hdr) {
        this.hdr = hdr;
        this.intensityConfigurator = hdr ? new NumberConfigurator(HDRColorConfigurator.INTENSITY_LABEL, () -> selectedIntensity,
                intensity -> onIntensityChanged(intensity.floatValue()), 1f, true) : null;
        if (this.intensityConfigurator != null) {
            this.intensityConfigurator.setType(ConfigNumber.Type.FLOAT).setRange(0, Float.MAX_VALUE);
        }
        getLayout().gapAll(1);
        gradientPreview.layout(layout -> {
            layout.marginLeft(2.5f);
            layout.marginRight(2.5f);
        }).addChildren(
                alphaIndicatorContainer.layout(layout -> {
                    layout.flexDirection(FlexDirection.ROW);
                    layout.height(5);
                    layout.widthPercent(100);
                    layout.alignItems(AlignItems.CENTER);
                }).addEventListener(UIEvents.DOUBLE_CLICK, event -> createNewIndicator(event, true)),
                new UIElement().layout(layout -> {
                    layout.height(10);
                    layout.widthPercent(100);
                }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> new GradientColorTexture(value)))),
                rgbIndicatorContainer.layout(layout -> {
                    layout.flexDirection(FlexDirection.ROW);
                    layout.height(5);
                    layout.widthPercent(100);
                    layout.alignItems(AlignItems.CENTER);
                }).addEventListener(UIEvents.DOUBLE_CLICK, event -> createNewIndicator(event, false))
        );
        colorSelector.setOnColorChangeListener(this::onColorChanged);
        refreshGradient();
        addChildren(gradientPreview, colorSelector);
        if (intensityConfigurator != null) {
            addChildren(intensityConfigurator);
        }
    }

    private void onColorChanged(int color) {
        if (selectedPoint >= 0) {
            if (isSelectAlpha && selectedPoint < value.getAP().size()) {
                value.getAP().get(selectedPoint).y = ColorUtils.alpha(color);
                notifyListeners();
            } else if (!isSelectAlpha && selectedPoint < value.getRgbP().size()) {
                writeSelectedRGB(color);
            }
        }
    }

    private void onIntensityChanged(float intensity) {
        if (!hdr || isSelectAlpha || selectedPoint < 0 || selectedPoint >= value.getRgbP().size()) return;
        selectedIntensity = Math.max(0, intensity);
        writeSelectedRGB(colorSelector.getColor());
    }

    /** Write the selected rgb stop from a picker color, premultiplying by the intensity in HDR mode. */
    private void writeSelectedRGB(int color) {
        var rgbP = value.getRgbP().get(selectedPoint);
        var scale = hdr ? selectedIntensity : 1f;
        rgbP.y = ColorUtils.red(color) * scale;
        rgbP.z = ColorUtils.green(color) * scale;
        rgbP.w = ColorUtils.blue(color) * scale;
        notifyListeners();
    }

    private void refreshGradient() {
        alphaIndicatorContainer.clearAllChildren();
        rgbIndicatorContainer.clearAllChildren();
        for (var alphaP : value.getAP()) {
            alphaIndicatorContainer.addChild(new UIElement().layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.leftPercent(alphaP.x * 100);
                layout.marginLeft(-2.5f);
                layout.width(5);
                layout.height(5);
            }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> (isSelectAlpha && selectedPoint == value.getAP().indexOf(alphaP)) ?
                    Icons.DOWN_ARROW_NO_BAR_S : Icons.DOWN_ARROW_NO_BAR_S_WHITE)))
                    .addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> onDragIndicator(event, true, value.getAP().indexOf(alphaP)))
                    .addEventListener(UIEvents.MOUSE_DOWN, event -> onIndicatorMouseDown(event, true, value.getAP().indexOf(alphaP))));
        }
        for (var rgbP : value.getRgbP()) {
            rgbIndicatorContainer.addChild(new UIElement().layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.leftPercent(rgbP.x * 100);
                layout.marginLeft(-2.5f);
                layout.width(5);
                layout.height(5);
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
        event.currentElement.layout(layout -> layout.leftPercent(offset));
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
        var rgbSelected = false;
        if (selectedPoint >= 0) {
            if (isSelectAlpha && selectedPoint < value.getAP().size()) {
                colorSelector.setColor(ColorUtils.color(value.getAP().get(selectedPoint).y, 1, 1, 1), false);
                colorSelector.colorPreview.setVisible(false);
                colorSelector.colorSlider.setVisible(false);
                colorSelector.hsbButton.setVisible(false);
                colorSelector.alphaSlider.setVisible(true);
            } else if (!isSelectAlpha && selectedPoint < value.getRgbP().size()) {
                rgbSelected = true;
                var rgb = value.getRgbP().get(selectedPoint);
                if (hdr) {
                    // split the stored premultiplied value back into a pickable base color + intensity
                    var split = HDRColor.fromPremultiplied(rgb.y, rgb.z, rgb.w, 1f);
                    selectedIntensity = split.getIntensity();
                    colorSelector.setColor(split.baseARGB(), false);
                } else {
                    colorSelector.setColor(ColorUtils.color(1, rgb.y, rgb.z, rgb.w), false);
                }
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
        // intensity only applies to an rgb stop — alpha stops have no HDR range. setDisplay, not
        // setVisible: the latter only skips drawing, which would leave a dead row in the popup.
        if (intensityConfigurator != null) {
            intensityConfigurator.setDisplay(rgbSelected);
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
