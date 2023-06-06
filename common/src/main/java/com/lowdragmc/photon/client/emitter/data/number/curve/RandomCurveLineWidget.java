package com.lowdragmc.photon.client.emitter.data.number.curve;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.MenuWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.lowdraglib.utils.curve.ExplicitCubicBezierCurve2;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * @author KilaBash
 * @date 2023/5/28
 * @implNote RandomCurveLineWidget
 */
public class RandomCurveLineWidget extends WidgetGroup {

    public final ECBCurves curves0, curves1;
    @Setter @Getter
    protected boolean lockControlPoint = true;
    @Setter @Getter
    protected int selectedPoint0 = -1, selectedPoint1 = -1;
    @Setter
    protected boolean renderGrid = true;
    @Setter
    protected Size gridSize = new Size(2, 2);
    @Setter
    protected Consumer<Pair<ECBCurves, ECBCurves>> onUpdate;
    @Setter
    protected Function<Vec2, Component> hoverTips;
    // runtime
    private boolean isDraggingPoint, isDraggingLeftControlPoint, isDraggingRightControlPoint;
    private long lastClickTick;


    public RandomCurveLineWidget(int x, int y, int width, int height, ECBCurves curves0, ECBCurves curves1) {
        super(x, y, width, height);
        this.curves0 = curves0;
        this.curves1 = curves1;
    }

    public Vec2 getPointPosition(Vec2 coord) {
        var size = getSize();
        var position = getPosition();
        return new Vec2(position.x + size.width * coord.x, position.y + size.height * (1 - coord.y));
    }

    public Vec2 getPointCoordinate(Vec2 pos) {
        var size = getSize();
        var position = getPosition();
        return new Vec2((pos.x - position.x) / size.width, 1 - (pos.y - position.y) / size.height);
    }

    public Vec2 getPointCoordinate(ECBCurves curves, int index) {
        if (index < curves.size()) {
            return curves.get(index).p0;
        }
        if (index > 0) {
            return curves.get(index - 1).p1;
        }
        return null;
    }

    public void setPointCoordinate(ECBCurves curves, int index, Vec2 coord) {
        if (index < curves.size()) {
            var offset = new Vec2(coord.x - curves.get(index).p0.x, coord.y - curves.get(index).p0.y);
            curves.get(index).p0 = coord;
            curves.get(index).c0 = curves.get(index).c0.add(offset);
        }
        if (index > 0) {
            var offset = new Vec2(coord.x - curves.get(index - 1).p1.x, coord.y - curves.get(index - 1).p1.y);
            curves.get(index - 1).p1 = coord;
            curves.get(index - 1).c1 = curves.get(index - 1).c1.add(offset);
        }
    }

    public void setLeftControlPointCoordinate(ECBCurves curves, int index, Vec2 coord) {
        if (index > 0) {
            curves.get(index - 1).c1 = coord;
        }
    }

    public void setRightControlPointCoordinate(ECBCurves curves, int index, Vec2 coord) {
        if (index < curves.size()) {
            curves.get(index).c0 = coord;
        }
    }

    protected void notifyChanged() {
        if (onUpdate != null) onUpdate.accept(Pair.of(curves0, curves1));
    }

    protected void openMenu(int mouseX, int mouseY) {
        var menu = TreeBuilder.Menu.start()
                .leaf(lockControlPoint ? Icons.CHECK : IGuiTexture.EMPTY, "Lock Controll Points", () -> lockControlPoint = !lockControlPoint);
        if ((selectedPoint0 != -1 && curves0.size() > 1) || (selectedPoint1 != -1 && curves1.size() > 1)) {
            final var curves = selectedPoint0 == -1 ? curves1 : curves0;
            final var selectedPoint = selectedPoint0 == -1 ? selectedPoint1 : selectedPoint0;
            menu.leaf("Remove", () -> {
                if (selectedPoint == 0) {
                    curves.remove(0);
                } else if (selectedPoint > 0 && selectedPoint < curves.size()) {
                    curves.get(selectedPoint - 1).p1 = curves.get(selectedPoint).p1;
                    curves.get(selectedPoint - 1).c1 = curves.get(selectedPoint).c0;
                    curves.remove(selectedPoint);
                } else if (selectedPoint >= curves.size()) {
                    curves.remove(curves.size() - 1);
                }
                selectedPoint0 = -1;
                selectedPoint1 = -1;
                notifyChanged();
            });

            var widget = new MenuWidget<>(mouseX - getPosition().x, mouseY - getPosition().y, 14, menu.build())
                    .setNodeTexture(new IGuiTexture() {
                        @Override
                        @Environment(EnvType.CLIENT)
                        public void draw(PoseStack stack, int mouseX, int mouseY, float x, float y, int width, int height) {
                            ColorPattern.BLACK.rectTexture().draw(stack, mouseX, mouseY, x, y, width, height);
                            Icons.RIGHT.draw(stack, mouseX, mouseY, x + width - height + 3, y + 3, height - 6, height - 6);
                        }
                    })
                    .setLeafTexture(ColorPattern.BLACK.rectTexture())
                    .setNodeHoverTexture(ColorPattern.T_GRAY.rectTexture())
                    .setCrossLinePredicate(TreeBuilder.Menu::isCrossLine)
                    .setKeyIconSupplier(TreeBuilder.Menu::getIcon)
                    .setKeyNameSupplier(TreeBuilder.Menu::getName)
                    .setOnNodeClicked(TreeBuilder.Menu::handle);
            waitToAdded(widget.setBackground(new ColorRectTexture(0xff3C4146), ColorPattern.GRAY.borderTexture(1)));
        }

    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (isMouseOver(getPosition().x - 2, getPosition().y - 2, getSize().width + 4, getSize().height + 4, mouseX, mouseY)) {
            if (button == 1) {
                openMenu((int) mouseX, (int) mouseY);
            } else {
                var clickTick = gui.getTickCount();
                // click point
                if (clickPoint(curves0, i -> this.selectedPoint0 = i, mouseX, mouseY)) return true;
                if (clickPoint(curves1, i -> this.selectedPoint1 = i, mouseX, mouseY)) return true;
                // click control point
                if (clickControlPoint(curves0, selectedPoint0, mouseX, mouseY)) return true;
                if (clickControlPoint(curves1, selectedPoint1, mouseX, mouseY)) return true;
                // double click curve
                if (clickTick - lastClickTick < 12 && isMouseOverElement(mouseX, mouseY)) {
                    lastClickTick = 0;
                    var x = (float) ((mouseX - getPosition().x) / getSize().width);
                    if (doubleClickCurve(curves0, x, mouseX, mouseY)) return true;
                    if (doubleClickCurve(curves1, x, mouseX, mouseY)) return true;
                } else {
                    lastClickTick = clickTick;
                }
            }
        }
        return false;
    }

    private boolean clickPoint(ECBCurves curves, IntConsumer selector, double mouseX, double mouseY) {
        for (int i = 0; i < curves.size(); i++) {
            var curve = curves.get(i);
            if (i == 0) {
                var pos = getPointPosition(curve.p0);
                if (isMouseOver((int) (pos.x - 2), (int) (pos.y - 2), 4, 4, mouseX, mouseY)) {
                    this.selectedPoint0 = -1;
                    this.selectedPoint1 = -1;
                    selector.accept(0);
                    this.isDraggingPoint = true;
                    return true;
                }
            }
            var pos = getPointPosition(curve.p1);
            if (isMouseOver((int) (pos.x - 2), (int) (pos.y - 2), 4, 4, mouseX, mouseY)) {
                this.selectedPoint0 = -1;
                this.selectedPoint1 = -1;
                selector.accept(i + 1);
                this.isDraggingPoint = true;
                return true;
            }
        }
        return false;
    }

    private boolean clickControlPoint(ECBCurves curves, int selectedPoint, double mouseX, double mouseY) {
        if (selectedPoint >= 0) {
            if (selectedPoint > 0 ) { // left control point
                var pos = getPointPosition(curves.get(selectedPoint - 1).c1);
                if (isMouseOver((int) (pos.x - 2), (int) (pos.y - 2), 4, 4, mouseX, mouseY)) {
                    this.isDraggingLeftControlPoint = true;
                    return true;
                }
            }
            if (selectedPoint < curves.size()) { // right control point
                var pos = getPointPosition(curves.get(selectedPoint).c0);
                if (isMouseOver((int) (pos.x - 2), (int) (pos.y - 2), 4, 4, mouseX, mouseY)) {
                    this.isDraggingRightControlPoint = true;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean doubleClickCurve(ECBCurves curves, float x, double mouseX, double mouseY) {
        var y = curves.get(0).p0.y;
        var found = x < curves.get(0).p0.x;
        var index = 0;
        if (!found) {
            for (ExplicitCubicBezierCurve2 curve : curves) {
                index++;
                if (x >= curve.p0.x && x <= curve.p1.x) {
                    y = curve.getPoint((x - curve.p0.x) / (curve.p1.x - curve.p0.x)).y;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            index++;
            y = curves.get(curves.size() - 1).p1.y;
        }
        var position = getPointPosition(new Vec2(x, y));
        if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
            if (index == 0) {
                var right = curves.get(0).p0;
                var rightCP = curves.get(index).c0;
                curves.add(0, new ExplicitCubicBezierCurve2(new Vec2(x, y), new Vec2(x + 0.1f, y), new Vec2(right.x + (right.x - rightCP.x), right.y + (right.y - rightCP.y)), right));
            } else if (index > curves.size()) {
                var left = curves.get(curves.size() - 1).p1;
                var leftCP = curves.get(curves.size() - 1).c1;
                curves.add(new ExplicitCubicBezierCurve2(left, new Vec2(left.x + (left.x - leftCP.x), left.y + (left.y - leftCP.y)), new Vec2(x - 0.1f, y), new Vec2(x, y)));
            } else {
                var curve = curves.get(index - 1);
                curves.add(index, new ExplicitCubicBezierCurve2(new Vec2(x, y), new Vec2(x + 0.1f, y), curve.c1, curve.p1));
                curve.c1 = new Vec2(x - 0.1f, y);
                curve.p1 = new Vec2(x, y);
            }
            notifyChanged();
            return true;
        }
        return false;
    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.isDraggingPoint = false;
        this.isDraggingLeftControlPoint = false;
        this.isDraggingRightControlPoint = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        dragSelected(curves0, selectedPoint0, mouseX, mouseY);
        dragSelected(curves1, selectedPoint1, mouseX, mouseY);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void dragSelected(ECBCurves curves, int selectedPoint, double mouseX, double mouseY) {
        var size = getSize();
        var position = getPosition();
        if (selectedPoint >= 0) {
            if (this.isDraggingPoint) {
                float minX = position.x;
                float maxX = position.x + size.width;
                if (selectedPoint > 0) {
                    minX = Math.max(minX, getPointPosition(getPointCoordinate(curves, selectedPoint - 1)).x);
                }
                if (selectedPoint < curves.size()) {
                    maxX = Math.min(maxX, getPointPosition(getPointCoordinate(curves, selectedPoint + 1)).x);
                }
                var coord = getPointCoordinate(new Vec2((float) Mth.clamp(mouseX, minX, maxX), (float) Mth.clamp(mouseY, position.y, position.y + size.height)));
                setPointCoordinate(curves, selectedPoint, coord);
                notifyChanged();
            }
            var point = getPointCoordinate(curves, selectedPoint);
            var pointPos = getPointPosition(point);
            if (this.isDraggingLeftControlPoint) {
                var coord = getPointCoordinate(new Vec2((float) Mth.clamp(mouseX, position.x, pointPos.x), (float) mouseY));
                setLeftControlPointCoordinate(curves, selectedPoint, coord);
                if (lockControlPoint) {
                    setRightControlPointCoordinate(curves, selectedPoint, new Vec2(point.x + (point.x - coord.x), point.y + (point.y - coord.y)));
                }
                notifyChanged();
            }
            if (this.isDraggingRightControlPoint) {
                var coord = getPointCoordinate(new Vec2((float) Mth.clamp(mouseX, pointPos.x, position.x + size.width), (float) mouseY));
                setRightControlPointCoordinate(curves, selectedPoint, coord);
                if (lockControlPoint) {
                    setLeftControlPointCoordinate(curves, selectedPoint, new Vec2(point.x + (point.x - coord.x), point.y + (point.y - coord.y)));
                }
                notifyChanged();
            }
        }
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void drawInBackground(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        // render background
        if (backgroundTexture != null) {
            Position pos = getPosition();
            Size size = getSize();
            backgroundTexture.draw(poseStack, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
        }
        if (hoverTexture != null && isMouseOverElement(mouseX, mouseY)) {
            Position pos = getPosition();
            Size size = getSize();
            hoverTexture.draw(poseStack, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
        }
        // render grid
        if (renderGrid) {
            var pos = getPosition();
            var size = getSize();
            for (int i = 0; i < gridSize.width; i++) {
                DrawerHelper.drawSolidRect(poseStack, pos.x + i * getSize().width / gridSize.width, pos.y, 1, size.height, ColorPattern.T_GRAY.color);
            }
            for (int i = 0; i < gridSize.height; i++) {
                DrawerHelper.drawSolidRect(poseStack, pos.x, pos.y + i * getSize().height / gridSize.height, size.width, 1, ColorPattern.T_GRAY.color);
            }
        }
        // render area
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        var matrix = poseStack.last().pose();
        var count = getSize().width * 2;
        for (int i = 0; i < count; i++) {
            float x0 = i * 1f / count;
            float x1 = (i + 1) * 1f / count;

            var p0 = getPointPosition(new Vec2(x0, curves0.getCurveY(x0)));
            var p1 = getPointPosition(new Vec2(x1, curves0.getCurveY(x1)));
            var p2 = getPointPosition(new Vec2(x1, curves1.getCurveY(x1)));
            var p3 = getPointPosition(new Vec2(x0, curves1.getCurveY(x0)));

            bufferBuilder.vertex(matrix, p0.x, p0.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
            bufferBuilder.vertex(matrix, p1.x, p1.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
            bufferBuilder.vertex(matrix, p2.x, p2.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
            bufferBuilder.vertex(matrix, p3.x, p3.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();

            bufferBuilder.vertex(matrix, p3.x, p3.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
            bufferBuilder.vertex(matrix, p2.x, p2.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
            bufferBuilder.vertex(matrix, p1.x, p1.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
            bufferBuilder.vertex(matrix, p0.x, p0.y, 0.0f).color(ColorPattern.T_WHITE.color).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.enableTexture();

        // render lines
        var points0 = curves0.stream().flatMap(curve -> curve.getPoints(100).stream().map(this::getPointPosition).toList().stream()).collect(Collectors.toList());
        var points1 = curves1.stream().flatMap(curve -> curve.getPoints(100).stream().map(this::getPointPosition).toList().stream()).collect(Collectors.toList());
        DrawerHelper.drawLines(poseStack, points0, ColorPattern.YELLOW.color, ColorPattern.YELLOW.color, 0.5f);
        DrawerHelper.drawLines(poseStack, points1, ColorPattern.GREEN.color, ColorPattern.GREEN.color, 0.5f);
        Collections.reverse(points0);
        Collections.reverse(points1);
        DrawerHelper.drawLines(poseStack, points0, ColorPattern.YELLOW.color, ColorPattern.YELLOW.color, 0.5f);
        DrawerHelper.drawLines(poseStack, points1, ColorPattern.GREEN.color, ColorPattern.GREEN.color, 0.5f);

        // render outer lines
        if (curves0.get(0).p0.x > 0) {
            DrawerHelper.drawLines(poseStack, List.of(getPointPosition(new Vec2(0, curves0.get(0).p0.y)), getPointPosition(curves0.get(0).p0)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        if (curves0.get(curves0.size() - 1).p1.x < 1) {
            DrawerHelper.drawLines(poseStack, List.of(getPointPosition(new Vec2(1, curves0.get(curves0.size() - 1).p1.y)), getPointPosition(curves0.get(curves0.size() - 1).p1)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        if (curves1.get(0).p0.x > 0) {
            DrawerHelper.drawLines(poseStack, List.of(getPointPosition(new Vec2(0, curves1.get(0).p0.y)), getPointPosition(curves1.get(0).p0)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        if (curves1.get(curves1.size() - 1).p1.x < 1) {
            DrawerHelper.drawLines(poseStack, List.of(getPointPosition(new Vec2(1, curves1.get(curves1.size() - 1).p1.y)), getPointPosition(curves1.get(curves1.size() - 1).p1)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        // render control lines
        if (selectedPoint0 >= 0) {
            if (selectedPoint0 > 0) { //render left
                var curve = curves0.get(selectedPoint0 - 1);
                DrawerHelper.drawLines(poseStack, List.of(getPointPosition(curve.c1), getPointPosition(curve.p1)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
                renderControlPoint(curve.c1, poseStack);
            }
            if (selectedPoint0 < curves0.size()) { //render right
                var curve = curves0.get(selectedPoint0);
                DrawerHelper.drawLines(poseStack, List.of(getPointPosition(curve.c0), getPointPosition(curve.p0)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
                renderControlPoint(curve.c0, poseStack);
            }
        }
        if (selectedPoint1 >= 0) {
            if (selectedPoint1 > 0) { //render left
                var curve = curves1.get(selectedPoint1 - 1);
                DrawerHelper.drawLines(poseStack, List.of(getPointPosition(curve.c1), getPointPosition(curve.p1)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
                renderControlPoint(curve.c1, poseStack);
            }
            if (selectedPoint1 < curves1.size()) { //render right
                var curve = curves1.get(selectedPoint1);
                DrawerHelper.drawLines(poseStack, List.of(getPointPosition(curve.c0), getPointPosition(curve.p0)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
                renderControlPoint(curve.c0, poseStack);
            }
        }
        // render points
        for (int i = 0; i < curves0.size(); i++) {
            var curve = curves0.get(i);
            if (i == 0) {
                renderPoint(curve.p0, selectedPoint0 == 0, poseStack, mouseX, mouseY);
            }
            renderPoint(curve.p1, selectedPoint0 == i + 1, poseStack, mouseX, mouseY);
        }
        for (int i = 0; i < curves1.size(); i++) {
            var curve = curves1.get(i);
            if (i == 0) {
                renderPoint(curve.p0, selectedPoint1 == 0, poseStack, mouseX, mouseY);
            }
            renderPoint(curve.p1, selectedPoint1 == i + 1, poseStack, mouseX, mouseY);
        }
        // render children
        for (Widget widget : widgets) {
            if (widget.isVisible()) {
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.enableBlend();
                if (widget.inAnimate()) {
                    widget.getAnimation().drawInBackground(poseStack, mouseX, mouseY, partialTicks);
                } else {
                    widget.drawInBackground(poseStack, mouseX, mouseY, partialTicks);
                }
            }
        }
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void drawInForeground(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        super.drawInForeground(poseStack, mouseX, mouseY, partialTicks);
        if (gui != null && gui.getModularUIGui() != null && hoverTips != null) {
            if (renderHoverTips(mouseX, mouseY, curves0)) return;
            renderHoverTips(mouseX, mouseY, curves1);
        }
    }

    private boolean renderHoverTips(int mouseX, int mouseY, ECBCurves curves0) {
        for (int i = 0; i < curves0.size(); i++) {
            var curve = curves0.get(i);
            if (i == 0) {
                var position = getPointPosition(curve.p0);
                if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
                    gui.getModularUIGui().setHoverTooltip(List.of(hoverTips.apply(curve.p0)), ItemStack.EMPTY, null, null);
                    return true;
                }
            }
            var position = getPointPosition(curve.p1);
            if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
                gui.getModularUIGui().setHoverTooltip(List.of(hoverTips.apply(curve.p0)), ItemStack.EMPTY, null, null);
                return true;
            }
        }
        return false;
    }

    @Environment(EnvType.CLIENT)
    protected void renderPoint(Vec2 point, boolean isSelected, @NotNull PoseStack poseStack, int mouseX, int mouseY) {
        var position = getPointPosition(point);
        if (isSelected) {
            ColorPattern.RED.rectTexture().setRadius(2).draw(poseStack, mouseX, mouseY, position.x - 2, position.y - 2, 4, 4);
        } else {
            ColorPattern.GRAY.rectTexture().setRadius(2).draw(poseStack, mouseX, mouseY, position.x - 2, position.y - 2, 4, 4);
            if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
                ColorPattern.WHITE.borderTexture(1).setRadius(2).draw(poseStack, mouseX, mouseY, position.x - 2, position.y - 2, 4, 4);
            }
        }

    }

    @Environment(EnvType.CLIENT)
    protected void renderControlPoint(Vec2 point, @NotNull PoseStack poseStack) {
        var position = getPointPosition(point);
        ColorPattern.GREEN.rectTexture().setRadius(1).draw(poseStack, 0, 0, position.x - 1, position.y - 1, 2, 2);
    }
}
