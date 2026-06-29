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
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.lwjgl.glfw.GLFW;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Editor for {@code animation} tracks: a bound-target header (root excluded), a keyframe-dot lane,
 *  and an expandable property list + interactive bezier curve editor. */
@OnlyIn(Dist.CLIENT)
public class AnimationTrackEditor extends TrackEditor {
    private static final ColorPattern[] CHANNEL_COLORS = {ColorPattern.RED, ColorPattern.GREEN, ColorPattern.BLUE};

    public static class AnimationTrackUIState extends TrackUIState {
        @Nullable AnimatedProperty selectedProperty;
        int selectedAxis = -1;            // -1 = all channels, else a single channel
        int selKeyAxis = -1, selKeyIndex = -1; // the "primary" key (tangent handles show only for size 1)
        /** Multi-selected keyframes of {@link #selectedProperty}, encoded as {@code axis<<32 | index}. */
        final Set<Long> selectedKeys = new HashSet<>();
        /** True once the user explicitly clicked a property/keyframe/curve (vs the auto-select on expand);
         *  used so an animation track still highlights when its header/lane is clicked. */
        boolean explicitSelection = false;
        final Set<AnimatedProperty> expandedProperties = new HashSet<>();
        // curve drag transient
        @Nullable AnimatedProperty dragProperty;
        int dragAxis = -1, dragKey = -1, dragHandle = 0; // handle: 0 point, 1 in, 2 out
        @Nullable ECBCurves[] dragSnapshot;
        // group keyframe drag (size > 1): original (tick,value) of each selected key
        boolean keyGroupDrag;
        final Map<Long, Vector2f> keyDragOrigins = new HashMap<>();
        // keyframe marquee (rubber-band) inside the curve box
        boolean keyMarquee, keyMarqueeAdditive;
        float kmX0, kmY0, kmX1, kmY1;
        // record mode
        /** Last captured value per type (the reference the poll diffs against). Re-read after every write
         *  so a capture/apply round-trip (e.g. euler↔quaternion) is absorbed and never re-triggers. */
        final Map<AnimatedPropertyType, float[]> recordLast = new HashMap<>();
        /** Last polled (integer) time; a change means the playhead moved (scrub/play) → don't write. */
        long recordLastTime = Long.MIN_VALUE;
        @Nullable List<AnimatedProperty> recordSnapshot;
        boolean recordDirty;
    }

    /** Per-channel value difference (degrees / blocks) above which record mode writes a keyframe. */
    private static final float REC_EPS = 1e-4f;

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
        return buildTargetSlot(ctx, track, allowRootTarget());
    }

    /** Whether this track may bind the root object. Animation excludes it; the speed track allows it. */
    protected boolean allowRootTarget() {
        return false;
    }

    /** Whether the user can add/remove properties. The speed track locks its single auto property. */
    protected boolean canEditProperties() {
        return true;
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
        if (st.selectedProperty != null && !st.selectedKeys.isEmpty()) {
            if (!track.lock()) removeSelectedKeys(ctx, st, st.selectedProperty);
            return true;
        }
        return false;
    }

    @Override
    public boolean hasSubSelection(TrackUIState state) {
        return ((AnimationTrackUIState) state).explicitSelection;
    }

    @Override
    public void clearSubSelection(TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        st.selectedProperty = null;
        st.selectedAxis = -1;
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        st.selectedKeys.clear();
        st.explicitSelection = false;
    }

    // ------------------------------------------------------------------ record mode

    @Override
    public UIElement buildHeaderControls(TimelineContext ctx, Track track, TrackUIState state) {
        var toggle = new Toggle().noText().setOn(ctx.isRecording(track))
                .setOnToggleChanged(on -> ctx.setRecordingTrack(on ? track : null));
        toggle.getToggleStyle()
                .markTexture(ColorPattern.RED.rectTexture())
                .unmarkTexture(ColorPattern.T_DARK_GRAY.rectTexture());
        toggle.setId("timeline.trackHeader.record").layout(layout -> layout.aspectRatio(1).heightPercent(100))
                .style(style -> style.tooltips("photon.gui.editor.timeline.record"));
        return toggle;
    }

    @Override
    public void beginRecording(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        var animation = (AnimationTrack) track;
        st.recordLast.clear();
        st.recordDirty = false;
        st.recordSnapshot = snapshotProperties(animation);
        st.recordLastTime = Math.max(0L, ctx.currentTimeTicks());
        var runtime = ctx.runtime();
        if (runtime != null && animation.targetId() != null
                && runtime.objects.get(animation.targetId()) instanceof FXObject target) {
            referenceRecord(st, target);
        }
    }

    @Override
    public void endRecording(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        var animation = (AnimationTrack) track;
        var before = st.recordSnapshot;
        st.recordSnapshot = null;
        st.recordLast.clear();
        if (!st.recordDirty || before == null) return;
        st.recordDirty = false;
        var after = snapshotProperties(animation);
        ctx.pushApplied("photon.gui.editor.timeline.record",
                () -> { restoreProperties(animation, after); ctx.requestRebuild(); ctx.refreshPreview(); },
                () -> { restoreProperties(animation, before); ctx.requestRebuild(); ctx.refreshPreview(); });
    }

    @Override
    public void pollRecording(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        var animation = (AnimationTrack) track;
        var runtime = ctx.runtime();
        if (runtime == null || animation.targetId() == null || track.lock()) return;
        if (!(runtime.objects.get(animation.targetId()) instanceof FXObject target)) return;
        var time = Math.max(0L, ctx.currentTimeTicks());
        // playhead moved (scrub/play): re-reference to the new pose and write nothing this tick — only a
        // user edit while the time is stationary should drop a key.
        if (time != st.recordLastTime) {
            st.recordLastTime = time;
            referenceRecord(st, target);
            return;
        }
        var ftime = (float) Math.max(0, time);
        boolean changed = false;
        boolean structural = false;
        for (var type : target.getFXObjectType().animatableProperties()) {
            var actual = type.capture(target);
            var last = st.recordLast.get(type);
            if (last == null) { st.recordLast.put(type, actual); continue; }
            if (!channelsDiffer(actual, last)) continue; // nothing changed since the last poll (also absorbs roundtrip)
            var property = animation.property(type);
            // The value the animation system itself produces here. A change that still matches this curve
            // output was caused by editing the curve / scrubbing — NOT a manual target edit — so skip it
            // (this is what stops curve-drags from spawning keys at the playhead).
            var curve = property != null ? property.sample(time) : last;
            if (property != null && !channelsDiffer(actual, curve)) {
                st.recordLast.put(type, actual);
                continue;
            }
            if (property == null) {
                // moved a not-yet-animated property → create it, seeded at the current pose and keyed here
                property = type.create(target);
                for (int c = 0; c < property.channelCount(); c++) {
                    property.moveKey(c, 0, ftime, property.key(c, 0).y); // relocate the seed key to the record time
                }
                animation.properties().add(property);
                fitRangeToKeys(property);
                structural = true;
                changed = true;
                continue; // the seed already holds the override value at this time
            }
            // Angular (rotation) channels are a coupled euler decomposition of one quaternion, so a partial
            // write (only the deviating channels) reconstructs a different orientation and feeds back into a
            // drifting pose. Record all channels of an angular property together (matches Unity).
            var coupled = type.angular();
            boolean wrote = false;
            for (int c = 0; c < actual.length; c++) {
                var ref = c < curve.length ? curve[c] : actual[c];
                if (coupled || Math.abs(actual[c] - ref) > REC_EPS) {
                    property.putKey(c, ftime, actual[c]);
                    changed = true;
                    wrote = true;
                }
            }
            if (wrote) fitRangeToKeys(property); // keep the new keyframe within the visible range
        }
        if (changed) {
            st.recordDirty = true;
            ctx.refreshPreview();
            // re-read the applied pose as the new reference so a capture/apply round-trip doesn't re-fire
            referenceRecord(st, target);
            if (structural) ctx.requestRebuild();
        }
    }

    /** Capture the current value of every animatable type into the record reference map. */
    private static void referenceRecord(AnimationTrackUIState st, FXObject target) {
        for (var type : target.getFXObjectType().animatableProperties()) {
            st.recordLast.put(type, type.capture(target));
        }
    }

    private static boolean channelsDiffer(float[] actual, float[] expected) {
        for (int c = 0; c < actual.length; c++) {
            var exp = c < expected.length ? expected[c] : actual[c];
            if (Math.abs(actual[c] - exp) > REC_EPS) return true;
        }
        return false;
    }

    private static List<AnimatedProperty> snapshotProperties(AnimationTrack track) {
        var copy = new ArrayList<AnimatedProperty>();
        for (var p : track.properties()) copy.add(p.copy());
        return copy;
    }

    private static void restoreProperties(AnimationTrack track, List<AnimatedProperty> snapshot) {
        track.properties().clear();
        for (var p : snapshot) track.properties().add(p.copy());
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
                    if (track.mute()) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_RED.color);
                    } else if (track.lock()) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_YELLOW.color);
                    }
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    drawLaneContent(ctx, graphics, animation, x, y, w, h);
                    ctx.drawPlayhead(graphics, x, y, w, h, pt);
                }));
        lane.addEventListener(UIEvents.MOUSE_WHEEL, ctx::zoom);
        lane.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) ctx.selectTrack(track);
        });
        var st = (AnimationTrackUIState) state;
        lane.addEventListener(UIEvents.DOUBLE_CLICK, e -> onLaneDoubleClick(ctx, animation, st, e));
        return lane;
    }

    /** Double-clicking a lane keyframe dot expands the track and selects that property's keyframe. */
    private void onLaneDoubleClick(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, UIEvent e) {
        AnimatedProperty bestProp = null;
        int bestAxis = -1, bestKey = -1;
        float bestDist = TimelineContext.KEY_HIT_PX + 1;
        for (var property : track.properties()) {
            for (int axis = 0; axis < property.channelCount(); axis++) {
                var count = property.keyCount(axis);
                for (int k = 0; k < count; k++) {
                    var kx = ctx.originX() + (float) ((property.key(axis, k).x - ctx.scrollTicks()) * ctx.scale());
                    var d = Math.abs(e.x - kx);
                    if (d < bestDist) { bestDist = d; bestProp = property; bestAxis = axis; bestKey = k; }
                }
            }
        }
        if (bestProp == null) return;
        st.expanded = true;
        selectProperty(ctx, track, st, bestProp, -1);
        st.selKeyAxis = bestAxis;
        st.selKeyIndex = bestKey;
        st.selectedKeys.clear();
        st.selectedKeys.add(encodeKey(bestAxis, bestKey));
        st.explicitSelection = true;
        ctx.requestRebuild();
        e.stopPropagation();
    }

    /** Lane content drawn under the playhead (default: keyframe dots). The speed track overrides to draw
     *  a curve preview. */
    protected void drawLaneContent(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        drawKeyframeDots(ctx, graphics, track, x, y, width, height);
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
            selectPropertyState(st, animation.properties().getFirst(), -1);
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
        if (!track.lock() && canEditProperties()) {
            var addBtn = new Button().setText("photon.gui.editor.timeline.add_property_button")
                    .setOnClick(e -> openAddPropertyMenu(ctx, animation, st, e.x, e.y));
            addBtn.setId("timeline.animProperty.add").layout(layout -> layout.widthPercent(100));
            list.addChild(addBtn);
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
                        .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                            ctx.drawPlayhead(graphics, x, y, w, h, pt);
                            drawKeyTooltip(ctx, graphics, animation, st, mx, my, x, y, w, h);
                            if (st.keyMarquee) drawKeyMarquee(graphics, st);
                        })));
        container.addEventListener(UIEvents.MOUSE_DOWN, e -> onCurveMouseDown(ctx, e, animation, st));
        container.addEventListener(UIEvents.DOUBLE_CLICK, e -> onCurveDoubleClick(ctx, e, animation, st));
        container.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            if (st.keyMarquee) { st.kmX1 = e.x; st.kmY1 = e.y; } else onCurveDrag(ctx, e, st);
        });
        container.addEventListener(UIEvents.DRAG_END, e -> {
            if (st.keyMarquee) finishKeyMarquee(ctx, animation, st, e.currentElement);
            else onCurveDragEnd(ctx, st);
        });
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
                selectProperty(ctx, track, st, property, -1);
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
        row.addChildren(toggle, label);
        if (canEditProperties()) {
            var remove = new Button().setText("×").setOnClick(e -> removeProperty(ctx, track, st, property));
            remove.layout(layout -> layout.aspectRatio(1).heightPercent(100));
            row.addChild(remove);
        }
        return row;
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
                selectProperty(ctx, track, st, property, axis);
                e.stopPropagation();
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
        var property = type.create(target);
        st.expanded = true;
        ctx.pushEdit("photon.gui.editor.timeline.add_property",
                () -> { track.properties().add(property); selectPropertyState(st, property, -1); ctx.requestRebuild(); ctx.refreshPreview(); },
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
            st.selectedKeys.clear();
        }
        st.expandedProperties.remove(property);
    }

    /** Select a property (sets the active track for delete-routing, no track highlight) and inspect it. */
    private void selectProperty(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                               AnimatedProperty property, int axis) {
        selectPropertyState(st, property, axis);
        st.explicitSelection = true;
        ctx.setActiveTrack(track);
        inspectProperty(ctx, track, property);
    }

    /** State-only selection (used on rebuild auto-select; does not touch the inspector or active track). */
    private void selectPropertyState(AnimationTrackUIState st, AnimatedProperty property, int axis) {
        st.selectedProperty = property;
        st.selectedAxis = axis;
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        st.selectedKeys.clear();
    }

    /** Inspect a property's configurator (e.g. rotation interp mode); falls back to the track config.
     *  Inspector edits are made undoable via property copy/restoreFrom snapshots. */
    private void inspectProperty(TimelineContext ctx, AnimationTrack track, AnimatedProperty property) {
        var before = new AnimatedProperty[]{property.copy()};
        var cfg = property.inspect(() -> {
            var prev = before[0];
            var after = property.copy();
            before[0] = after;
            ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                    () -> { property.restoreFrom(after); ctx.refreshPreview(); },
                    () -> { property.restoreFrom(prev); ctx.refreshPreview(); });
            ctx.refreshPreview();
        });
        ctx.inspectProperty(track, cfg != null ? cfg : trackConfigurator(ctx, track));
    }

    private static String propertyKey(AnimatedPropertyType type) {
        return "photon.gui.editor.timeline.property." + type.name();
    }

    private static ColorPattern channelColor(int axis) {
        return CHANNEL_COLORS[axis % CHANNEL_COLORS.length];
    }

    private static long encodeKey(int axis, int index) { return ((long) axis << 32) | (index & 0xffffffffL); }
    private static int keyAxis(long id) { return (int) (id >>> 32); }
    private static int keyIndex(long id) { return (int) id; }

    // ------------------------------------------------------------------ curve editor

    private float[] effectiveRange(AnimatedProperty property) {
        var min = property.rangeMin();
        var max = property.rangeMax();
        if (max <= min) max = min + 1;
        return new float[]{min, max};
    }

    /** Grow the display range (never shrink) so every keyframe value of {@code property} stays visible. */
    private void fitRangeToKeys(AnimatedProperty property) {
        float dataMin = Float.MAX_VALUE, dataMax = -Float.MAX_VALUE;
        for (int axis = 0; axis < property.channelCount(); axis++) {
            var count = property.keyCount(axis);
            for (int k = 0; k < count; k++) {
                var v = property.key(axis, k).y;
                dataMin = Math.min(dataMin, v);
                dataMax = Math.max(dataMax, v);
            }
        }
        if (dataMin > dataMax) return; // no keys
        var pad = Math.max(0.5f, (dataMax - dataMin) * 0.1f);
        property.setRange(Math.min(property.rangeMin(), dataMin - pad), Math.max(property.rangeMax(), dataMax + pad));
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
        drawCurveGrid(ctx, graphics, x, y, width, height);
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property)) return;
        var range = effectiveRange(property);
        var min = range[0];
        var max = range[1];
        DrawerHelper.drawText(graphics, "%.1f".formatted(max), x + 2, y + 1, 1f, ColorPattern.WHITE.color);
        DrawerHelper.drawText(graphics, "%.1f".formatted(min), x + 2, y + height - 9, 1f, ColorPattern.WHITE.color);
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
                var selected = st.selectedKeys.contains(encodeKey(axis, k));
                DrawerHelper.drawSolidRect(graphics, kx - 2, ky - 2, 4, 4, (selected ? ColorPattern.WHITE : ColorPattern.ORANGE).color);
            }
        }
        // tangent handles only when exactly one key is selected
        if (st.selectedKeys.size() == 1 && st.selKeyAxis >= 0 && isAxisActive(st, st.selKeyAxis)
                && st.selKeyIndex >= 0 && st.selKeyIndex < property.keyCount(st.selKeyAxis)) {
            var key = property.key(st.selKeyAxis, st.selKeyIndex);
            var kx = tickToCurveX(ctx, key.x, x);
            var ky = valueToCurveY(key.y, y, height, min, max);
            drawHandle(ctx, graphics, property.inHandle(st.selKeyAxis, st.selKeyIndex), kx, ky, x, y, height, min, max);
            drawHandle(ctx, graphics, property.outHandle(st.selKeyAxis, st.selKeyIndex), kx, ky, x, y, height, min, max);
        }
    }

    /** Vertical gridlines aligned to the ruler's major ticks (+ a faint horizontal mid-line). */
    private void drawCurveGrid(TimelineContext ctx, GuiGraphics graphics, float x, float y, float width, float height) {
        var major = ctx.majorTickInterval();
        if (major > 0) {
            var endTick = ctx.scrollTicks() + width / ctx.scale();
            for (double t = Math.floor(ctx.scrollTicks() / major) * major; t <= endTick; t += major) {
                if (t < 0) continue;
                var gx = tickToCurveX(ctx, (float) t, x);
                if (gx < x || gx > x + width) continue;
                DrawerHelper.drawSolidRect(graphics, gx, y, 1, height, ColorPattern.T_GRAY.color);
            }
        }
        DrawerHelper.drawSolidRect(graphics, x, y + height / 2f, width, 1, ColorPattern.T_DARK_GRAY.color);
    }

    /** When hovering a keyframe, draw a small "(time, value)" tooltip near the cursor. */
    private void drawKeyTooltip(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, AnimationTrackUIState st,
                               float mx, float my, float x, float y, float width, float height) {
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property)) return;
        var hit = hitKey(ctx, st, property, x, y, height, effectiveRange(property), mx, my);
        if (hit == null) return;
        var key = property.key(hit[0], hit[1]);
        var text = "(%.0f, %.2f)".formatted(key.x, key.y);
        var tw = net.minecraft.client.Minecraft.getInstance().font.width(text);
        var tx = mx + 6 + tw > x + width ? mx - 6 - tw : mx + 6;
        var ty = Math.max(y, my - 10);
        DrawerHelper.drawSolidRect(graphics, tx - 1, ty - 1, tw + 2, 10, ColorPattern.BLACK.color);
        DrawerHelper.drawText(graphics, text, tx, ty, 1f, ColorPattern.WHITE.color);
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
        if (e.button == 0) { ctx.setActiveTrack(track); st.explicitSelection = true; }
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
        // tangent handle drag (only when exactly one key is selected)
        if (st.selectedKeys.size() == 1 && st.selKeyAxis >= 0 && isAxisActive(st, st.selKeyAxis)) {
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
            var id = encodeKey(hit[0], hit[1]);
            if (e.isShiftDown()) { // toggle membership, no drag
                if (!st.selectedKeys.remove(id)) st.selectedKeys.add(id);
                st.selKeyAxis = hit[0];
                st.selKeyIndex = hit[1];
                e.stopPropagation();
                return;
            }
            if (!st.selectedKeys.contains(id)) { st.selectedKeys.clear(); st.selectedKeys.add(id); }
            st.selKeyAxis = hit[0];
            st.selKeyIndex = hit[1];
            beginCurveDrag(ctx, st, property, hit[0], hit[1], 0);
            el.startDrag(null, null);
            e.stopPropagation();
        } else {
            // empty press → start a keyframe marquee
            st.keyMarquee = true;
            st.keyMarqueeAdditive = e.isShiftDown();
            st.kmX0 = st.kmX1 = e.x;
            st.kmY0 = st.kmY1 = e.y;
            el.startDrag(null, null);
            e.stopPropagation();
        }
    }

    private void onCurveDoubleClick(TimelineContext ctx, UIEvent e, AnimationTrack track, AnimationTrackUIState st) {
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property)) return;
        var el = e.currentElement;
        var bx = el.getContentX();
        var by = el.getContentY();
        var bh = el.getContentHeight();
        // double-click the range numbers (top-left = max, bottom-left = min) to edit them inline
        if (e.x >= bx && e.x <= bx + 34) {
            if (e.y <= by + 9) { openRangeEditor(ctx, el, property, true, bh); e.stopPropagation(); return; }
            if (e.y >= by + bh - 9) { openRangeEditor(ctx, el, property, false, bh); e.stopPropagation(); return; }
        }
        if (track.lock()) return;
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
        var newIndex = property.addKey(axis, tick, property.type().clampValue(cursorValue));
        if (newIndex < 0) return;
        st.selKeyAxis = axis;
        st.selKeyIndex = newIndex;
        st.selectedKeys.clear();
        st.selectedKeys.add(encodeKey(axis, newIndex));
        var after = property.snapshotChannels();
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreChannels(after); ctx.refreshPreview(); },
                () -> { property.restoreChannels(before); ctx.refreshPreview(); });
        ctx.refreshPreview();
        e.stopPropagation();
    }

    /** Inline editor (a temporary {@link TextField}) for a curve's display min/max. */
    private void openRangeEditor(TimelineContext ctx, UIElement container, AnimatedProperty property, boolean editingMax, float boxH) {
        var current = editingMax ? property.rangeMax() : property.rangeMin();
        var field = new TextField();
        field.setAnyString();
        field.setText("%.3f".formatted(current), false);
        field.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(2).top(editingMax ? 1 : boxH - 9).width(44).height(9));
        var committed = new boolean[]{false};
        Runnable commit = () -> {
            if (committed[0]) return;
            committed[0] = true;
            applyRangeEdit(ctx, property, editingMax, field.getValue());
            container.removeChild(field);
        };
        field.addEventListener(UIEvents.KEY_DOWN, ev -> {
            if (ev.keyCode == GLFW.GLFW_KEY_ENTER || ev.keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commit.run();
                ev.stopPropagation();
            } else if (ev.keyCode == GLFW.GLFW_KEY_ESCAPE) {
                committed[0] = true;
                container.removeChild(field);
                ev.stopPropagation();
            }
        });
        field.addEventListener(UIEvents.BLUR, ev -> commit.run());
        container.addChild(field);
        field.focus();
    }

    private void applyRangeEdit(TimelineContext ctx, AnimatedProperty property, boolean editingMax, String raw) {
        float v;
        try {
            v = Float.parseFloat(raw.trim());
        } catch (Exception ex) {
            return; // invalid input → keep the old range
        }
        var oldMin = property.rangeMin();
        var oldMax = property.rangeMax();
        float nmin = editingMax ? oldMin : v;
        float nmax = editingMax ? v : oldMax;
        if (nmax < nmin) { var t = nmin; nmin = nmax; nmax = t; } // new max below min → swap so min <= max
        if (nmax == nmin) nmax = nmin + 1;                        // avoid a zero-width range
        if (nmin == oldMin && nmax == oldMax) return;
        var fMin = nmin;
        var fMax = nmax;
        property.setRange(fMin, fMax);
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.setRange(fMin, fMax); ctx.refreshPreview(); },
                () -> { property.setRange(oldMin, oldMax); ctx.refreshPreview(); });
        ctx.refreshPreview();
    }

    private void beginCurveDrag(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property, int axis, int key, int handle) {
        st.dragProperty = property;
        st.dragAxis = axis;
        st.dragKey = key;
        st.dragHandle = handle;
        st.dragSnapshot = property.snapshotChannels();
        st.keyGroupDrag = handle == 0 && st.selectedKeys.size() > 1;
        st.keyDragOrigins.clear();
        if (st.keyGroupDrag) {
            for (var id : st.selectedKeys) {
                st.keyDragOrigins.put(id, new Vector2f(property.key(keyAxis(id), keyIndex(id))));
            }
        }
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
        if (st.dragHandle == 0 && st.keyGroupDrag) { groupMoveKeys(ctx, e, st, property, bx, by, bh, range); return; }
        var tick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var value = curveYToValue(e.y, by, bh, range[0], range[1]);
        if (st.dragHandle == 0) {
            tick = (float) ctx.snapKeyTick(tick, e.isCtrlDown());
            var count = property.keyCount(axis);
            var lo = k > 0 ? property.key(axis, k - 1).x + 0.001f : 0;
            var hi = k < count - 1 ? property.key(axis, k + 1).x - 0.001f : Float.MAX_VALUE;
            property.moveKey(axis, k, Math.max(lo, Math.min(hi, tick)), property.type().clampValue(value));
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
        st.keyGroupDrag = false;
        st.keyDragOrigins.clear();
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
        st.selectedKeys.remove(encodeKey(axis, k));
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreChannels(after); ctx.refreshPreview(); },
                () -> { property.restoreChannels(before); ctx.refreshPreview(); });
        ctx.refreshPreview();
    }

    /** Remove every selected keyframe (descending per channel so indices stay valid), one undo. */
    private void removeSelectedKeys(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property) {
        var before = property.snapshotChannels();
        var byAxis = new HashMap<Integer, List<Integer>>();
        for (var id : st.selectedKeys) byAxis.computeIfAbsent(keyAxis(id), a -> new ArrayList<>()).add(keyIndex(id));
        for (var entry : byAxis.entrySet()) {
            entry.getValue().sort(java.util.Comparator.reverseOrder());
            for (var k : entry.getValue()) property.removeKey(entry.getKey(), k);
        }
        var after = property.snapshotChannels();
        st.selectedKeys.clear();
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreChannels(after); ctx.refreshPreview(); },
                () -> { property.restoreChannels(before); ctx.refreshPreview(); });
        ctx.refreshPreview();
    }

    /** Move every selected key by the same (Δtick, Δvalue), clamped so the group stays ordered between
     *  its non-selected neighbours. Restores the drag snapshot each frame to avoid compounding. */
    private void groupMoveKeys(TimelineContext ctx, UIEvent e, AnimationTrackUIState st, AnimatedProperty property,
                              float bx, float by, float bh, float[] range) {
        var anchorOrig = st.keyDragOrigins.get(encodeKey(st.dragAxis, st.dragKey));
        if (anchorOrig == null) return;
        var cursorTick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var cursorValue = curveYToValue(e.y, by, bh, range[0], range[1]);
        var snapped = (float) ctx.snapKeyTick(cursorTick, e.isCtrlDown());
        float dTick = snapped - anchorOrig.x;
        float dVal = cursorValue - anchorOrig.y;
        property.restoreChannels(st.dragSnapshot);
        float lo = -Float.MAX_VALUE, hi = Float.MAX_VALUE;
        for (var id : st.selectedKeys) {
            var axis = keyAxis(id);
            var k = keyIndex(id);
            var orig = st.keyDragOrigins.get(id);
            if (orig == null) continue;
            lo = Math.max(lo, -orig.x); // keep tick >= 0
            if (k - 1 >= 0 && !st.selectedKeys.contains(encodeKey(axis, k - 1))) {
                lo = Math.max(lo, property.key(axis, k - 1).x + 0.001f - orig.x);
            }
            if (k + 1 < property.keyCount(axis) && !st.selectedKeys.contains(encodeKey(axis, k + 1))) {
                hi = Math.min(hi, property.key(axis, k + 1).x - 0.001f - orig.x);
            }
        }
        dTick = lo <= hi ? Math.max(lo, Math.min(hi, dTick)) : lo;
        for (var id : st.selectedKeys) {
            var orig = st.keyDragOrigins.get(id);
            if (orig != null) property.moveKey(keyAxis(id), keyIndex(id), orig.x + dTick, property.type().clampValue(orig.y + dVal));
        }
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void drawKeyMarquee(GuiGraphics graphics, AnimationTrackUIState st) {
        var x = Math.min(st.kmX0, st.kmX1);
        var y = Math.min(st.kmY0, st.kmY1);
        var w = Math.abs(st.kmX1 - st.kmX0);
        var h = Math.abs(st.kmY1 - st.kmY0);
        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_WHITE.color);
        DrawerHelper.drawBorder(graphics, x, y, w, h, ColorPattern.WHITE.color, 1);
    }

    private void finishKeyMarquee(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, UIElement box) {
        st.keyMarquee = false;
        var property = st.selectedProperty;
        if (property == null || !track.properties().contains(property)) return;
        var x0 = Math.min(st.kmX0, st.kmX1);
        var y0 = Math.min(st.kmY0, st.kmY1);
        var x1 = Math.max(st.kmX0, st.kmX1);
        var y1 = Math.max(st.kmY0, st.kmY1);
        if (x1 - x0 < 3 && y1 - y0 < 3) { // a click → clear (unless additive)
            if (!st.keyMarqueeAdditive) { st.selectedKeys.clear(); st.selKeyAxis = -1; st.selKeyIndex = -1; }
            return;
        }
        var bx = box.getContentX();
        var by = box.getContentY();
        var bh = box.getContentHeight();
        var range = effectiveRange(property);
        if (!st.keyMarqueeAdditive) st.selectedKeys.clear();
        for (var axis : activeAxes(st)) {
            var count = property.keyCount(axis);
            for (int k = 0; k < count; k++) {
                var key = property.key(axis, k);
                var kx = tickToCurveX(ctx, key.x, bx);
                var ky = valueToCurveY(key.y, by, bh, range[0], range[1]);
                if (kx >= x0 && kx <= x1 && ky >= y0 && ky <= y1) st.selectedKeys.add(encodeKey(axis, k));
            }
        }
        if (st.selectedKeys.size() == 1) {
            var id = st.selectedKeys.iterator().next();
            st.selKeyAxis = keyAxis(id);
            st.selKeyIndex = keyIndex(id);
        }
    }
}
