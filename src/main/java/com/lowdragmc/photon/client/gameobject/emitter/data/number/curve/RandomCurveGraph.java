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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import oshi.util.tuples.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

public class RandomCurveGraph extends BindableUIElement<Pair<ECBCurves, ECBCurves>> {
    public final UIElement graphView = new UIElement();

    @Getter
    protected Pair<ECBCurves, ECBCurves> value = new Pair<>(new ECBCurves(), new ECBCurves());
    protected final List<UIElement> pointsUIA = new ArrayList<>();
    protected final List<UIElement> pointsUIB = new ArrayList<>();
    protected boolean lockControlPoint = true;

    // runtime
    protected int selectedPointA = -1;
    protected int selectedPointB = -1;
    protected UIElement controlPoint1A = new UIElement();
    protected UIElement controlPoint1B = new UIElement();
    protected UIElement controlPoint2A = new UIElement();
    protected UIElement controlPoint2B = new UIElement();

    public RandomCurveGraph() {
        refreshGraph();
        getLayout().setFlexDirection(YogaFlexDirection.ROW);

        graphView.layout(layout -> {
            layout.setHeightPercent(100);
            layout.setFlex(1);
        }).style(style -> style.backgroundTexture(this::drawGraphView))
                .addEventListener(UIEvents.MOUSE_DOWN, this::onGraphMouseDown)
                .addEventListener(UIEvents.DOUBLE_CLICK, this::onGraphDoubleClick)
                .addChildren(controlPoint1A, controlPoint2A, controlPoint1B, controlPoint2B);

        addChildren(graphView);

        createControlPoint(controlPoint1A);
        controlPoint1A.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
            var result = DraggingPoint(event, false);
            result.x = Math.min(result.x, value.getA().getSegments().get(selectedPointA - 1).p1.x);
            if (selectedPointA > 0) {
                value.getA().getSegments().get(selectedPointA - 1).c1.set(result);
                if (lockControlPoint && selectedPointA < value.getA().getSegments().size()) {
                    value.getA().getSegments().get(selectedPointA).c0
                            .set(new Vector2f(value.getA().getSegments().get(selectedPointA).p0).mul(2).sub(result));
                }
            }
            updateControlPoints();
            notifyListeners();
        });
        createControlPoint(controlPoint1B);
        controlPoint1B.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
            var result = DraggingPoint(event, false);
            result.x = Math.min(result.x, value.getB().getSegments().get(selectedPointB - 1).p1.x);
            if (selectedPointB > 0) {
                value.getB().getSegments().get(selectedPointB - 1).c1.set(result);
                if (lockControlPoint && selectedPointB < value.getB().getSegments().size()) {
                    value.getB().getSegments().get(selectedPointB).c0
                            .set(new Vector2f(value.getB().getSegments().get(selectedPointB).p0).mul(2).sub(result));
                }
            }
            updateControlPoints();
            notifyListeners();
        });

        createControlPoint(controlPoint2A);
        controlPoint2A.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
            var result = DraggingPoint(event, false);
            result.x = Math.max(result.x, value.getA().getSegments().get(selectedPointA).p0.x);
            if (selectedPointA < value.getA().getSegments().size()) {
                value.getA().getSegments().get(selectedPointA).c0.set(result);
                if (lockControlPoint && selectedPointA - 1 >= 0) {
                    value.getA().getSegments().get(selectedPointA - 1).c1
                            .set(new Vector2f(value.getA().getSegments().get(selectedPointA).p0).mul(2).sub(result));
                }
            }
            updateControlPoints();
            notifyListeners();
        });
        createControlPoint(controlPoint2B);
        controlPoint2B.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
            var result = DraggingPoint(event, false);
            result.x = Math.max(result.x, value.getB().getSegments().get(selectedPointB).p0.x);
            if (selectedPointB < value.getB().getSegments().size()) {
                value.getB().getSegments().get(selectedPointB).c0.set(result);
                if (lockControlPoint && selectedPointB - 1 >= 0) {
                    value.getB().getSegments().get(selectedPointB - 1).c1
                            .set(new Vector2f(value.getB().getSegments().get(selectedPointB).p0).mul(2).sub(result));
                }
            }
            updateControlPoints();
            notifyListeners();
        });

    }

    private void createControlPoint(UIElement controlPoint1A) {
        controlPoint1A.layout(layout -> {
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
                });
    }

    private TreeBuilder.Menu createMenu(){
        var menu = TreeBuilder.Menu.start();
        menu.leaf(lockControlPoint ? Icons.CHECK_SPRITE : IGuiTexture.EMPTY, "editor.lock_control_points", () -> lockControlPoint = !lockControlPoint);
        if (selectedPointA != -1 && pointsUIA.size() > 1 || selectedPointB != -1 && pointsUIB.size() > 1) {
            menu.leaf("ldlib.gui.editor.menu.remove", () -> {
                removePoint(selectedPointA, pointsUIA, value.getA());
                removePoint(selectedPointB, pointsUIB, value.getB());
                selectedPointA = -1;
                selectedPointB = -1;
                refreshGraph();
            });
        }
        return menu;
    }

    private void removePoint(int selectedPoint, List<UIElement> pointsUI, ECBCurves value) {
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
        var index = tryToAddNewNode(event, value.getA());
        if (index == -1) {
            index = tryToAddNewNode(event, value.getB());
        }
        if (index != -1) {
            refreshGraph();
            notifyListeners();
        }
    }

    private int tryToAddNewNode(UIEvent event, ECBCurves value) {
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
            return index;
        }
        return -1;
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
        refreshGraph(pointsUIA, value.getA(), this::setSelectedA);
        refreshGraph(pointsUIB, value.getB(), this::setSelectedB);
        updateControlPoints();
    }


    public void refreshGraph(List<UIElement> pointsUI, ECBCurves value, IntConsumer setter) {
        pointsUI.forEach(graphView::removeChild);
        pointsUI.clear();
        for (int i = 0; i < value.getSegments().size(); i++) {
            var curve = value.getSegments().get(i);
            if (i == 0) {
                var ui = createPointUI(value, 0, curve.p0, setter);
                graphView.addChild(ui);
                pointsUI.add(ui);
            }
            var ui = createPointUI(value, i + 1, curve.p1, setter);
            graphView.addChild(ui);
            pointsUI.add(ui);
        }
    }

    private void updateControlPoints() {
        updateControlPoints(selectedPointA, pointsUIA, value.getA(), controlPoint1A, controlPoint2A);
        updateControlPoints(selectedPointB, pointsUIB, value.getB(), controlPoint1B, controlPoint2B);
    }

    private void updateControlPoints(int selectedPoint, List<UIElement> pointsUI, ECBCurves value, UIElement controlPoint1, UIElement controlPoint2) {
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

    public void setSelectedA(int index) {
        if (selectedPointA == index) return;
        selectedPointB = -1;
        if (selectedPointA >= 0 && selectedPointA < pointsUIA.size()) {
            pointsUIA.get(selectedPointA).style(style -> style.backgroundTexture(ColorPattern.LIGHT_GRAY.rectTexture()));
        }
        if (index >= 0 && index < pointsUIA.size()) {
            selectedPointA = index;
            pointsUIA.get(selectedPointA).style(style -> style.backgroundTexture(ColorPattern.ORANGE.rectTexture()));
        } else {
            selectedPointA = -1;
        }
        // set control point
        updateControlPoints();
    }

    public void setSelectedB(int index) {
        if (selectedPointB == index) return;
        selectedPointA = -1;
        if (selectedPointB >= 0 && selectedPointB < pointsUIB.size()) {
            pointsUIB.get(selectedPointB).style(style -> style.backgroundTexture(ColorPattern.LIGHT_GRAY.rectTexture()));
        }
        if (index >= 0 && index < pointsUIB.size()) {
            selectedPointB = index;
            pointsUIB.get(selectedPointB).style(style -> style.backgroundTexture(ColorPattern.ORANGE.rectTexture()));
        } else {
            selectedPointB = -1;
        }
        // set control point
        updateControlPoints();
    }


    private UIElement createPointUI(ECBCurves value, int index, Vector2f point, IntConsumer setter) {
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
                    setter.accept(index);
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


    public RandomCurveGraph setOnCurveChangeListener(Consumer<Pair<ECBCurves, ECBCurves>> listener) {
        registerValueListener(listener);
        return this;
    }

    @Override
    public RandomCurveGraph setValue(Pair<ECBCurves, ECBCurves> value, boolean notify) {
        if (this.value == value) return this;
        if (this.value.getA() == value.getA() && this.value.getB() == value.getB()) return this;
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

        // render area
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        var matrix = graphics.pose().last().pose();
        var count = width * 2;
        for (int i = 0; i < count; i++) {
            float x0 = i * 1f / count;
            float x1 = (i + 1) * 1f / count;

            var p0 = getPointPosition(new Vector2f(x0, value.getA().getCurveY(x0)), x, y, width, height);
            var p1 = getPointPosition(new Vector2f(x1, value.getA().getCurveY(x1)), x, y, width, height);
            var p2 = getPointPosition(new Vector2f(x1, value.getB().getCurveY(x1)), x, y, width, height);
            var p3 = getPointPosition(new Vector2f(x0, value.getB().getCurveY(x0)), x, y, width, height);

            buffer.addVertex(matrix, p0.x, p0.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p1.x, p1.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p2.x, p2.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p3.x, p3.y, 0.0f).setColor(ColorPattern.T_WHITE.color);

            buffer.addVertex(matrix, p3.x, p3.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p2.x, p2.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p1.x, p1.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p0.x, p0.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
        }

        BufferUploader.drawWithShader(buffer.buildOrThrow());

        drawGraphView(selectedPointA, value.getA(), graphics, x, y, width, height);
        drawGraphView(selectedPointB, value.getB(), graphics, x, y, width, height);
    }

    private void drawGraphView(int selectedPoint, ECBCurves value, GuiGraphics graphics, float x, float y, float width, float height) {
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
