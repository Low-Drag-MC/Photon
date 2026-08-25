package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.lowdragmc.photon.gui.editor.resource.ResourceDialogs;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GradientColorConfigurator extends ValueConfigurator<GradientColor> {
    public final GradientColorSelector gradientSelector;
    public final UIElement colorPreview;
    /** Whether the edited gradient's rgb stops hold premultiplied HDR values. */
    protected final boolean hdr;
    // keep the floating editor open while a resource load/save dialog is on top
    protected boolean keepOpen = false;

    public GradientColorConfigurator(String name, Supplier<GradientColor> supplier, Consumer<GradientColor> onUpdate, @Nonnull GradientColor defaultValue, boolean forceUpdate) {
        this(name, supplier, onUpdate, defaultValue, forceUpdate, false);
    }

    public GradientColorConfigurator(String name, Supplier<GradientColor> supplier, Consumer<GradientColor> onUpdate, @Nonnull GradientColor defaultValue, boolean forceUpdate, boolean hdr) {
        super(name, supplier, onUpdate, defaultValue, forceUpdate);
        this.hdr = hdr;

        if (value == null) {
            value = defaultValue;
        }

        this.gradientSelector = new GradientColorSelector(hdr);
        this.gradientSelector.style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER));
        this.gradientSelector.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.widthPercent(100);
            layout.maxWidth(150);
            layout.minWidth(100);
            layout.paddingAll(4);
        });
        this.gradientSelector.moveInlineAsDefault().addClass("panel_bg");
        this.gradientSelector.setOnColorGradientChangeListener(this::updateValueActively);
        this.gradientSelector.setFocusable(true);
        this.gradientSelector.setEnforceFocus(e -> {
            if (!keepOpen) hide();
        });
        this.gradientSelector.addEventListener(UIEvents.LAYOUT_CHANGED, e -> gradientSelector.adaptPositionToScreen());
        this.gradientSelector.addChild(createResourceButtons());

        inlineContainer.addChildren(colorPreview = new UIElement().layout(layout -> {
            layout.height(14);
            layout.paddingAll(3);
        }).style(style -> style.backgroundTexture(Sprites.RECT_RD_SOLID))
                .moveInlineAsDefault()
                .addClass("configurator_preview_bg")
                .addChildren(new UIElement()
                        .layout(layout -> layout.heightPercent(100))
                        .style(style -> style.backgroundTexture(GuiTexture.of(this::drawColorPreview)))
                        .addEventListener(UIEvents.MOUSE_DOWN, this::onClick)));

        this.gradientSelector.setValue(value, false);
    }

    protected UIElement createResourceButtons() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.justifyContent(AlignContent.FLEX_END);
            layout.gapAll(2);
            layout.marginTop(2);
        }).addChildren(
                ResourceDialogs.iconButton(Icons.IMPORT, "photon.resource.load_from_resource", this::onLoadFromResource)
                        .layout(layout -> {
                            layout.width(14);
                            layout.height(14);
                        }),
                ResourceDialogs.iconButton(Icons.SAVE, "photon.resource.save_to_resource", this::onSaveToResource)
                        .layout(layout -> {
                            layout.width(14);
                            layout.height(14);
                        }));
    }

    protected void onLoadFromResource(UIEvent event) {
        keepOpen = true;
        holdOpen(ResourceDialogs.showLoadDialog(GradientResource.INSTANCE, getModularUI(), event.x, event.y, gradients -> {
            if (value == null || !accepts(gradients)) return;
            onValueUpdatePassively(gradients.gradient0.copy());
            updateValue();
        }));
    }

    protected void onSaveToResource(UIEvent event) {
        keepOpen = true;
        holdOpen(ResourceDialogs.showSaveDialog(GradientResource.INSTANCE, getModularUI(), event.x, event.y,
                () -> new GradientResource.Gradients(gradientSelector.getValue().copy()).setHDR(hdr)));
    }

    private void holdOpen(@Nullable Dialog sub) {
        if (sub == null) {
            keepOpen = false;
            return;
        }
        sub.setOnClose(() -> {
            keepOpen = false;
            this.gradientSelector.focus();
        });
    }

    @Override
    protected void onValueUpdatePassively(GradientColor newValue) {
        if (newValue == null) newValue = defaultValue;
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
        this.gradientSelector.setValue(newValue, false);
    }

    @Override
    protected void onDropObject(@NotNull Object object) {
        if (object instanceof GradientResource.Gradients gradients) {
            if (value == null || !accepts(gradients)) return;
            // copy: without it the configurator edits the resource library's own gradient in place
            onValueUpdatePassively(gradients.gradient0.copy());
            updateValue();
        } else {
            super.onDropObject(object);
        }
    }

    @Override
    protected boolean canDropObject(@Nonnull Object object) {
        if (object instanceof GradientResource.Gradients gradients) return accepts(gradients);
        return super.canDropObject(object);
    }

    /**
     * An LDR gradient is a valid HDR one (every stop is just intensity 1), so the LDR presets stay
     * usable here. The other direction is not: the stops would be silently clamped.
     */
    protected boolean accepts(GradientResource.Gradients gradients) {
        return hdr || !gradients.hdr;
    }

    public void show() {
        var parent = this.gradientSelector.getParent();
        if (parent != null) {
            return;
        }
        var mui = getModularUI();
        if (mui != null) {
            var root = mui.ui.rootElement;
            root.addChild(gradientSelector.layout(layout -> {
                var x = colorPreview.getPositionX();
                var y = colorPreview.getPositionY();
                layout.left( x - root.getLayoutX());
                layout.top( y - root.getLayoutY());
                layout.width(colorPreview.getSizeWidth());
            }));
            this.gradientSelector.focus();
        }
    }

    public void hide() {
        var parent = this.gradientSelector.getParent();
        if (parent != null) {
            this.gradientSelector.blur();
            parent.removeChild(this.gradientSelector);
        }
    }

    protected void onClick(UIEvent event) {
        if (this.gradientSelector.getParent() != null) {
            hide();
        } else {
            show();
        }
    }

    protected void drawColorPreview(GUIContext graphics, float x, float y, float width, float height) {
        var gradientColor = value == null ? defaultValue : value;
        // render color bar (26.1: per-corner-colored context.fill segments, no immediate buffer)

        GradientColorTexture.drawGradient(graphics, x, y, width, height, gradientColor);
        GradientColorTexture.drawGradient(graphics, x, y - 1, width, 1, gradientColor);
        GradientColorTexture.drawGradient(graphics, x, y + height, width, 1, gradientColor);

        DrawerHelperClient.drawSolidRect(graphics, x - 1, y, 1, height, gradientColor.getColor(0));
        DrawerHelperClient.drawSolidRect(graphics, x + width, y, 1, height, gradientColor.getColor(1));
    }

}
