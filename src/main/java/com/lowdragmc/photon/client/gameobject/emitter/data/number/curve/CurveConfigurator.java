package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import com.lowdragmc.photon.gui.editor.resource.ResourceDialogs;
import org.jetbrains.annotations.Nullable;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

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
    // keep the floating editor open while a resource load/save dialog is on top
    protected boolean keepOpen = false;

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
            layout.widthPercent(100);
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.top( 0);
        });
        lowerBound.setNumbersOnlyFloat(value.getMin(), value.getMax());
        lowerBound.setText(value.getLower() + "");
        lowerBound.setTextResponder(text -> {
            value.setLower(Mth.clamp(Float.parseFloat(text), value.getMin(), value.getMax()));
            updateValue();
        });
        lowerBound.layout(layout -> {
            layout.widthPercent(100);
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.bottom( 0);
        });

        this.curveGraph.setOnCurveChangeListener(curves -> updateValue());
        // the drag readout shows the mapped value (lower..upper) instead of the normalized 0..1
        this.curveGraph.setCoordFormatter((x, y) ->
                "(%.2f, %.2f)".formatted(x, value.getLower() + (value.getUpper() - value.getLower()) * y));

        inlineContainer.addChildren(curvePreview.layout(layout -> {
            layout.height(14);
            layout.paddingAll(3);
        }).style(style -> style.backgroundTexture(Sprites.RECT_RD_SOLID))
                .moveInlineAsDefault()
                .addClass("configurator_preview_bg")
                .addChildren(new UIElement()
                        .layout(layout -> layout.heightPercent(100))
                        .style(style -> style.backgroundTexture(DynamicTexture.of(() -> new CurveTexture(value.getCurves()))))
                        .addEventListener(UIEvents.MOUSE_DOWN, this::onClick)));

        this.curveGraph.setValue(value.getCurves(), false);

        this.dialog.style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER));
        this.dialog.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.widthPercent(100);
            layout.height(100);
            layout.paddingAll(4);
            layout.flexDirection(FlexDirection.ROW);
        }).addChildren(boundContainer.layout(layout -> {
            layout.heightPercent(100);
            layout.width(40);
            layout.marginRight(2);
        }).addChildren(upperBound, lowerBound), curveGraph.layout(layout -> {
            layout.heightPercent(100);
            layout.flex(1);
        }), createResourceButtons());
        this.dialog.setFocusable(true);
        this.dialog.setEnforceFocus(e -> {
            if (!keepOpen) hide();
        });
        this.dialog.addEventListener(UIEvents.LAYOUT_CHANGED, e -> dialog.adaptPositionToScreen());
        this.dialog.moveInlineAsDefault().addClass("panel_bg");
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

    protected UIElement createResourceButtons() {
        return new UIElement().layout(layout -> {
            layout.heightPercent(100);
            layout.width(14);
            layout.marginLeft(2);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(2);
        }).addChildren(
                ResourceDialogs.iconButton(Icons.IMPORT, "photon.resource.load_from_resource", this::onLoadFromResource)
                        .layout(layout -> layout.widthPercent(100)),
                ResourceDialogs.iconButton(Icons.SAVE, "photon.resource.save_to_resource", this::onSaveToResource)
                        .layout(layout -> layout.widthPercent(100)));
    }

    protected void onLoadFromResource(UIEvent event) {
        keepOpen = true;
        holdOpen(ResourceDialogs.showLoadDialog(CurveResource.INSTANCE, getModularUI(), event.x, event.y, curves -> {
            if (value == null) return;
            value.getCurves().deserializeNBT(Platform.getFrozenRegistry(), curves.curves0.serializeNBT(Platform.getFrozenRegistry()));
            this.curveGraph.setValue(value.getCurves(), false);
            updateValue();
        }));
    }

    protected void onSaveToResource(UIEvent event) {
        keepOpen = true;
        holdOpen(ResourceDialogs.showSaveDialog(CurveResource.INSTANCE, getModularUI(), event.x, event.y,
                () -> new CurveResource.Curves(curveGraph.getValue().copy())));
    }

    private void holdOpen(@Nullable Dialog sub) {
        if (sub == null) {
            keepOpen = false;
            return;
        }
        sub.setOnClose(() -> {
            keepOpen = false;
            this.dialog.focus();
        });
    }

    public CurveConfigurator disableBoundField() {
        boundContainer.setDisplay(false);
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
                layout.left( x - root.getLayoutX());
                layout.top( y - root.getLayoutY());
                layout.width(Math.max(curvePreview.getSizeWidth(), 300));
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
