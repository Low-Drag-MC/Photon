package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.client.shader.LDLibRenderTypes;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GradientColorConfigurator extends ValueConfigurator<GradientColor> {
    public final GradientColorSelector gradientSelector;
    public final UIElement colorPreview;

    public GradientColorConfigurator(String name, Supplier<GradientColor> supplier, Consumer<GradientColor> onUpdate, @Nonnull GradientColor defaultValue, boolean forceUpdate) {
        super(name, supplier, onUpdate, defaultValue, forceUpdate);

        if (value == null) {
            value = defaultValue;
        }

        this.gradientSelector = new GradientColorSelector();
        this.gradientSelector.style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER));
        this.gradientSelector.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.widthPercent(100);
            layout.maxWidth(150);
            layout.minWidth(100);
            layout.paddingAll(4);
        });
        this.gradientSelector.setOnColorGradientChangeListener(this::updateValueActively);
        this.gradientSelector.setFocusable(true);
        this.gradientSelector.setEnforceFocus(e -> hide());
        this.gradientSelector.addEventListener(UIEvents.LAYOUT_CHANGED, e -> gradientSelector.adaptPositionToScreen());

        inlineContainer.addChildren(colorPreview = new UIElement().layout(layout -> {
            layout.height(14);
            layout.paddingAll(3);
        }).style(style -> style.backgroundTexture(Sprites.RECT_RD_SOLID))
                .addChildren(new UIElement()
                        .layout(layout -> layout.heightPercent(100))
                        .style(style -> style.backgroundTexture(this::drawColorPreview))
                        .addEventListener(UIEvents.MOUSE_DOWN, this::onClick)));

        this.gradientSelector.setValue(value, false);
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
            if (value == null) return;
            onValueUpdatePassively(gradients.gradient0);
            updateValue();
        } else {
            super.onDropObject(object);
        }
    }

    @Override
    protected boolean canDropObject(@Nonnull Object object) {
        return object instanceof GradientResource.Gradients || super.canDropObject(object);
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

    protected void drawColorPreview(GuiGraphics graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        var gradientColor = value == null ? defaultValue : value;
        // render color bar
        var mat = graphics.pose().last().pose();
        var buffer = graphics.bufferSource().getBuffer(LDLibRenderTypes.guiOverlay());
        RenderSystem.disableDepthTest();

        GradientColorTexture.drawGradient(mat, buffer, x, y, width, height, gradientColor);
        GradientColorTexture.drawGradient(mat, buffer, x, y - 1, width, 1, gradientColor);
        GradientColorTexture.drawGradient(mat, buffer, x, y + height, width, 1, gradientColor);

        DrawerHelper.drawSolidRect(graphics, x - 1, y, 1, height, gradientColor.getColor(0));
        DrawerHelper.drawSolidRect(graphics, x + width, y, 1, height, gradientColor.getColor(1));
    }

}
