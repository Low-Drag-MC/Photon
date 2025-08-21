package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableUIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import lombok.Getter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import org.appliedenergistics.yoga.YogaDisplay;
import org.appliedenergistics.yoga.YogaEdge;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaPositionType;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CurveGraph extends BindableUIElement<ECBCurves> {
    public final UIElement graphView = new UIElement();

    @Getter
    protected ECBCurves value = new ECBCurves();
    protected final List<UIElement> pointsUI = new ArrayList<>();
    protected boolean lockControlPoint = true;

    // runtime
    protected int selectedPoint = -1;
    protected UIElement controlPoint1 = new UIElement();
    protected UIElement controlPoint2 = new UIElement();

    public CurveGraph() {
        refreshGraph();
        getLayout().setFlexDirection(YogaFlexDirection.ROW);

        graphView.layout(layout -> {
            layout.setHeightPercent(100);
            layout.setFlex(1);
        }).style(style -> style.backgroundTexture(this::drawGraphView))
                .addEventListener(UIEvents.MOUSE_DOWN, this::onGraphMouseDown)
                .addEventListener(UIEvents.DOUBLE_CLICK, this::onGraphDoubleClick)
                .addChildren(controlPoint1, controlPoint2);

        addChildren(graphView);

        controlPoint1.layout(layout -> {
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setWidth(2);
            layout.setHeight(2);
            layout.setMargin(YogaEdge.LEFT, -1);
            layout.setMargin(YogaEdge.TOP, -1);
        }).style(style -> style.backgroundTexture(ColorPattern.GREEN.rectTexture())).setDisplay(YogaDisplay.NONE)
                .addEventListener(UIEvents.MOUSE_DOWN, event -> {
                    event.currentElement.startDrag(null, null);
                }).addEventListener(UIEvents.MOUSE_ENTER, event -> {
                    event.currentElement.style(style -> style.overlayTexture(ColorPattern.RED.borderTexture(1)));
                }).addEventListener(UIEvents.MOUSE_LEAVE, event -> {
                    event.currentElement.style(style -> style.overlayTexture(IGuiTexture.EMPTY));
                }).addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
                    var result = DraggingPoint(event, false);
                    result.x = Math.min(result.x, value.getSegments().get(selectedPoint - 1).p1.x);
                    if (selectedPoint > 0) {
                        value.getSegments().get(selectedPoint - 1).c1.set(result);
                        if (lockControlPoint && selectedPoint < value.getSegments().size()) {
                            value.getSegments().get(selectedPoint).c0
                                    .set(new Vector2f(value.getSegments().get(selectedPoint).p0).mul(2).sub(result));
                        }
                    }
                    updateControlPoints();
                    notifyListeners();
                });

        controlPoint2.layout(layout -> {
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setWidth(2);
            layout.setHeight(2);
            layout.setMargin(YogaEdge.LEFT, -1);
            layout.setMargin(YogaEdge.TOP, -1);
        }).style(style -> style.backgroundTexture(ColorPattern.GREEN.rectTexture())).setDisplay(YogaDisplay.NONE)
                .addEventListener(UIEvents.MOUSE_DOWN, event -> {
                    event.currentElement.startDrag(null, null);
                }).addEventListener(UIEvents.MOUSE_ENTER, event -> {
                    event.currentElement.style(style -> style.overlayTexture(ColorPattern.RED.borderTexture(1)));
                }).addEventListener(UIEvents.MOUSE_LEAVE, event -> {
                    event.currentElement.style(style -> style.overlayTexture(IGuiTexture.EMPTY));
                }).addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
                    var result = DraggingPoint(event, false);
                    result.x = Math.max(result.x, value.getSegments().get(selectedPoint).p0.x);
                    if (selectedPoint < value.getSegments().size()) {
                        value.getSegments().get(selectedPoint).c0.set(result);
                        if (lockControlPoint && selectedPoint - 1 >= 0) {
                            value.getSegments().get(selectedPoint - 1).c1
                                    .set(new Vector2f(value.getSegments().get(selectedPoint).p0).mul(2).sub(result));
                        }
                    }
                    updateControlPoints();
                    notifyListeners();
                });
    }

    private TreeBuilder.Menu createMenu(){
        var menu = TreeBuilder.Menu.start();
        menu.leaf(lockControlPoint ? Icons.CHECK_SPRITE : IGuiTexture.EMPTY, "editor.lock_control_points", () -> lockControlPoint = !lockControlPoint);
        if (selectedPoint != -1 && pointsUI.size() > 1) {
            menu.leaf("ldlib.gui.editor.menu.remove", () -> {
                if (selectedPoint >= 0 && selectedPoint < pointsUI.size()) {
                    var segments = value.getSegments();
                    if (selectedPoint == 0) {
                        segments.removeFirst();
                    } else if (selectedPoint < segments.size()) {
                        segments.get(selectedPoint - 1).p1.set(segments.get(selectedPoint).p1);
                        segments.get(selectedPoint - 1).c1.set(segments.get(selectedPoint).c0);
                        segments.remove(selectedPoint);
                    } else {
                        segments.removeLast();
                    }
                    notifyListeners();
                }
                selectedPoint = -1;
                refreshGraph();
            });
        }
        return menu;
    }

    private void onGraphMouseDown(UIEvent event) {
        if (event.button == 1) {
            var menu = createMenu();
            if (!menu.isEmpty()) {
                this.addChild(new Menu<>(createMenu().build(), TreeBuilder.Menu::uiProvider)
                        .setOnClose(graphView::focus)
                        .setHoverTextureProvider(TreeBuilder.Menu::hoverTextureProvider)
                        .setOnNodeClicked(TreeBuilder.Menu::handle)
                        .layout(layout -> {
                            layout.setPosition(YogaEdge.LEFT, event.x - this.getContentX());
                            layout.setPosition(YogaEdge.TOP, event.y - this.getContentY());
                        }));
            }
        }
    }

    private void onGraphDoubleClick(UIEvent event) {
        var x = (event.x - graphView.getContentX()) / graphView.getContentWidth();
        var y = value.getSegments().getFirst().p0.y;
        var found = x < value.getSegments().getFirst().p0.x;
        var index = 0;
        if (!found) {
            for (var curve : value.getSegments()) {
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
            y = value.getSegments().getLast().p1.y;
        }
        var position = getPointPosition(new Vector2f(x, y), graphView.getContentX(), graphView.getContentY(), graphView.getContentWidth(), graphView.getContentHeight());
        if (isMouseOver((int) (position.x - 2), (int) (position.y - 2), 4, 4, event.x, event.y)) {
            var segments = value.getSegments();
            if (index == 0) {
                var right = segments.getFirst().p0;
                var rightCP = segments.get(index).c0;
                segments.addFirst(new ExplicitCubicBezierCurve2(
                        new Vector2f(x, y),
                        new Vector2f(x + 0.1f, y),
                        new Vector2f(right.x + (right.x - rightCP.x), right.y + (right.y - rightCP.y)),
                        right));
            } else if (index > segments.size()) {
                var left = segments.getLast().p1;
                var leftCP = segments.getLast().c1;
                segments.add(new ExplicitCubicBezierCurve2(
                        left,
                        new Vector2f(left.x + (left.x - leftCP.x), left.y + (left.y - leftCP.y)),
                        new Vector2f(x - 0.1f, y),
                        new Vector2f(x, y)));
            } else {
                var curve = segments.get(index - 1);
                segments.add(index, new ExplicitCubicBezierCurve2(
                        new Vector2f(x, y),
                        new Vector2f(x + 0.1f, y),
                        new Vector2f(curve.c1),
                        new Vector2f(curve.p1)));
                curve.c1.set(x - 0.1f, y);
                curve.p1.set(x, y);
            }
            notifyListeners();
            selectedPoint = index;
            refreshGraph();
        }
    }

    private @NotNull Vector2f DraggingPoint(UIEvent event, boolean clamp) {
        var x = event.x - graphView.getContentX();
        var y = event.y - graphView.getContentY();
        var width = graphView.getContentWidth();
        var height = graphView.getContentHeight();
        var percentX = clamp ? Mth.clamp(x / width, 0f, 1f) : x / width;
        var percentY = clamp ? Mth.clamp(y / height, 0f, 1f) : y / height;
        event.currentElement.layout(layout -> {
            layout.setPositionPercent(YogaEdge.LEFT, percentX * 100);
            layout.setPositionPercent(YogaEdge.TOP, percentY * 100);
        });
        return new Vector2f(percentX, 1 - percentY);
    }

    public void refreshGraph() {
        pointsUI.forEach(graphView::removeChild);
        pointsUI.clear();
        for (int i = 0; i < value.getSegments().size(); i++) {
            var curve = value.getSegments().get(i);
            if (i == 0) {
                var ui = createPointUI(0, curve.p0);
                graphView.addChild(ui);
                pointsUI.add(ui);
            }
            var ui = createPointUI(i + 1, curve.p1);
            graphView.addChild(ui);
            pointsUI.add(ui);
        }
        updateControlPoints();
    }

    private UIElement createPointUI(int index, Vector2f point) {
        return new UIElement().layout(layout -> {
            layout.setPositionType(YogaPositionType.ABSOLUTE);
            layout.setWidth(4);
            layout.setHeight(4);
            layout.setMargin(YogaEdge.LEFT, -2);
            layout.setMargin(YogaEdge.TOP, -2);
            layout.setPositionPercent(YogaEdge.LEFT, point.x * 100);
            layout.setPositionPercent(YogaEdge.TOP, (1 - point.y) * 100);
        }).style(style -> style.backgroundTexture(ColorPattern.LIGHT_GRAY.rectTexture()))
                .addEventListener(UIEvents.MOUSE_DOWN, event -> {
                    setSelected(index);
                    event.currentElement.startDrag(null, null);
                }).addEventListener(UIEvents.MOUSE_ENTER, event -> {
                    event.currentElement.style(style -> style.overlayTexture(ColorPattern.RED.borderTexture(1)));
                }).addEventListener(UIEvents.MOUSE_LEAVE, event -> {
                    event.currentElement.style(style -> style.overlayTexture(IGuiTexture.EMPTY));
                }).addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
                    var result = DraggingPoint(event, true);
                    var segments = value.getSegments();
                    if (index < segments.size()) {
                        var offset = new Vector2f(result.x - segments.get(index).p0.x, result.y - segments.get(index).p0.y);
                        segments.get(index).p0.set(result);
                        segments.get(index).c0.add(offset);
                    }
                    if (index > 0) {
                        var offset = new Vector2f(result.x - segments.get(index - 1).p1.x, result.y - segments.get(index - 1).p1.y);
                        segments.get(index - 1).p1.set(result);
                        segments.get(index - 1).c1.add(offset);
                    }
                    updateControlPoints();
                    notifyListeners();
                });
    }

    public void setSelected(int index) {
        if (selectedPoint == index) return;
        if (selectedPoint >= 0 && selectedPoint < pointsUI.size()) {
            pointsUI.get(selectedPoint).style(style -> style.backgroundTexture(ColorPattern.LIGHT_GRAY.rectTexture()));
        }
        if (index >= 0 && index < pointsUI.size()) {
            selectedPoint = index;
            pointsUI.get(selectedPoint).style(style -> style.backgroundTexture(ColorPattern.ORANGE.rectTexture()));
        } else {
            selectedPoint = -1;
        }
        // set control point
        updateControlPoints();
    }

    private void updateControlPoints() {
        if (selectedPoint >= 0 && selectedPoint < pointsUI.size()) {
            var segments = value.getSegments();
            if (selectedPoint > 0) {
                controlPoint1.layout(layout -> {
                    layout.setPositionPercent(YogaEdge.LEFT, segments.get(selectedPoint - 1).c1.x * 100);
                    layout.setPositionPercent(YogaEdge.TOP, (1 - segments.get(selectedPoint - 1).c1.y) * 100);
                }).setDisplay(YogaDisplay.FLEX);
            } else {
                controlPoint1.setDisplay(YogaDisplay.NONE);
            }
            if (selectedPoint < segments.size()) {
                controlPoint2.layout(layout -> {
                    layout.setPositionPercent(YogaEdge.LEFT, segments.get(selectedPoint).c0.x * 100);
                    layout.setPositionPercent(YogaEdge.TOP, (1 - segments.get(selectedPoint).c0.y) * 100);
                }).setDisplay(YogaDisplay.FLEX);
            } else {
                controlPoint2.setDisplay(YogaDisplay.NONE);
            }
        } else {
            controlPoint1.setDisplay(YogaDisplay.NONE);
            controlPoint2.setDisplay(YogaDisplay.NONE);
        }
    }

    public CurveGraph setOnCurveChangeListener(Consumer<ECBCurves> listener) {
        registerValueListener(listener);
        return this;
    }

    @Override
    public CurveGraph setValue(ECBCurves value, boolean notify) {
        if (this.value == value) return this;
        this.value = value;
        if (notify) {
            notifyListeners();
        }
        refreshGraph();
        return this;
    }

    private Vec2 getPointPosition(Vector2f coord, float x, float y, float width, float height) {
        return new Vec2(x + width * coord.x, y + height * (1 - coord.y));
    }

    private void drawGraphView(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTick) {
        DrawerHelper.drawSolidRect(graphics, x, y, width, height, ColorPattern.BLACK.color);
        for (int i = 0; i < 6; i++) {
            DrawerHelper.drawSolidRect(graphics, x + i * width / 6, y, 1, height, ColorPattern.T_GRAY.color);
        }
        for (int i = 0; i < 6; i++) {
            DrawerHelper.drawSolidRect(graphics, x, y + i * height / 6, width, 1, ColorPattern.T_GRAY.color);
        }
        var curves = value.getSegments();
        // render lines
        var points = curves.stream().flatMap(curve -> curve.getPoints(100).stream().map(coord -> getPointPosition(coord, x, y, width, height)).toList().stream()).collect(Collectors.toList());
        DrawerHelper.drawLines(graphics, points, -1, -1, 0.5f);
        Collections.reverse(points);
        DrawerHelper.drawLines(graphics, points, -1, -1, 0.5f);
        // render outer lines
        if (curves.getFirst().p0.x > 0) {
            DrawerHelper.drawLines(graphics, List.of(getPointPosition(new Vector2f(0, curves.getFirst().p0.y), x, y, width, height), getPointPosition(curves.getFirst().p0, x, y, width, height)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        if (curves.getLast().p1.x < 1) {
            DrawerHelper.drawLines(graphics, List.of(getPointPosition(new Vector2f(1, curves.getLast().p1.y), x, y, width, height), getPointPosition(curves.getLast().p1, x, y, width, height)), ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        // render control lines
        if (selectedPoint >= 0) {
            if (selectedPoint > 0) { //render left
                var curve = curves.get(selectedPoint - 1);
                DrawerHelper.drawLines(graphics, List.of(getPointPosition(curve.c1, x, y, width, height), getPointPosition(curve.p1, x, y, width, height)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
            }
            if (selectedPoint < curves.size()) { //render right
                var curve = curves.get(selectedPoint);
                DrawerHelper.drawLines(graphics, List.of(getPointPosition(curve.c0, x, y, width, height), getPointPosition(curve.p0, x, y, width, height)), ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
            }
        }
    }
}
