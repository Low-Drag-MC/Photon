package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableUIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Shared implementation of the bezier curve editors ({@link CurveGraph} / {@link RandomCurveGraph}):
 * each keypoint is a child element whose hit area is larger than its drawn square, and the two tangent
 * handles are floating elements that follow the selected point (the timeline curve-box pattern). The
 * graph element itself only handles empty-space interactions (double-click add, right-click menu) and
 * draws the curves plus a coordinate readout while a point/handle is hovered or dragged.
 */
public abstract class AbstractCurveGraph<T> extends BindableUIElement<T> {
    /** Size (px) of a point/handle hit element; the visual square is drawn centred inside. */
    protected static final float POINT_HIT_SIZE = 8;
    /** Vertical pixel tolerance to the curve within which a double-click inserts a new point. */
    protected static final float CURVE_ADD_PX = 4;
    /** Minimum x gap kept between neighbouring points so adjacent points never coincide (a 0-width
     *  segment renders as a vertical jump; an overshoot would render inverted/crossed-over). */
    protected static final float MIN_POINT_GAP = 0.001f;
    /** Interior size (px) of the fixed hover ring; an active marker body grows to exactly fill it
     *  (the {@link ColorPattern#borderTexture} border renders OUTSIDE the given rect). */
    protected static final float RING_SIZE = 5;
    private static final IGuiTexture HOVER_RING = ColorPattern.RED.borderTexture(1);

    public final UIElement graphView = new UIElement();
    protected boolean lockControlPoint = true;

    // primary selection: series index + point index (drives the tangent handles; the random graph's two
    // curves are series 0 / 1, so a single primary keeps the curves' selections mutually exclusive)
    protected int selectedSeries = -1;
    protected int selectedPoint = -1;
    /** Multi-selected points encoded as {@code series << 32 | index} (marquee / shift-click selection). */
    protected final Set<Long> selectedPoints = new HashSet<>();
    // per-series point elements, rebuilt by refreshGraph
    protected final List<List<UIElement>> pointElements = new ArrayList<>();
    // the two tangent handles float to the selected point (in = prev segment's c1, out = next segment's c0)
    protected final UIElement inHandle;
    protected final UIElement outHandle;

    // drag session, captured at mouse-down so a mid-drag selection change cannot retarget it
    protected int dragSeries = -1, dragPoint = -1, dragKind = -1; // kind: 0 point, 1 in-handle, 2 out-handle
    // group drag (anchor point is part of a multi-selection): original coord of every selected point
    protected boolean groupDrag;
    protected final Map<Long, Vector2f> dragOrigins = new HashMap<>();
    // point marquee (rubber-band) on empty graph space
    protected boolean marquee, marqueeAdditive;
    protected float mqX0, mqY0, mqX1, mqY1;

    /** Formats the coordinate readout; input is the curve-space (x, y) where both spans are [0, 1]. */
    @Setter
    protected BiFunction<Float, Float, String> coordFormatter = (x, y) -> "(%.2f, %.2f)".formatted(x, y);

    protected AbstractCurveGraph() {
        getLayout().flexDirection(FlexDirection.ROW);
        inHandle = createHandleElement(1);
        outHandle = createHandleElement(2);
        graphView.setFocusable(true);
        graphView.layout(layout -> {
            layout.heightPercent(100);
            layout.flex(1);
        }).style(style -> style
                        .backgroundTexture(com.lowdragmc.lowdraglib2.gui.texture.GuiTexture.of((ctx, gx, gy, gw, gh) -> drawGraph(ctx, ctx.mouseX, ctx.mouseY, gx, gy, gw, gh, ctx.partialTick)))
                        .overlayTexture(com.lowdragmc.lowdraglib2.gui.texture.GuiTexture.of((ctx, gx, gy, gw, gh) -> drawGraphOverlay(ctx, ctx.mouseX, ctx.mouseY, gx, gy, gw, gh, ctx.partialTick))))
                .addEventListener(UIEvents.MOUSE_DOWN, this::onGraphMouseDown)
                .addEventListener(UIEvents.DOUBLE_CLICK, this::onGraphDoubleClick)
                .addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
                    if (marquee) {
                        mqX1 = e.x;
                        mqY1 = e.y;
                    }
                })
                .addEventListener(UIEvents.DRAG_END, e -> {
                    if (marquee) finishMarquee();
                })
                .addChildren(inHandle, outHandle);
        addChild(graphView);
        // Delete removes the selection; the graph focuses itself on any press so the key routes here
        addEventListener(UIEvents.KEY_DOWN, e -> {
            if (e.keyCode == GLFW.GLFW_KEY_DELETE && !selectedPoints.isEmpty()) {
                deleteSelection();
                e.stopPropagation();
            }
        });
    }

    protected static long encodePoint(int series, int index) {
        return ((long) series << 32) | (index & 0xffffffffL);
    }

    protected static int pointSeries(long id) {
        return (int) (id >>> 32);
    }

    protected static int pointIndex(long id) {
        return (int) id;
    }

    /** Shift or Ctrl held → additive multi-selection (toggle membership), matching the timeline. */
    private static boolean isAdditive(UIEvent e) {
        return e.isShiftDown() || e.isCtrlDown();
    }

    /** The edited curves, one per series. Subclasses call {@link #refreshGraph()} after replacing them. */
    protected abstract List<ECBCurves> allCurves();

    /** Extra background drawn between the grid and the curve lines (the random graph fills the A-B area). */
    protected void drawArea(GUIContext graphics, float x, float y, float width, float height) {
    }

    // ------------------------------------------------------------------ elements

    /** Rebuild every point element from the model and float the handles to the (still valid) selection. */
    public void refreshGraph() {
        for (var els : pointElements) els.forEach(graphView::removeChild);
        pointElements.clear();
        // re-add the handles last so they stay on top of the points for hit-testing
        graphView.removeChild(inHandle);
        graphView.removeChild(outHandle);
        var curvesList = allCurves();
        for (int s = 0; s < curvesList.size(); s++) {
            var els = new ArrayList<UIElement>();
            var segments = curvesList.get(s).getSegments();
            if (!segments.isEmpty()) {
                for (int i = 0; i <= segments.size(); i++) {
                    var el = createPointElement(s, i);
                    graphView.addChild(el);
                    els.add(el);
                }
            }
            pointElements.add(els);
        }
        graphView.addChildren(inHandle, outHandle);
        selectedPoints.removeIf(id -> pointSeries(id) >= pointElements.size()
                || pointIndex(id) < 0 || pointIndex(id) >= pointElements.get(pointSeries(id)).size());
        if (selectedSeries >= 0 && (selectedSeries >= pointElements.size()
                || selectedPoint < 0 || selectedPoint >= pointElements.get(selectedSeries).size())) {
            selectedSeries = -1;
            selectedPoint = -1;
        }
        repositionAll();
    }

    /** Re-lay-out every point/handle element from the model (positions are percent-based, so container
     *  resizes track automatically and this only needs to run after a model mutation). */
    protected void repositionAll() {
        var curvesList = allCurves();
        for (int s = 0; s < pointElements.size() && s < curvesList.size(); s++) {
            var curves = curvesList.get(s);
            var els = pointElements.get(s);
            for (int i = 0; i < els.size(); i++) {
                var p = pointCoord(curves, i);
                els.get(i).layout(layout -> {
                    layout.leftPercent(p.x() * 100);
                    layout.topPercent((1 - p.y()) * 100);
                });
            }
        }
        repositionHandles();
    }

    /** The point at {@code index} of a series: point 0 is the first segment's p0, point i is segment i-1's p1. */
    protected org.joml.Vector2fc pointCoord(ECBCurves curves, int index) {
        var segments = curves.getSegments();
        return index == 0 ? segments.getFirst().p0 : segments.get(index - 1).p1;
    }

    private UIElement createPointElement(int series, int index) {
        var el = new UIElement();
        el.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.width(POINT_HIT_SIZE);
            layout.height(POINT_HIT_SIZE);
            layout.marginLeft(-POINT_HIT_SIZE / 2);
            layout.marginTop(-POINT_HIT_SIZE / 2);
        }).style(style -> style.overlayTexture(com.lowdragmc.lowdraglib2.gui.texture.GuiTexture.of((graphics, x, y, w, h) -> {
            float mx = graphics.mouseX, my = graphics.mouseY, pt = graphics.partialTick;
            var selected = selectedPoints.contains(encodePoint(series, index));
            // only the element that would actually receive the click (or the current drag anchor) reacts
            var active = dragKind < 0 ? el.isHover()
                    : dragKind == 0 && dragSeries == series && dragPoint == index;
            drawMarker(graphics, x + w / 2, y + h / 2, 4,
                    (selected ? ColorPattern.ORANGE : ColorPattern.LIGHT_GRAY).color, active, mx, my, pt);
        })));
        el.addEventListener(UIEvents.MOUSE_DOWN, e -> onPointMouseDown(e, series, index));
        // swallow double-clicks so the graph's add-point handler can't duplicate an existing point
        el.addEventListener(UIEvents.DOUBLE_CLICK, UIEvent::stopPropagation);
        el.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::onDragUpdate);
        el.addEventListener(UIEvents.DRAG_END, e -> endDrag());
        return el;
    }

    private void onPointMouseDown(UIEvent e, int series, int index) {
        var id = encodePoint(series, index);
        if (e.button == 1) {
            // right-click on a member of a multi-selection removes the whole selection, else just this point
            if (selectedPoints.size() > 1 && selectedPoints.contains(id)) {
                deleteSelection();
            } else {
                removePoint(series, index);
            }
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        graphView.focus(); // route the Delete key here
        if (isAdditive(e)) { // shift/ctrl toggles membership, no drag
            if (!selectedPoints.remove(id)) selectedPoints.add(id);
            syncPrimaryFromSet();
            repositionHandles();
            e.stopPropagation();
            return;
        }
        if (!selectedPoints.contains(id)) {
            setSelected(series, index);
        } else {
            // pressing a member keeps the multi-selection and makes it the drag anchor
            selectedSeries = series;
            selectedPoint = index;
            repositionHandles();
        }
        beginDrag(series, index, 0);
        e.currentElement.startDrag(null, null);
        e.stopPropagation();
    }

    private UIElement createHandleElement(int kind) {
        var el = new UIElement();
        el.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.width(POINT_HIT_SIZE);
            layout.height(POINT_HIT_SIZE);
            layout.marginLeft(-POINT_HIT_SIZE / 2);
            layout.marginTop(-POINT_HIT_SIZE / 2);
        }).setDisplay(false).style(style -> style.overlayTexture(com.lowdragmc.lowdraglib2.gui.texture.GuiTexture.of((graphics, x, y, w, h) -> {
            float mx = graphics.mouseX, my = graphics.mouseY, pt = graphics.partialTick;
            var active = dragKind < 0 ? el.isHover() : dragKind == kind;
            drawMarker(graphics, x + w / 2, y + h / 2, 3, ColorPattern.GREEN.color, active, mx, my, pt);
        })));
        el.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button != 0 || selectedSeries < 0) return;
            graphView.focus(); // route the Delete key here
            beginDrag(selectedSeries, selectedPoint, kind);
            e.currentElement.startDrag(null, null);
            e.stopPropagation();
        });
        el.addEventListener(UIEvents.DOUBLE_CLICK, UIEvent::stopPropagation);
        el.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::onDragUpdate);
        el.addEventListener(UIEvents.DRAG_END, e -> endDrag());
        return el;
    }

    /** A point/handle square centred at (cx, cy); when active (hovered / dragged) the body grows to fill
     *  the fixed-size highlight ring — the ring itself never scales. */
    private static void drawMarker(GUIContext graphics, float cx, float cy, float size, int color,
                                   boolean active, float mx, float my, float pt) {
        var drawSize = active ? RING_SIZE : size;
        DrawerHelperClient.drawSolidRect(graphics, cx - drawSize / 2, cy - drawSize / 2, drawSize, drawSize, color);
        if (active) {
            graphics.drawTexture(HOVER_RING, cx - RING_SIZE / 2, cy - RING_SIZE / 2, RING_SIZE, RING_SIZE);
        }
    }

    public void setSelected(int series, int index) {
        selectedSeries = series;
        selectedPoint = index;
        selectedPoints.clear();
        if (series >= 0 && index >= 0) selectedPoints.add(encodePoint(series, index));
        repositionHandles();
    }

    protected void clearSelection() {
        selectedSeries = -1;
        selectedPoint = -1;
        selectedPoints.clear();
        repositionHandles();
    }

    /** Keep the primary (handle-bearing) selection in sync with the multi-set: exactly one member → it,
     *  otherwise none (the tangent handles only apply to a single selected point, like the timeline). */
    protected void syncPrimaryFromSet() {
        if (selectedPoints.size() == 1) {
            var id = selectedPoints.iterator().next();
            selectedSeries = pointSeries(id);
            selectedPoint = pointIndex(id);
        } else {
            selectedSeries = -1;
            selectedPoint = -1;
        }
    }

    protected void repositionHandles() {
        positionHandle(inHandle, handleCoord(1));
        positionHandle(outHandle, handleCoord(2));
    }

    /** The selected point's tangent handle coordinate ({@code kind}: 1 = in, 2 = out), or null if hidden
     *  (no / multiple selection: handles only apply to a single selected point). */
    @Nullable
    protected org.joml.Vector2fc handleCoord(int kind) {
        var curvesList = allCurves();
        if (selectedPoints.size() > 1) return null;
        if (selectedSeries < 0 || selectedSeries >= curvesList.size()) return null;
        var segments = curvesList.get(selectedSeries).getSegments();
        if (selectedPoint < 0 || selectedPoint > segments.size()) return null;
        if (kind == 1) return selectedPoint > 0 ? segments.get(selectedPoint - 1).c1 : null;
        return selectedPoint < segments.size() ? segments.get(selectedPoint).c0 : null;
    }

    private void positionHandle(UIElement el, @Nullable org.joml.Vector2fc pos) {
        if (pos == null) {
            el.setDisplay(false);
            return;
        }
        el.setDisplay(true);
        el.layout(layout -> {
            layout.leftPercent(pos.x() * 100);
            layout.topPercent((1 - pos.y()) * 100);
        });
    }

    // ------------------------------------------------------------------ dragging

    protected void beginDrag(int series, int point, int kind) {
        dragSeries = series;
        dragPoint = point;
        dragKind = kind;
        groupDrag = kind == 0 && selectedPoints.size() > 1 && selectedPoints.contains(encodePoint(series, point));
        dragOrigins.clear();
        if (groupDrag) {
            var curvesList = allCurves();
            for (var id : selectedPoints) {
                var s = pointSeries(id);
                var i = pointIndex(id);
                if (s < curvesList.size() && !curvesList.get(s).getSegments().isEmpty()
                        && i <= curvesList.get(s).getSegments().size()) {
                    dragOrigins.put(id, new Vector2f(pointCoord(curvesList.get(s), i)));
                }
            }
        }
    }

    protected void endDrag() {
        dragSeries = -1;
        dragPoint = -1;
        dragKind = -1;
        groupDrag = false;
        dragOrigins.clear();
    }

    protected void onDragUpdate(UIEvent event) {
        var curvesList = allCurves();
        if (dragKind < 0 || dragSeries < 0 || dragSeries >= curvesList.size()) return;
        var width = graphView.getContentWidth();
        var height = graphView.getContentHeight();
        if (width <= 0 || height <= 0) return;
        var px = (event.x - graphView.getContentX()) / width;
        var py = 1 - (event.y - graphView.getContentY()) / height;
        var segments = curvesList.get(dragSeries).getSegments();
        if (groupDrag) { // only ever set for a point (kind 0) drag
            dragMoveGroup(Mth.clamp(px, 0, 1), Mth.clamp(py, 0, 1));
        } else if (dragKind == 0) {
            dragMovePoint(segments, Mth.clamp(px, 0, 1), Mth.clamp(py, 0, 1));
        } else {
            dragMoveHandle(segments, px, py);
        }
        repositionAll();
        notifyListeners();
        event.stopPropagation();
    }

    private void dragMovePoint(List<ExplicitCubicBezierCurve2> segments, float px, float py) {
        var index = dragPoint;
        if (index < 0 || index > segments.size()) return;
        var lo = index > 0 ? segments.get(index - 1).p0.x() + MIN_POINT_GAP : 0f;
        var hi = index < segments.size() ? segments.get(index).p1.x() - MIN_POINT_GAP : 1f;
        // max-of-min, not clamp: when the neighbours are closer than 2 gaps (lo > hi) the lower bound wins
        movePointTo(segments, index, Math.max(lo, Math.min(hi, px)), py);
    }

    /** Move every selected point by the same delta relative to its drag origin, with the x delta clamped so
     *  the group stays ordered against its non-selected neighbours (and inside [0, 1]); the y is clamped per
     *  point. Matches the timeline's group keyframe drag. */
    private void dragMoveGroup(float px, float py) {
        var anchorOrig = dragOrigins.get(encodePoint(dragSeries, dragPoint));
        if (anchorOrig == null) return;
        var curvesList = allCurves();
        var dx = px - anchorOrig.x;
        var dy = py - anchorOrig.y;
        float lo = -Float.MAX_VALUE, hi = Float.MAX_VALUE;
        for (var entry : dragOrigins.entrySet()) {
            var id = entry.getKey();
            var orig = entry.getValue();
            var s = pointSeries(id);
            var i = pointIndex(id);
            if (s >= curvesList.size()) continue;
            var segments = curvesList.get(s).getSegments();
            if (i > segments.size()) continue;
            lo = Math.max(lo, -orig.x);
            hi = Math.min(hi, 1 - orig.x);
            if (i > 0 && !dragOrigins.containsKey(encodePoint(s, i - 1))) {
                lo = Math.max(lo, segments.get(i - 1).p0.x() + MIN_POINT_GAP - orig.x);
            }
            if (i < segments.size() && !dragOrigins.containsKey(encodePoint(s, i + 1))) {
                hi = Math.min(hi, segments.get(i).p1.x() - MIN_POINT_GAP - orig.x);
            }
        }
        dx = lo <= hi ? Mth.clamp(dx, lo, hi) : lo;
        for (var entry : dragOrigins.entrySet()) {
            var id = entry.getKey();
            var orig = entry.getValue();
            var s = pointSeries(id);
            if (s >= curvesList.size()) continue;
            var segments = curvesList.get(s).getSegments();
            movePointTo(segments, pointIndex(id), orig.x + dx, Mth.clamp(orig.y + dy, 0, 1));
        }
    }

    private void movePointTo(List<ExplicitCubicBezierCurve2> segments, int index, float tx, float ty) {
        if (index < 0 || index > segments.size()) return;
        var target = new Vector2f(tx, ty);
        if (index < segments.size()) {
            var seg = segments.get(index);
            var offset = new Vector2f(target).sub(seg.p0);
            seg.p0 = new Vector2f(target);
            seg.c0 = new Vector2f(seg.c0).add(offset);
        }
        if (index > 0) {
            var seg = segments.get(index - 1);
            var offset = new Vector2f(target).sub(seg.p1);
            seg.p1 = new Vector2f(target);
            seg.c1 = new Vector2f(seg.c1).add(offset);
        }
    }

    private void dragMoveHandle(List<ExplicitCubicBezierCurve2> segments, float px, float py) {
        var index = dragPoint;
        if (index < 0 || index > segments.size()) return;
        if (dragKind == 1) { // in-handle: the previous segment's c1
            if (index <= 0) return;
            var seg = segments.get(index - 1);
            seg.c1 = new Vector2f(clampHandleX(seg, px), py);
            if (lockControlPoint && index < segments.size()) {
                var next = segments.get(index);
                var mirrored = new Vector2f(next.p0).mul(2).sub(seg.c1);
                next.c0 = new Vector2f(clampHandleX(next, mirrored.x), mirrored.y);
            }
        } else { // out-handle: the next segment's c0
            if (index >= segments.size()) return;
            var seg = segments.get(index);
            seg.c0 = new Vector2f(clampHandleX(seg, px), py);
            if (lockControlPoint && index > 0) {
                var prev = segments.get(index - 1);
                var mirrored = new Vector2f(prev.p1).mul(2).sub(seg.c0);
                prev.c1 = new Vector2f(clampHandleX(prev, mirrored.x), mirrored.y);
            }
        }
    }

    /** Clamp a control x into its segment's x span so the segment stays an explicit y = f(x) curve. */
    private static float clampHandleX(ExplicitCubicBezierCurve2 seg, float x) {
        return Mth.clamp(x, seg.p0.x(), seg.p1.x());
    }

    // ------------------------------------------------------------------ add / remove

    /** Whether removing point {@code index} keeps the series valid (at least one segment must remain). */
    protected boolean canRemove(int series, int index) {
        var curvesList = allCurves();
        if (series < 0 || series >= curvesList.size()) return false;
        var segments = curvesList.get(series).getSegments();
        if (index < 0 || index > segments.size() || segments.isEmpty()) return false;
        return (index > 0 && index < segments.size()) || segments.size() > 1;
    }

    protected void removePoint(int series, int index) {
        if (!canRemove(series, index)) return;
        removeRaw(series, index);
        clearSelection();
        endDrag();
        refreshGraph();
        notifyListeners();
    }

    /** Splice point {@code index} out of its series without refresh/notify (caller batches those). */
    private void removeRaw(int series, int index) {
        var segments = allCurves().get(series).getSegments();
        if (index == 0) {
            segments.removeFirst();
        } else if (index < segments.size()) {
            segments.get(index - 1).p1 = new Vector2f(segments.get(index).p1);
            segments.get(index - 1).c1 = new Vector2f(segments.get(index).c0);
            segments.remove(index);
        } else {
            segments.removeLast();
        }
    }

    /** Remove every multi-selected point (descending per series so indices stay valid); endpoint removals
     *  that would empty a series are skipped so the curve always stays defined. */
    protected void deleteSelection() {
        if (selectedPoints.isEmpty()) return;
        var bySeries = new HashMap<Integer, List<Integer>>();
        for (var id : selectedPoints) {
            bySeries.computeIfAbsent(pointSeries(id), s -> new ArrayList<>()).add(pointIndex(id));
        }
        var removed = false;
        for (var entry : bySeries.entrySet()) {
            entry.getValue().sort(Comparator.reverseOrder());
            for (var index : entry.getValue()) {
                if (canRemove(entry.getKey(), index)) {
                    removeRaw(entry.getKey(), index);
                    removed = true;
                }
            }
        }
        clearSelection();
        endDrag();
        if (removed) {
            refreshGraph();
            notifyListeners();
        }
    }

    private void onGraphMouseDown(UIEvent event) {
        if (event.button == 0) {
            // empty-space press starts a marquee (points/handles stopPropagation their own presses); a plain
            // click (finishMarquee sees a tiny rect) clears the selection
            graphView.focus();
            marquee = true;
            marqueeAdditive = isAdditive(event);
            mqX0 = mqX1 = event.x;
            mqY0 = mqY1 = event.y;
            graphView.startDrag(null, null);
        } else if (event.button == 1) {
            var menu = createMenu();
            if (!menu.isEmpty()) {
                this.addChild(new Menu<>(menu.build(), TreeBuilder.Menu::uiProvider)
                        .setOnClose(graphView::focus)
                        .setHoverTextureProvider(TreeBuilder.Menu::hoverTextureProvider)
                        .setOnNodeClicked(TreeBuilder.Menu::handle)
                        .layout(layout -> {
                            layout.left(event.x - this.getContentX());
                            layout.top(event.y - this.getContentY());
                        }));
            }
        }
    }

    protected TreeBuilder.Menu createMenu() {
        var menu = TreeBuilder.Menu.start();
        menu.leaf(lockControlPoint ? Icons.CHECK_SPRITE : IGuiTexture.EMPTY, "editor.lock_control_points",
                () -> lockControlPoint = !lockControlPoint);
        if (selectedPoints.size() > 1) {
            menu.leaf("ldlib.gui.editor.menu.remove", this::deleteSelection);
        } else if (canRemove(selectedSeries, selectedPoint)) {
            var series = selectedSeries;
            var index = selectedPoint;
            menu.leaf("ldlib.gui.editor.menu.remove", () -> removePoint(series, index));
        }
        return menu;
    }

    /** Select every point whose marker falls inside the released marquee rect; a tiny rect counts as a plain
     *  click and clears the selection (unless additive). */
    private void finishMarquee() {
        marquee = false;
        var x0 = Math.min(mqX0, mqX1);
        var y0 = Math.min(mqY0, mqY1);
        var x1 = Math.max(mqX0, mqX1);
        var y1 = Math.max(mqY0, mqY1);
        if (x1 - x0 < 3 && y1 - y0 < 3) {
            if (!marqueeAdditive) clearSelection();
            return;
        }
        if (!marqueeAdditive) selectedPoints.clear();
        var gx = graphView.getContentX();
        var gy = graphView.getContentY();
        var gw = graphView.getContentWidth();
        var gh = graphView.getContentHeight();
        var curvesList = allCurves();
        for (int s = 0; s < curvesList.size(); s++) {
            var segments = curvesList.get(s).getSegments();
            if (segments.isEmpty()) continue;
            for (int i = 0; i <= segments.size(); i++) {
                var p = pointCoord(curvesList.get(s), i);
                var sx = gx + gw * p.x();
                var sy = gy + gh * (1 - p.y());
                if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) selectedPoints.add(encodePoint(s, i));
            }
        }
        syncPrimaryFromSet();
        repositionHandles();
    }

    private void onGraphDoubleClick(UIEvent event) {
        var curvesList = allCurves();
        for (int s = 0; s < curvesList.size(); s++) {
            var index = tryAddPoint(event, curvesList.get(s));
            if (index >= 0) {
                selectedSeries = s;
                selectedPoint = index;
                selectedPoints.clear();
                selectedPoints.add(encodePoint(s, index));
                refreshGraph();
                notifyListeners();
                return;
            }
        }
    }

    /** Insert a new point where a double-click lands on (or vertically near) the series' curve; returns the
     *  new point index, or -1 when the click missed this curve. */
    private int tryAddPoint(UIEvent event, ECBCurves value) {
        var width = graphView.getContentWidth();
        var height = graphView.getContentHeight();
        var segments = value.getSegments();
        if (width <= 0 || height <= 0 || segments.isEmpty()) return -1;
        var x = (event.x - graphView.getContentX()) / width;
        var y = segments.getFirst().p0.y();
        var found = x < segments.getFirst().p0.x();
        var index = 0;
        if (!found) {
            for (var curve : segments) {
                index++;
                if (x >= curve.p0.x() && x <= curve.p1.x()) {
                    var dx = curve.p1.x() - curve.p0.x();
                    y = dx <= 0 ? curve.p1.y() : curve.getPoint((x - curve.p0.x()) / dx).y;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            index++;
            y = segments.getLast().p1.y();
        }
        var curveY = graphView.getContentY() + height * (1 - y);
        if (Math.abs(event.y - curveY) > CURVE_ADD_PX) return -1;
        if (index == 0) {
            var right = segments.getFirst().p0;
            var rightCP = segments.getFirst().c0;
            segments.addFirst(new ExplicitCubicBezierCurve2(
                    new Vector2f(x, y),
                    new Vector2f(x + 0.1f, y),
                    new Vector2f(right.x() + (right.x() - rightCP.x()), right.y() + (right.y() - rightCP.y())),
                    right));
        } else if (index > segments.size()) {
            var left = segments.getLast().p1;
            var leftCP = segments.getLast().c1;
            segments.add(new ExplicitCubicBezierCurve2(
                    left,
                    new Vector2f(left.x() + (left.x() - leftCP.x()), left.y() + (left.y() - leftCP.y())),
                    new Vector2f(x - 0.1f, y),
                    new Vector2f(x, y)));
        } else {
            var curve = segments.get(index - 1);
            segments.add(index, new ExplicitCubicBezierCurve2(
                    new Vector2f(x, y),
                    new Vector2f(x + 0.1f, y),
                    new Vector2f(curve.c1),
                    new Vector2f(curve.p1)));
            curve.c1 = new Vector2f(x - 0.1f, y);
            curve.p1 = new Vector2f(x, y);
        }
        return index;
    }

    // ------------------------------------------------------------------ drawing

    protected Vector2f toScreen(org.joml.Vector2fc coord, float x, float y, float width, float height) {
        return new Vector2f(x + width * coord.x(), y + height * (1 - coord.y()));
    }

    protected void drawGraph(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTick) {
        DrawerHelperClient.drawSolidRect(graphics, x, y, width, height, ColorPattern.BLACK.color);
        for (int i = 0; i < 6; i++) {
            DrawerHelperClient.drawSolidRect(graphics, x + i * width / 6, y, 1, height, ColorPattern.T_GRAY.color);
            DrawerHelperClient.drawSolidRect(graphics, x, y + i * height / 6, width, 1, ColorPattern.T_GRAY.color);
        }
        drawArea(graphics, x, y, width, height);
        var curvesList = allCurves();
        // control-line arms follow the handles: only for a single selected point (hidden in multi-select)
        var showArms = selectedPoints.size() <= 1;
        for (int s = 0; s < curvesList.size(); s++) {
            drawSeriesCurve(graphics, curvesList.get(s), showArms && s == selectedSeries ? selectedPoint : -1, x, y, width, height);
        }
    }

    /** Draw one series' curve, out-of-range extension lines, and (when {@code armPoint >= 0}) the green
     *  control-line arms of that point — behind the floating handle elements. */
    protected void drawSeriesCurve(GUIContext graphics, ECBCurves value, int armPoint, float x, float y, float width, float height) {
        var curves = value.getSegments();
        if (curves.isEmpty()) return;
        // render lines (drawn twice, reversed, so the strip is visible from both winding directions)
        var points = curves.stream()
                .flatMap(curve -> curve.getPoints(100).stream().map(coord -> toScreen(coord, x, y, width, height)))
                .collect(Collectors.toList());
        DrawerHelperClient.drawLines(graphics, points, -1, -1, 0.5f);
        Collections.reverse(points);
        DrawerHelperClient.drawLines(graphics, points, -1, -1, 0.5f);
        // render outer lines
        if (curves.getFirst().p0.x() > 0) {
            DrawerHelperClient.drawLines(graphics, List.of(
                            toScreen(new Vector2f(0, curves.getFirst().p0.y()), x, y, width, height),
                            toScreen(curves.getFirst().p0, x, y, width, height)),
                    ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        if (curves.getLast().p1.x() < 1) {
            DrawerHelperClient.drawLines(graphics, List.of(
                            toScreen(new Vector2f(1, curves.getLast().p1.y()), x, y, width, height),
                            toScreen(curves.getLast().p1, x, y, width, height)),
                    ColorPattern.T_RED.color, ColorPattern.T_RED.color, 0.3f);
        }
        // render control lines behind the floating handle elements
        if (armPoint >= 0) {
            if (armPoint > 0 && armPoint - 1 < curves.size()) {
                var curve = curves.get(armPoint - 1);
                DrawerHelperClient.drawLines(graphics, List.of(toScreen(curve.c1, x, y, width, height), toScreen(curve.p1, x, y, width, height)),
                        ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
            }
            if (armPoint < curves.size()) {
                var curve = curves.get(armPoint);
                DrawerHelperClient.drawLines(graphics, List.of(toScreen(curve.c0, x, y, width, height), toScreen(curve.p0, x, y, width, height)),
                        ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
            }
        }
    }

    /** Overlay above the point/handle elements: the marquee rect and the "(x, y)" coordinate readout. */
    private void drawGraphOverlay(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTick) {
        if (marquee) {
            var mx0 = Math.min(mqX0, mqX1);
            var my0 = Math.min(mqY0, mqY1);
            var mw = Math.abs(mqX1 - mqX0);
            var mh = Math.abs(mqY1 - mqY0);
            DrawerHelperClient.drawSolidRect(graphics, mx0, my0, mw, mh, ColorPattern.T_WHITE.color);
            DrawerHelperClient.drawBorder(graphics, mx0, my0, mw, mh, ColorPattern.WHITE.color, 1);
        }
        var coord = readoutCoord();
        if (coord == null) return;
        var text = coordFormatter.apply(coord.x(), coord.y());
        var tw = Minecraft.getInstance().font.width(text);
        var tx = mouseX + 6 + tw > x + width ? mouseX - 6 - tw : mouseX + 6;
        var ty = Mth.clamp(mouseY - 10, y, y + height - 10);
        DrawerHelperClient.drawSolidRect(graphics, tx - 1, ty - 1, tw + 2, 10, ColorPattern.BLACK.color);
        DrawerHelperClient.drawText(graphics, text, tx, ty, 1f, ColorPattern.WHITE.color);
    }

    @Nullable
    private org.joml.Vector2fc readoutCoord() {
        var curvesList = allCurves();
        // an active drag always shows its target's live coordinate
        if (dragKind >= 0 && dragSeries >= 0 && dragSeries < curvesList.size()) {
            var curves = curvesList.get(dragSeries);
            var segments = curves.getSegments();
            if (dragKind == 0 && dragPoint >= 0 && dragPoint <= segments.size() && !segments.isEmpty()) {
                return pointCoord(curves, dragPoint);
            }
            if (dragKind == 1 && dragPoint > 0 && dragPoint - 1 < segments.size()) {
                return segments.get(dragPoint - 1).c1;
            }
            if (dragKind == 2 && dragPoint >= 0 && dragPoint < segments.size()) {
                return segments.get(dragPoint).c0;
            }
            return null;
        }
        // otherwise exactly the element that would receive the click (top-most hovered)
        if (inHandle.isHover()) return handleCoord(1);
        if (outHandle.isHover()) return handleCoord(2);
        for (int s = 0; s < pointElements.size() && s < curvesList.size(); s++) {
            var els = pointElements.get(s);
            for (int i = 0; i < els.size(); i++) {
                if (els.get(i).isHover()) return pointCoord(curvesList.get(s), i);
            }
        }
        return null;
    }
}
