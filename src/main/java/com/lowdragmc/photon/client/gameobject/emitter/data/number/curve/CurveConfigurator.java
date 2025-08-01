package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import net.minecraft.util.Mth;
import org.appliedenergistics.yoga.YogaDisplay;
import org.appliedenergistics.yoga.YogaEdge;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaPositionType;
import org.jetbrains.annotations.NotNull;
import oshi.hardware.Display;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CurveConfigurator extends ValueConfigurator<Curve> {
    public final UIElement boundContainer = new UIElement();
    public final TextField upperBound = new TextField();
    public final TextField lowerBound = new TextField();
    public final UIElement dialog = new UIElement();
    public final CurveGraph curveGraph = new CurveGraph();
    public final UIElement curvePreview = new UIElement();

    public CurveConfigurator(String name, Supplier<Curve> supplier, Consumer<Curve> onUpdate, @Nonnull Curve defaultValue, boolean forceUpdate) {
        super(name, supplier, onUpdate, defaultValue, forceUpdate);
        if (value == null) {
            value = defaultValue;
        }

        upperBound.setNumbersOnlyFloat(value.getMin(), value.getMax());
        upperBound.setText(value.getUpper() + "");
        upperBound.setTextResponder(text -> {
            value.setUpper(Mth.clamp(Float.parseFloat(text), value.getMin(), value.getMax()));
            updateValue();
        });
        upperBound.layout(layout -> {
            layout.setWidthPercent(100);
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setPosition(YogaEdge.TOP, 0);
        });
        lowerBound.setNumbersOnlyFloat(value.getMin(), value.getMax());
        lowerBound.setText(value.getLower() + "");
        lowerBound.setTextResponder(text -> {
            value.setLower(Mth.clamp(Float.parseFloat(text), value.getMin(), value.getMax()));
            updateValue();
        });
        lowerBound.layout(layout -> {
            layout.setWidthPercent(100);
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setPosition(YogaEdge.BOTTOM, 0);
        });

        this.curveGraph.setOnCurveChangeListener(curves -> updateValue());

        inlineContainer.addChildren(curvePreview.layout(layout -> {
            layout.setHeight(14);
            layout.setPadding(YogaEdge.ALL, 3);
        }).style(style -> style.backgroundTexture(Sprites.RECT_RD_SOLID))
                .addChildren(new UIElement()
                        .layout(layout -> layout.setHeightPercent(100))
                        .style(style -> style.backgroundTexture(DynamicTexture.of(() -> new CurveTexture(value.getCurves()))))
                        .addEventListener(UIEvents.MOUSE_DOWN, this::onClick)));

        this.curveGraph.setValue(value.getCurves(), false);

        this.dialog.style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER));
        this.dialog.layout(layout -> {
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setWidthPercent(100);
            layout.setHeight(100);
            layout.setPadding(YogaEdge.ALL, 4);
            layout.setFlexDirection(YogaFlexDirection.ROW);
        }).addChildren(boundContainer.layout(layout -> {
            layout.setHeightPercent(100);
            layout.setWidth(40);
            layout.setMargin(YogaEdge.RIGHT, 2);
        }).addChildren(upperBound, lowerBound), curveGraph.layout(layout -> {
            layout.setHeightPercent(100);
            layout.setFlex(1);
        }));
        this.dialog.setFocusable(true);
        this.dialog.setEnforceFocus(e -> hide());
        this.dialog.addEventListener(UIEvents.LAYOUT_CHANGED, e -> dialog.adaptPositionToScreen());
    }

    @Override
    protected void onDropObject(@NotNull Object object) {
        if (object instanceof CurveResource.Curves curves) {
            if (value == null) return;
            value.getCurves().deserializeNBT(Platform.getFrozenRegistry(), curves.curves0.serializeNBT(Platform.getFrozenRegistry()));
            this.curveGraph.setValue(value.getCurves(), false);
            updateValue();
        } else {
            super.onDropObject(object);
        }
    }

    @Override
    protected boolean canDropObject(@Nonnull Object object) {
        return object instanceof CurveResource.Curves || super.canDropObject(object);
    }

    public CurveConfigurator disableBoundField() {
        boundContainer.setDisplay(YogaDisplay.NONE);
        return this;
    }

    @Override
    protected void onValueUpdatePassively(Curve newValue) {
        if (newValue == null) newValue = defaultValue;
        if (newValue == value || newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
        this.curveGraph.setValue(newValue.getCurves(), false);
    }

    public void show() {
        var parent = this.dialog.getParent();
        if (parent != null) {
            return;
        }
        var mui = getModularUI();
        if (mui != null) {
            var root = mui.ui.rootElement;
            root.addChild(dialog.layout(layout -> {
                var x = curvePreview.getPositionX();
                var y = curvePreview.getPositionY();
                layout.setPosition(YogaEdge.LEFT, x - root.getLayoutX());
                layout.setPosition(YogaEdge.TOP, y - root.getLayoutY());
                layout.setWidth(Math.max(curvePreview.getSizeWidth(), 300));
            }));
            this.dialog.focus();
        }
    }

    public void hide() {
        var parent = this.dialog.getParent();
        if (parent != null) {
            this.dialog.blur();
            parent.removeChild(this.dialog);
        }
    }

    protected void onClick(UIEvent event) {
        if (this.dialog.getParent() != null) {
            hide();
        } else {
            show();
        }
    }

}
