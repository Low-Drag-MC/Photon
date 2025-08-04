package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.apache.commons.lang3.tuple.Pair;
import org.appliedenergistics.yoga.YogaEdge;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaGutter;
import org.appliedenergistics.yoga.YogaPositionType;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RandomGradientColorConfigurator extends ValueConfigurator<Pair<GradientColor, GradientColor>> {
    public final UIElement gradientSelector;
    public final GradientColorSelector gradientSelector0, gradientSelector1;
    public final UIElement colorPreview;

    public RandomGradientColorConfigurator(String name, Supplier<Pair<GradientColor, GradientColor>> supplier, Consumer<Pair<GradientColor, GradientColor>> onUpdate, @Nonnull Pair<GradientColor, GradientColor> defaultValue, boolean forceUpdate) {
        super(name, supplier, onUpdate, defaultValue, forceUpdate);

        if (value == null) {
            value = defaultValue;
        }

        inlineContainer.addChildren(colorPreview = new UIElement().layout(layout -> {
                    layout.setHeight(14);
                    layout.setPadding(YogaEdge.ALL, 3);
                }).style(style -> style.backgroundTexture(Sprites.RECT_RD_SOLID))
                .addChildren(new UIElement()
                        .layout(layout -> layout.setHeightPercent(100))
                        .style(style -> style.backgroundTexture(this::drawColorPreview))
                        .addEventListener(UIEvents.MOUSE_DOWN, this::onClick)));

        this.gradientSelector0 = createGradientSelector(gradient -> updateValueActively(Pair.of(gradient, value.getRight() ) ), value.getLeft());
        this.gradientSelector1 = createGradientSelector(gradient -> updateValueActively(Pair.of(value.getLeft(), gradient) ), value.getRight());

        this.gradientSelector = new UIElement().layout(layout -> {
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setFlexDirection(YogaFlexDirection.ROW);
            layout.setPadding(YogaEdge.ALL, 4);
            layout.setGap(YogaGutter.ALL, 2);
            layout.setMaxWidth(300);
        }).style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER))
                .addChildren(gradientSelector0, gradientSelector1);
        this.gradientSelector.setFocusable(true);
        this.gradientSelector.setEnforceFocus(e -> hide());
        this.gradientSelector.addEventListener(UIEvents.LAYOUT_CHANGED, e -> gradientSelector.adaptPositionToScreen());
    }

    private GradientColorSelector createGradientSelector(Consumer<GradientColor> onGradientChanged, GradientColor initialValue) {
        var gradientSelector = new GradientColorSelector();
        gradientSelector.layout(layout -> {
            layout.setFlex(1);
        });
        gradientSelector.setOnColorGradientChangeListener(onGradientChanged);
        gradientSelector.setValue(initialValue, false);
        return gradientSelector;
    }

    @Override
    protected void onValueUpdatePassively(Pair<GradientColor, GradientColor> newValue) {
        if (newValue == null) newValue = defaultValue;
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
        this.gradientSelector0.setValue(newValue.getLeft(), false);
        this.gradientSelector1.setValue(newValue.getRight(), false);
    }

    @Override
    protected void onDropObject(@NotNull Object object) {
        if (object instanceof GradientResource.Gradients gradients) {
            if (gradients.gradient1 == null || value == null) return;
            onValueUpdatePassively(Pair.of(gradients.gradient0, gradients.gradient1));
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
                layout.setPosition(YogaEdge.LEFT, x - root.getLayoutX());
                layout.setPosition(YogaEdge.TOP, y - root.getLayoutY());
                layout.setWidth(colorPreview.getSizeWidth());
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

    protected void drawColorPreview(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTicks) {
        var gradientColor = value == null ? defaultValue : value;
        // render color bar
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        var mat = graphics.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        GradientColorTexture.drawGradient(mat, buffer, x, y, width, height / 2, gradientColor.getLeft());
        GradientColorTexture.drawGradient(mat, buffer, x, y - 1, width, 1, gradientColor.getLeft());
        GradientColorTexture.drawGradient(mat, buffer, x, y + height / 2, width, height / 2, gradientColor.getRight());
        GradientColorTexture.drawGradient(mat, buffer, x, y + height, width, 1, gradientColor.getRight());
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        graphics.drawManaged(() -> {
            DrawerHelper.drawSolidRect(graphics, x - 1, y, 1, height / 2, gradientColor.getLeft().getColor(0), false);
            DrawerHelper.drawSolidRect(graphics, x + width, y, 1, height / 2, gradientColor.getLeft().getColor(1), false);
            DrawerHelper.drawSolidRect(graphics, x - 1, y + height / 2, 1, height / 2, gradientColor.getRight().getColor(0), false);
            DrawerHelper.drawSolidRect(graphics, x + width, y + height / 2, 1, height / 2, gradientColor.getRight().getColor(1), false);
        });
    }

}
