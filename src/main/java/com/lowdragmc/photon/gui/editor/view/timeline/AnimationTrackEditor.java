package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Editor for {@code animation} tracks: a bound-target header (root excluded), a keyframe-dot lane,
 *  and an expandable property list + interactive bezier curve editor. */
@OnlyIn(Dist.CLIENT)
public class AnimationTrackEditor extends TrackEditor {
    private static final ColorPattern[] CHANNEL_COLORS = {ColorPattern.RED, ColorPattern.GREEN, ColorPattern.BLUE};

    public static class AnimationTrackUIState extends TrackUIState {
        @Nullable AnimatedProperty selectedProperty;
        int selectedAxis = -1;            // -1 = all channels, else a single channel
        int selKeyAxis = -1, selKeyIndex = -1;
        final Set<AnimatedProperty> expandedProperties = new HashSet<>();
        // curve drag transient
        @Nullable AnimatedProperty dragProperty;
        int dragAxis = -1, dragKey = -1, dragHandle = 0; // handle: 0 point, 1 in, 2 out
        @Nullable ECBCurves[] dragSnapshot;
    }

    @Override
    public AnimationTrackUIState createState() {
        return new AnimationTrackUIState();
    }

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.ORANGE;
    }

    @Override
    public boolean isExpandable() {
        return true;
    }

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        return buildTargetSlot(ctx, track, false); // animation cannot target root
    }

    @Override
    public double contentMaxTick(Track track) {
        double max = 0;
        for (var property : ((AnimationTrack) track).properties()) {
            for (var time : property.keyframeTimes()) {
                max = Math.max(max, time);
            }
        }
        return max;
    }

    @Override
    public IConfigurable trackConfigurator(TimelineContext ctx, Track track) {
        return IConfigurable.create(group -> {
            group.addConfigurator(new BooleanConfigurator("mute", track::mute,
                    v -> { track.mute(v); ctx.requestRebuild(); ctx.refreshPreview(); }, track.mute(), true));
            group.addConfigurator(new BooleanConfigurator("lock", track::lock,
                    v -> { track.lock(v); ctx.requestRebuild(); }, track.lock(), true));
        });
    }

    @Override
    public void onRemoved(TimelineContext ctx, Track track, TrackUIState state) {
        restoreBaseOf(ctx, track, ((AnimationTrack) track).targetId());
    }

    @Override
    public void onTargetWillChange(TimelineContext ctx, Track track, @Nullable java.util.UUID oldTargetId) {
        restoreBaseOf(ctx, track, oldTargetId);
    }

    private void restoreBaseOf(TimelineContext ctx, Track track, @Nullable java.util.UUID targetId) {
        var runtime = ctx.runtime();
        if (track instanceof AnimationTrack animation && targetId != null && runtime != null
                && runtime.objects.get(targetId) instanceof FXObject target) {
            animation.restoreBase(target);
        }
    }

    @Override
    public boolean deleteSelection(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        if (st.selectedProperty != null && st.selKeyAxis >= 0 && st.selKeyIndex >= 0) {
            if (!track.lock()) removeKeyframe(ctx, track, st, st.selectedProperty, st.selKeyAxis, st.selKeyIndex);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ lane (keyframe dots)

    @Override
    public UIElement buildLane(TimelineContext ctx, Track track, TrackUIState state) {
        var animation = (AnimationTrack) track;
        var lane = new UIElement().setId("timeline.trackLane").layout(layout -> {
            layout.widthPercent(100);
            layout.height(TimelineContext.ROW_HEIGHT);
        }).setOverflowVisible(false).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.BLACK.color);
                    if (ctx.isTrackSelected(track)) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_WHITE.color);
                    }
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    drawKeyframeDots(ctx, graphics, animation, x, y, w, h);
                    ctx.drawPlayhead(graphics, x, y, w, h, pt);
                }));
        lane.addEventListener(UIEvents.MOUSE_WHEEL, ctx::zoom);
        lane.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) ctx.selectTrack(track);
        });
        return lane;
    }

    private void drawKeyframeDots(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        for (var property : track.properties()) {
            for (var time : property.keyframeTimes()) {
                var dx = ctx.originX() + (float) ((time - ctx.scrollTicks()) * ctx.scale());
                if (dx < x || dx > x + width) continue;
                DrawerHelper.drawSolidRect(graphics, dx - 1.5f, y + height / 2f - 1.5f, 3, 3, ColorPattern.ORANGE.color);
            }
        }
    }

    // ------------------------------------------------------------------ expanded panels

    @Override
    public UIElement buildExpandedLeft(TimelineContext ctx, Track track, TrackUIState state) {
        var animation = (AnimationTrack) track;
        var st = (AnimationTrackUIState) state;
        // auto-select the first property so the curve panel isn't blank when expanded
        if (st.selectedProperty == null && !animation.properties().isEmpty()) {
            selectProperty(st, animation.properties().getFirst(), -1);
        }
        var scroller = new ScrollerView();
        scroller.setId("timeline.animProperties");
        scroller.getLayout().widthPercent(100); // height comes from the host wrapper's flex(1)
        scroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
                .verticalScrollDisplay(ScrollDisplay.AUTO).horizontalScrollDisplay(ScrollDisplay.NEVER));
        // handle on the scroller (fills the whole panel) so right-click anywhere adds a property and,
        // crucially, stopPropagation keeps it from bubbling to the left panel's "add track" handler
        scroller.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) {
                ctx.selectTrack(track);
            } else if (e.button == 1 && !track.lock()) {
                openAddPropertyMenu(ctx, animation, st, e.x, e.y);
            }
            e.stopPropagation();
        });
        var list = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(1);
            layout.paddingAll(1);
        });
        for (var property : animation.properties()) {
            list.addChild(createPropertyGroup(ctx, animation, st, property));
        }
        scroller.addScrollViewChild(list);
        return scroller;
    }

    /** A property and its (collapsible) sub-property rows, grouped so the expand toggle can show/hide
     *  the sub-rows via {@code setDisplay} without a rebuild (like the track header's expand toggle). */
    private UIElement createPropertyGroup(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, AnimatedProperty property) {
        var group = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(1);
        });
        var subRows = new ArrayList<UIElement>();
        if (property.channelCount() > 1) {
            for (int axis = 0; axis < property.channelCount(); axis++) {
                var sub = createSubPropertyRow(ctx, track, st, property, axis);
                sub.setDisplay(st.expandedProperties.contains(property));
                subRows.add(sub);
            }
        }
        group.addChild(createPropertyRow(ctx, track, st, property, subRows));
        for (var sub : subRows) group.addChild(sub);
        return group;
    }

    @Override
    public UIElement buildExpandedRight(TimelineContext ctx, Track track, TrackUIState state) {
        var animation = (AnimationTrack) track;
        var st = (AnimationTrackUIState) state;
        var container = new UIElement().setId("timeline.animCurves").layout(layout ->
                layout.widthPercent(100)).setOverflowVisible(false); // height from the host wrapper's flex(1)
        container.addChild(new UIElement().layout(layout -> layout.widthPercent(100).heightPercent(100))
                .style(style -> style
                        .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> drawCurveEditor(ctx, graphics, animation, st, x, y, w, h))
                        .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> ctx.drawPlayhead(graphics, x, y, w, h, pt))));
        container.addEventListener(UIEvents.MOUSE_DOWN, e -> onCurveMouseDown(ctx, e, animation, st));
        container.addEventListener(UIEvents.DOUBLE_CLICK, e -> onCurveDoubleClick(ctx, e, animation, st));
        container.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> onCurveDrag(ctx, e, st));
        container.addEventListener(UIEvents.DRAG_END, e -> onCurveDragEnd(ctx, st));
        container.addEventListener(UIEvents.MOUSE_WHEEL, e -> onCurveWheel(ctx, e, st));
        return container;
    }

    private UIElement createPropertyRow(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                                        AnimatedProperty property, List<UIElement> subRows) {
        var row = new UIElement().setId("timeline.animProperty").layout(layout -> {
            layout.widthPercent(100);
            layout.height(12);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
        }).style(style -> style.backgroundTexture((graphics, mx, my, x, y, w, h, pt) ->
                DrawerHelper.drawSolidRect(graphics, x, y, w, h,
                        (st.selectedProperty == property && st.selectedAxis < 0 ? ColorPattern.GRAY : ColorPattern.T_GRAY).color)));
        row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) {
                ctx.selectTrack(track);
                selectProperty(st, property, -1);
            } else if (e.button == 1 && property.type().angular() && !track.lock()) {
                openInterpModeMenu(ctx, property, e.x, e.y);
                e.stopPropagation();
            }
        });
        var multi = property.channelCount() > 1;
        // expand toggle styled like the track-header expand toggle; flips the sub-rows' display (no rebuild)
        var toggle = new Toggle().noText().setOn(st.expandedProperties.contains(property))
                .setOnToggleChanged(on -> {
                    if (on) st.expandedProperties.add(property);
                    else st.expandedProperties.remove(property);
                    for (var sub : subRows) sub.setDisplay(on);
                });
        toggle.getToggleStyle()
                .baseTexture(IGuiTexture.EMPTY)
                .hoverTexture(IGuiTexture.EMPTY)
                .markTexture(Icons.DOWN_ARROW_NO_BAR_S_LIGHT).unmarkTexture(Icons.RIGHT_ARROW_NO_BAR_S_LIGHT);
        toggle.setId("timeline.animProperty.expand").layout(layout -> layout.aspectRatio(1).heightPercent(100)).setDisplay(multi);
        var label = new Label().setText(Component.translatable(propertyKey(property.type())).getString());
        TimelineContext.styleLabel(label);
        label.layout(layout -> layout.flex(1).heightPercent(100));
        var remove = new Button().setText("×").setOnClick(e -> removeProperty(ctx, track, st, property));
        remove.layout(layout -> layout.aspectRatio(1).heightPercent(100));
        return row.addChildren(toggle, label, remove);
    }

    private UIElement createSubPropertyRow(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, AnimatedProperty property, int axis) {
        var row = new UIElement().setId("timeline.animSubProperty").layout(layout -> {
            layout.widthPercent(100);
            layout.height(11);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
            layout.paddingLeft(12);
        }).style(style -> style.backgroundTexture((graphics, mx, my, x, y, w, h, pt) ->
                DrawerHelper.drawSolidRect(graphics, x, y, w, h,
                        (st.selectedProperty == property && st.selectedAxis == axis ? ColorPattern.GRAY : ColorPattern.T_DARK_GRAY).color)));
        row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) {
                ctx.selectTrack(track);
                selectProperty(st, property, axis);
            }
        });
        var swatch = new UIElement().layout(layout -> layout.width(4).heightPercent(100))
                .style(style -> style.backgroundTexture(channelColor(axis).rectTexture()));
        var label = new Label().setText(Component.translatable(propertyKey(property.type())).getString()
                + "." + property.type().channelKey(axis));
        TimelineContext.styleLabel(label);
        label.layout(layout -> layout.flex(1).heightPercent(100));
        return row.addChildren(swatch, label);
    }

    // ------------------------------------------------------------------ menus / mutations

    private void openAddPropertyMenu(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, float x, float y) {
        var runtime = ctx.runtime();
        if (runtime == null) return;
        var bound = track.targetId() == null ? null : runtime.objects.get(track.targetId());
        if (!(bound instanceof FXObject fxObject)) return;
        var menu = TreeBuilder.Menu.start();
        for (var type : fxObject.getFXObjectType().animatableProperties()) {
            if (track.property(type) == null) { // only show not-yet-added properties
                menu.leaf(Component.translatable("photon.gui.editor.timeline.add_property",
                        Component.translatable(propertyKey(type))), () -> addProperty(ctx, track, st, type));
            }
        }
        if (!menu.isEmpty()) ctx.openMenu(x, y, menu);
    }

    private void addProperty(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, AnimatedPropertyType type) {
        var runtime = ctx.runtime();
        if (runtime == null || track.targetId() == null) return;
        if (!(runtime.objects.get(track.targetId()) instanceof FXObject target)) return;
        var property = AnimatedProperty.create(type, target);
        st.expanded = true;
        ctx.pushEdit("photon.gui.editor.timeline.add_property",
                () -> { track.properties().add(property); selectProperty(st, property, -1); ctx.requestRebuild(); ctx.refreshPreview(); },
                () -> { track.properties().remove(property); property.restoreBase(target); clearSelectedIf(st, property); ctx.requestRebuild(); ctx.refreshPreview(); });
    }

    private void removeProperty(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, AnimatedProperty property) {
        var runtime = ctx.runtime();
        var bound = (runtime == null || track.targetId() == null) ? null : runtime.objects.get(track.targetId());
        var index = track.properties().indexOf(property);
        ctx.pushEdit("photon.gui.editor.timeline.remove_property",
                () -> {
                    track.properties().remove(property);
                    if (bound instanceof FXObject obj) property.restoreBase(obj);
                    clearSelectedIf(st, property);
                    ctx.requestRebuild();
                    ctx.refreshPreview();
                },
                () -> { track.properties().add(Math.min(index, track.properties().size()), property); ctx.requestRebuild(); ctx.refreshPreview(); });
    }

    private void clearSelectedIf(AnimationTrackUIState st, AnimatedProperty property) {
        if (st.selectedProperty == property) {
            st.selectedProperty = null;
            st.selectedAxis = -1;
            st.selKeyAxis = -1;
            st.selKeyIndex = -1;
        }
        st.expandedProperties.remove(property);
    }

    private void openInterpModeMenu(TimelineContext ctx, AnimatedProperty property, float x, float y) {
        var menu = TreeBuilder.Menu.start();
        menu.leaf(Component.translatable("photon.gui.editor.timeline.interp_default"),
                () -> setInterpMode(ctx, property, AnimatedProperty.INTERP_DEFAULT));
        menu.leaf(Component.translatable("photon.gui.editor.timeline.interp_shortest"),
                () -> setInterpMode(ctx, property, AnimatedProperty.INTERP_SHORTEST));
        ctx.openMenu(x, y, menu);
    }

    private void setInterpMode(TimelineContext ctx, AnimatedProperty property, int mode) {
        var old = property.interpMode();
        if (old == mode) return;
        ctx.pushEdit("photon.gui.editor.timeline.edit_curve",
                () -> { property.interpMode(mode); ctx.refreshPreview(); },
                () -> { property.interpMode(old); ctx.refreshPreview(); });
    }

    private void selectProperty(AnimationTrackUIState st, AnimatedProperty property, int axis) {
        st.selectedProperty = property;
        st.selectedAxis = axis;
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
    }

    private static String propertyKey(AnimatedPropertyType type) {
        return "photon.gui.editor.timeline.property." + type.name();
    }

    private static ColorPattern channelColor(int axis) {
        return CHANNEL_COLORS[axis % CHANNEL_COLORS.length];
    }

    // ------------------------------------------------------------------ curve editor

    private float[] effectiveRange(AnimatedProperty property) {
        var min = property.rangeMin();
        var max = property.rangeMax();
        if (max <= min) max = min + 1;
        return new float[]{min, max};
    }

    private int[] activeAxes(AnimationTrackUIState st) {
        if (st.selectedAxis >= 0) return new int[]{st.selectedAxis};
        var n = st.selectedProperty == null ? 0 : st.selectedProperty.channelCount();
        var axes = new int[n];
        for (int i = 0; i < n; i++) axes[i] = i;
        return axes;
    }

    private boolean isAxisActive(AnimationTrackUIState st, int axis) {
        return st.selectedAxis < 0 || st.selectedAxis == axis;
    }

    private float tickToCurveX(TimelineContext ctx, float tick, float boxX) {
        return boxX + (tick - ctx.scrollTicks()) * ctx.scale();
    }

    private float curveXToTick(TimelineContext ctx, float mouseX, float boxX) {
        return (mouseX - boxX) / ctx.scale() + ctx.scrollTicks();
    }

    private float valueToCurveY(float value, float boxY, float boxH, float min, float max) {
        // not clamped: out-of-range points render outside and are hidden by the panel scissor
        return boxY + boxH * (1 - (value - min) / (max - min));
    }

    private float curveYToValue(float mouseY, float boxY, float boxH, float min, float max) {
        return min + (max - min) * (1 - (mouseY - boxY) / boxH);
    }

    private void drawCurveEditor(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, AnimationTrackUIState st, float x, float y, float width, float height) {
        DrawerHelper.drawSolidRect(graphics, x, y, width, height, ColorPattern.BLACK.color);
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property)) return;
        var range = effectiveRange(property);
        var min = range[0];
        var max = range[1];
        DrawerHelper.drawText(graphics, "%.1f".formatted(max), x + 2, y + 1, 0.5f, ColorPattern.GRAY.color);
        DrawerHelper.drawText(graphics, "%.1f".formatted(min), x + 2, y + height - 6, 0.5f, ColorPattern.GRAY.color);
        for (var axis : activeAxes(st)) {
            var channel = property.channel(axis);
            var points = new ArrayList<Vector2f>();
            for (float px = 0; px <= width; px += 2) {
                var tick = ctx.scrollTicks() + px / ctx.scale();
                points.add(new Vector2f(x + px, valueToCurveY(AnimatedProperty.sampleChannel(channel, tick), y, height, min, max)));
            }
            DrawerHelper.drawLines(graphics, points, channelColor(axis).color, channelColor(axis).color, 0.5f);
        }
        for (var axis : activeAxes(st)) {
            var count = property.keyCount(axis);
            for (int k = 0; k < count; k++) {
                var key = property.key(axis, k);
                var kx = tickToCurveX(ctx, key.x, x);
                if (kx < x - 2 || kx > x + width + 2) continue;
                var ky = valueToCurveY(key.y, y, height, min, max);
                var selected = st.selKeyAxis == axis && st.selKeyIndex == k;
                DrawerHelper.drawSolidRect(graphics, kx - 2, ky - 2, 4, 4, (selected ? ColorPattern.WHITE : ColorPattern.ORANGE).color);
            }
        }
        if (st.selKeyAxis >= 0 && isAxisActive(st, st.selKeyAxis) && st.selKeyIndex >= 0 && st.selKeyIndex < property.keyCount(st.selKeyAxis)) {
            var key = property.key(st.selKeyAxis, st.selKeyIndex);
            var kx = tickToCurveX(ctx, key.x, x);
            var ky = valueToCurveY(key.y, y, height, min, max);
            drawHandle(ctx, graphics, property.inHandle(st.selKeyAxis, st.selKeyIndex), kx, ky, x, y, height, min, max);
            drawHandle(ctx, graphics, property.outHandle(st.selKeyAxis, st.selKeyIndex), kx, ky, x, y, height, min, max);
        }
    }

    private void drawHandle(TimelineContext ctx, GuiGraphics graphics, @Nullable Vector2f handle, float kx, float ky,
                            float x, float y, float height, float min, float max) {
        if (handle == null) return;
        var hx = tickToCurveX(ctx, handle.x, x);
        var hy = valueToCurveY(handle.y, y, height, min, max);
        DrawerHelper.drawLines(graphics, List.of(new Vector2f(kx, ky), new Vector2f(hx, hy)),
                ColorPattern.T_GREEN.color, ColorPattern.T_GREEN.color, 0.3f);
        DrawerHelper.drawSolidRect(graphics, hx - 1.5f, hy - 1.5f, 3, 3, ColorPattern.GREEN.color);
    }

    private void onCurveMouseDown(TimelineContext ctx, UIEvent e, AnimationTrack track, AnimationTrackUIState st) {
        if (e.button == 0) ctx.selectTrack(track);
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property)) return;
        var el = e.currentElement;
        var bx = el.getContentX();
        var by = el.getContentY();
        var bh = el.getContentHeight();
        var range = effectiveRange(property);
        if (e.button == 1) {
            var hit = hitKey(ctx, st, property, bx, by, bh, range, e.x, e.y);
            if (hit != null && !track.lock()) removeKeyframe(ctx, track, st, property, hit[0], hit[1]);
            e.stopPropagation();
            return;
        }
        if (e.button != 0 || track.lock()) return;
        if (st.selKeyAxis >= 0 && isAxisActive(st, st.selKeyAxis)) {
            var which = hitHandle(ctx, property, st.selKeyAxis, st.selKeyIndex, bx, by, bh, range, e.x, e.y);
            if (which != 0) {
                beginCurveDrag(ctx, st, property, st.selKeyAxis, st.selKeyIndex, which);
                el.startDrag(null, null);
                e.stopPropagation();
                return;
            }
        }
        var hit = hitKey(ctx, st, property, bx, by, bh, range, e.x, e.y);
        if (hit != null) {
            st.selKeyAxis = hit[0];
            st.selKeyIndex = hit[1];
            beginCurveDrag(ctx, st, property, hit[0], hit[1], 0);
            el.startDrag(null, null);
            e.stopPropagation();
        } else {
            st.selKeyAxis = -1;
            st.selKeyIndex = -1;
        }
    }

    private void onCurveDoubleClick(TimelineContext ctx, UIEvent e, AnimationTrack track, AnimationTrackUIState st) {
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property) || track.lock()) return;
        var el = e.currentElement;
        var bx = el.getContentX();
        var by = el.getContentY();
        var bh = el.getContentHeight();
        var range = effectiveRange(property);
        var tick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var cursorValue = curveYToValue(e.y, by, bh, range[0], range[1]);
        var axes = activeAxes(st);
        var axis = -1;
        if (axes.length == 1) {
            axis = axes[0];
        } else {
            var best = Float.MAX_VALUE;
            for (var a : axes) {
                var cy = valueToCurveY(AnimatedProperty.sampleChannel(property.channel(a), tick), by, bh, range[0], range[1]);
                var d = Math.abs(e.y - cy);
                if (d < best) { best = d; axis = a; }
            }
        }
        if (axis < 0) return;
        var before = property.snapshotChannels();
        var newIndex = property.addKey(axis, tick, cursorValue);
        if (newIndex < 0) return;
        st.selKeyAxis = axis;
        st.selKeyIndex = newIndex;
        var after = property.snapshotChannels();
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreChannels(after); ctx.refreshPreview(); },
                () -> { property.restoreChannels(before); ctx.refreshPreview(); });
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void beginCurveDrag(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property, int axis, int key, int handle) {
        st.dragProperty = property;
        st.dragAxis = axis;
        st.dragKey = key;
        st.dragHandle = handle;
        st.dragSnapshot = property.snapshotChannels();
        ctx.beginScrub();
    }

    private void onCurveDrag(TimelineContext ctx, UIEvent e, AnimationTrackUIState st) {
        if (st.dragProperty == null) return;
        var el = e.currentElement;
        var bx = el.getContentX();
        var by = el.getContentY();
        var bh = el.getContentHeight();
        var property = st.dragProperty;
        var axis = st.dragAxis;
        var k = st.dragKey;
        var range = effectiveRange(property);
        var tick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var value = curveYToValue(e.y, by, bh, range[0], range[1]);
        if (st.dragHandle == 0) {
            var count = property.keyCount(axis);
            var lo = k > 0 ? property.key(axis, k - 1).x + 0.001f : 0;
            var hi = k < count - 1 ? property.key(axis, k + 1).x - 0.001f : Float.MAX_VALUE;
            property.moveKey(axis, k, Math.max(lo, Math.min(hi, tick)), value);
        } else if (st.dragHandle == 1) {
            property.setInHandle(axis, k, Math.min(tick, property.key(axis, k).x), value);
        } else {
            property.setOutHandle(axis, k, Math.max(tick, property.key(axis, k).x), value);
        }
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void onCurveDragEnd(TimelineContext ctx, AnimationTrackUIState st) {
        if (st.dragProperty == null) return;
        var property = st.dragProperty;
        var before = st.dragSnapshot;
        var after = property.snapshotChannels();
        st.dragProperty = null;
        st.dragSnapshot = null;
        ctx.endScrub();
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreChannels(after); ctx.refreshPreview(); },
                () -> { property.restoreChannels(before); ctx.refreshPreview(); });
    }

    private void onCurveWheel(TimelineContext ctx, UIEvent e, AnimationTrackUIState st) {
        if (e.isShiftDown() && st.selectedProperty != null) {
            var range = effectiveRange(st.selectedProperty);
            var center = (range[0] + range[1]) / 2f;
            var half = Math.max(1e-3f, (range[1] - range[0]) / 2f * (e.deltaY > 0 ? 1 / 1.1f : 1.1f));
            st.selectedProperty.setRange(center - half, center + half);
            e.stopPropagation();
        } else {
            ctx.zoom(e);
        }
    }

    @Nullable
    private int[] hitKey(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property, float bx, float by, float bh, float[] range, float mx, float my) {
        for (var axis : activeAxes(st)) {
            var count = property.keyCount(axis);
            for (int k = 0; k < count; k++) {
                var key = property.key(axis, k);
                var kx = tickToCurveX(ctx, key.x, bx);
                var ky = valueToCurveY(key.y, by, bh, range[0], range[1]);
                if (Math.abs(mx - kx) <= TimelineContext.KEY_HIT_PX && Math.abs(my - ky) <= TimelineContext.KEY_HIT_PX) {
                    return new int[]{axis, k};
                }
            }
        }
        return null;
    }

    private int hitHandle(TimelineContext ctx, AnimatedProperty property, int axis, int k, float bx, float by, float bh, float[] range, float mx, float my) {
        if (k < 0 || k >= property.keyCount(axis)) return 0;
        var in = property.inHandle(axis, k);
        if (in != null && Math.abs(mx - tickToCurveX(ctx, in.x, bx)) <= TimelineContext.KEY_HIT_PX
                && Math.abs(my - valueToCurveY(in.y, by, bh, range[0], range[1])) <= TimelineContext.KEY_HIT_PX) {
            return 1;
        }
        var out = property.outHandle(axis, k);
        if (out != null && Math.abs(mx - tickToCurveX(ctx, out.x, bx)) <= TimelineContext.KEY_HIT_PX
                && Math.abs(my - valueToCurveY(out.y, by, bh, range[0], range[1])) <= TimelineContext.KEY_HIT_PX) {
            return 2;
        }
        return 0;
    }

    private void removeKeyframe(TimelineContext ctx, Track track, AnimationTrackUIState st, AnimatedProperty property, int axis, int k) {
        var before = property.snapshotChannels();
        property.removeKey(axis, k);
        var after = property.snapshotChannels();
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreChannels(after); ctx.refreshPreview(); },
                () -> { property.restoreChannels(before); ctx.refreshPreview(); });
        ctx.refreshPreview();
    }
}
