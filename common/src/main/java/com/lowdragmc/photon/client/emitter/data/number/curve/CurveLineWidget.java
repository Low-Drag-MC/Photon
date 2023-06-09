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
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author KilaBash
 * @date 2023/5/28
 * @implNote CurveView
 */
public class CurveLineWidget extends WidgetGroup {

    public final ECBCurves curves;
    @Setter @Getter
    protected boolean lockControlPoint = true;
    @Setter @Getter
    protected int selectedPoint = -1;
    @Setter
    protected boolean renderGrid = true;
    @Setter
    protected Size gridSize = new Size(2, 2);
    @Setter
    protected Consumer<ECBCurves> onUpdate;
    @Setter
    protected Function<Vec2, Component> hoverTips;
    // runtime
    private boolean isDraggingPoint, isDraggingLeftControlPoint, isDraggingRightControlPoint;
    private long lastClickTick;


    public CurveLineWidget(int x, int y, int width, int height, ECBCurves curves) {
        super(x, y, width, height);
        this.curves = curves;
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

    public Vec2 getPointCoordinate(int index) {
        if (index < curves.size()) {
            return curves.get(index).p0;
        }
        if (index > 0) {
            return curves.get(index - 1).p1;
        }
        return null;
    }

    public void setPointCoordinate(int index, Vec2 coord) {
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

    public void setLeftControlPointCoordinate(int index, Vec2 coord) {
        if (index > 0) {
            curves.get(index - 1).c1 = coord;
        }
    }

    public void setRightControlPointCoordinate(int index, Vec2 coord) {
        if (index < curves.size()) {
            curves.get(index).c0 = coord;
        }
    }

    protected void notifyChanged() {
        if (onUpdate != null) onUpdate.accept(curves);
    }

    protected void openMenu(int mouseX, int mouseY) {
        var menu = TreeBuilder.Menu.start()
                .leaf(lockControlPoint ? Icons.CHECK : IGuiTexture.EMPTY, "Lock Controll Points", () -> lockControlPoint = !lockControlPoint)
                .branch("Grid", m -> m
                        .leaf("2×2", () -> setGridSize(new Size(2, 2)))
                        .leaf("6×2", () -> setGridSize(new Size(6, 2)))
                        .leaf("6×4", () -> setGridSize(new Size(6, 4))));
        if (selectedPoint != -1 && curves.size() > 1) {
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
                selectedPoint = -1;
                notifyChanged();
            });

            var widget = new MenuWidget<>(mouseX - getPosition().x, mouseY - getPosition().y, 14, menu.build())
                    .setNodeTexture(new IGuiTexture() {
                        @Override
                        @Environment(EnvType.CLIENT)
                        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, int width, int height) {
                            ColorPattern.BLACK.rectTexture().draw(graphics, mouseX, mouseY, x, y, width, height);
                            Icons.RIGHT.draw(graphics, mouseX, mouseY, x + width - height + 3, y + 3, height - 6, height - 6);
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
                for (int i = 0; i < curves.size(); i++) {
                    var curve = curves.get(i);
                    if (i == 0) {
                        var pos = getPointPosition(curve.p0);
                        if (isMouseOver((int) (pos.x - 2), (int) (pos.y - 2), 4, 4, mouseX, mouseY)) {
                            this.selectedPoint = 0;
                            this.isDraggingPoint = true;
                            return true;
                        }
                    }
                    var pos = getPointPosition(curve.p1);
                    if (isMouseOver((int) (pos.x - 2), (int) (pos.y - 2), 4, 4, mouseX, mouseY)) {
                        this.selectedPoint = i + 1;
                        this.isDraggingPoint = true;
                        return true;
                    }
                }
                // click control point
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
                if (clickTick - lastClickTick < 12 && isMouseOverElement(mouseX, mouseY)) { // double click
                    lastClickTick = 0;
                    var x = (float) ((mouseX - getPosition().x) / getSize().width);
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
                    }
                } else {
                    lastClickTick = clickTick;
                }
            }
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
        var size = getSize();
        var position = getPosition();
        if (selectedPoint >= 0) {
            if (this.isDraggingPoint) {
                float minX = position.x;
                float maxX = position.x + size.width;
                if (selectedPoint > 0) {
                    minX = Math.max(minX, getPointPosition(getPointCoordinate(selectedPoint - 1)).x);
                }
                if (selectedPoint < curves.size()) {
                    maxX = Math.min(maxX, getPointPosition(getPointCoordinate(selectedPoint + 1)).x);
                }
                var coord = getPointCoordinate(new Vec2((float) Mth.clamp(mouseX, minX, maxX), (float) Mth.clamp(mouseY, position.y, position.y + size.height)));
                setPointCoordinate(selectedPoint, coord);
                notifyChanged();
            }
            var point = getPointCoordinate(selectedPoint);
            var pointPos = getPointPosition(point);
            if (this.isDraggingLeftControlPoint) {
                var coord = getPointCoordinate(new Vec2((float) Mth.clamp(mouseX, position.x, pointPos.x), (float) mouseY));
                setLeftControlPointCoordinate(selectedPoint, coord);
                if (lockControlPoint) {
                    setRightControlPointCoordinate(selectedPoint, new Vec2(point.x + (point.x - coord.x), point.y + (point.y - coord.y)));
                }
                notifyChanged();
            }
            if (this.isDraggingRightControlPoint) {
                var coord = getPointCoordinate(new Vec2((float) Mth.clamp(mouseX, pointPos.x, position.x + size.width), (float) mouseY));
                setRightControlPointCoordinate(selectedPoint, coord);
                if (lockControlPoint) {
                    setLeftControlPointCoordinate(selectedPoint, new Vec2(point.x + (point.x - coord.x), point.y + (point.y - coord.y)));
                }
                notifyChanged();
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // render background
        if (backgroundTexture != null) {
            Position pos = getPosition();
            Size size = getSize();
            backgroundTexture.draw(graphics, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
        }
        if (hoverTexture != null && isMouseOverElement(mouseX, mouseY)) {
            Position pos = getPosition();
            Size size = getSize();
            hoverTexture.draw(graphics, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
        }
        // render grid
        if (renderGrid) {
            var pos = getPosition();
            var size = getSize();
            for (int i = 0; i < gridSize.width; i++) {
                DrawerHelper.drawSolidRect(graphics, pos.x + i * getSize().width / gridSize.width, pos.y, 1, size.height, ColorPattern.T_GRAY.color);
            }
            for (int i = 0; i < gridSize.height; i++) {
                DrawerHelper.drawSolidRect(graphics, pos.x, pos.y + i * getSize().height / gridSize.height, size.width, 1, ColorPattern.T_GRAY.color);
            }
        }
        // render lines
        var points = curves.stream().flatMap(curve -> curve.getPoints(100).stream().map(this::getPointPosition).toList().stream()).collect(Collectors.toList());
        DrawerHelper.drawLines(graphics, points, -1, -1, 0.5f);
        Collections.reverse(points);
        DrawerHelper.drawLines(graphics, points, -1, -1, 0.5f);
        // render outer lines
        if (curves.get(0).p0.x > 0) {
            DrawerHelper.drawLines(graphics, List.of(getPointPosition(new Vec2(0, curves.get(0).p0.y)), getPointPosition(curves.get(0).p0)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        if (curves.get(curves.size() - 1).p1.x < 1) {
            DrawerHelper.drawLines(graphics, List.of(getPointPosition(new Vec2(1, curves.get(curves.size() - 1).p1.y)), getPointPosition(curves.get(curves.size() - 1).p1)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        // render control lines
        if (selectedPoint >= 0) {
            if (selectedPoint > 0) { //render left
                var curve = curves.get(selectedPoint - 1);
                DrawerHelper.drawLines(graphics, List.of(getPointPosition(curve.c1), getPointPosition(curve.p1)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
                renderControlPoint(curve.c1, graphics);
            }
            if (selectedPoint < curves.size()) { //render right
                var curve = curves.get(selectedPoint);
                DrawerHelper.drawLines(graphics, List.of(getPointPosition(curve.c0), getPointPosition(curve.p0)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
                renderControlPoint(curve.c0, graphics);
            }
        }
        // render points
        for (int i = 0; i < curves.size(); i++) {
            var curve = curves.get(i);
            if (i == 0) {
                renderPoint(curve.p0, selectedPoint == 0, graphics, mouseX, mouseY);
            }
            renderPoint(curve.p1, selectedPoint == i + 1, graphics, mouseX, mouseY);
        }
        // render children
        for (Widget widget : widgets) {
            if (widget.isVisible()) {
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.enableBlend();
                if (widget.inAnimate()) {
                    widget.getAnimation().drawInBackground(graphics, mouseX, mouseY, partialTicks);
                } else {
                    widget.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                }
            }
        }
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void drawInForeground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        if (gui != null && gui.getModularUIGui() != null && hoverTips != null) {
            for (int i = 0; i < curves.size(); i++) {
                var curve = curves.get(i);
                if (i == 0) {
                    var position = getPointPosition(curve.p0);
                    if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
                        gui.getModularUIGui().setHoverTooltip(List.of(hoverTips.apply(curve.p0)), ItemStack.EMPTY, null, null);
                        return;
                    }
                }
                var position = getPointPosition(curve.p1);
                if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
                    gui.getModularUIGui().setHoverTooltip(List.of(hoverTips.apply(curve.p0)), ItemStack.EMPTY, null, null);
                    return;
                }
            }
        }
    }

    @Environment(EnvType.CLIENT)
    protected void renderPoint(Vec2 point, boolean isSelected, @NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        var position = getPointPosition(point);
        if (isSelected) {
            ColorPattern.RED.rectTexture().setRadius(2).draw(graphics, mouseX, mouseY, position.x - 2, position.y - 2, 4, 4);
        } else {
            ColorPattern.GRAY.rectTexture().setRadius(2).draw(graphics, mouseX, mouseY, position.x - 2, position.y - 2, 4, 4);
            if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, mouseX, mouseY)) {
                ColorPattern.WHITE.borderTexture(1).setRadius(2).draw(graphics, mouseX, mouseY, position.x - 2, position.y - 2, 4, 4);
            }
        }

    }

    @Environment(EnvType.CLIENT)
    protected void renderControlPoint(Vec2 point, @NotNull GuiGraphics graphics) {
        var position = getPointPosition(point);
        ColorPattern.GREEN.rectTexture().setRadius(1).draw(graphics, 0, 0, position.x - 1, position.y - 1, 2, 2);
    }
}
