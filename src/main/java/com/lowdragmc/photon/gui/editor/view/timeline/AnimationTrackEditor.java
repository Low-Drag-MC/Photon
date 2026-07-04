package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ColorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
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
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.OreSprites;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyType;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.lwjgl.glfw.GLFW;
import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.timeline.ExprClip;
import com.lowdragmc.photon.client.fx.timeline.GradientClip;
import com.lowdragmc.photon.client.fx.timeline.CurveClip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.property.ColorAnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigAnimatedProperty;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorConfigurator;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;
import org.joml.Vector4f;

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

    /** A generic {@link NumberFunctionConfig} used to edit a curve clip's {@code Curve} in the inspector,
     *  read once from the dummy annotated holder field below. */
    @NumberFunctionConfig(types = {Curve.class, RandomCurve.class},
            curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "value"))
    private static final Object CURVE_CLIP_CONFIG_HOLDER = null;
    private static final NumberFunctionConfig CURVE_CLIP_CONFIG = curveClipConfig();

    private static NumberFunctionConfig curveClipConfig() {
        try {
            return AnimationTrackEditor.class.getDeclaredField("CURVE_CLIP_CONFIG_HOLDER")
                    .getAnnotation(NumberFunctionConfig.class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public static class AnimationTrackUIState extends TrackUIState {
        @Nullable AnimatedProperty selectedProperty;
        int selectedAxis = -1;            // -1 = all channels, else a single channel
        int selKeyAxis = -1, selKeyIndex = -1; // the "primary" key (tangent handles show only for size 1)
        /** Multi-selected keyframes of {@link #selectedProperty}, encoded as {@code axis<<32 | index}. */
        final Set<Long> selectedKeys = new HashSet<>();
        /** True once the user explicitly clicked a property/keyframe/curve (vs the auto-select on expand);
         *  used so an animation track still highlights when its header/lane is clicked. */
        boolean explicitSelection = false;
        /** Auto-select the first property only once (on the initial build); after the user explicitly clears
         *  the selection (clicking the track), the curve box stays empty instead of re-picking a property. */
        boolean autoSelectedOnce = false;
        final Set<AnimatedProperty> expandedProperties = new HashSet<>();
        // curve drag transient
        @Nullable AnimatedProperty dragProperty;
        int dragAxis = -1, dragKey = -1, dragHandle = 0; // handle: 0 point, 1 in, 2 out
        @Nullable ECBCurves[] dragSnapshot;
        // expr clips
        /** Selected expression clips of {@link #selectedProperty} (references; interaction is single-axis). */
        final Set<ExprClip> selectedExprClips = new HashSet<>();
        @Nullable ExprClip dragClip;
        int dragClipAxis = -1, dragClipMode = 0; // 0 move, 1 resize-start, 2 resize-end
        double dragClipGrabOffset;               // cursor tick - anchor start, captured at drag begin
        @Nullable List<ExprClip> dragClipSnapshot;
        final Map<ExprClip, double[]> clipDragOrigins = new HashMap<>(); // clip -> {start, duration}
        // group keyframe drag (size > 1): original (tick,value) of each selected key
        boolean keyGroupDrag;
        final Map<Long, Vector2f> keyDragOrigins = new HashMap<>();
        // keyframe marquee (rubber-band) inside the curve box
        boolean keyMarquee, keyMarqueeAdditive;
        float kmX0, kmY0, kmX1, kmY1;
        // color property (gradient lane): selected/dragged stop + drag snapshot for undo
        @Nullable ColorAnimatedProperty.ColorKey selectedStop;
        @Nullable ColorAnimatedProperty.ColorKey dragStop;
        @Nullable List<ColorAnimatedProperty.ColorKey> stopDragSnapshot;
        // color property gradient clips (f(t)->gradient) overlaid on the color lane
        @Nullable GradientClip selectedGradientClip;
        final Set<GradientClip> selectedGradientClips = new HashSet<>();
        @Nullable GradientClip dragGradientClip;
        int dragGradientClipMode = 0; // 0 move, 1 resize-start, 2 resize-end
        double dragGradientClipGrabOffset;
        @Nullable List<GradientClip> gradientClipDragSnapshot;
        // config NF/NF3 curve clips (f(t)->curve) drawn in a strip at the top of the curve box
        @Nullable CurveClip selectedCurveClip;
        final Set<CurveClip> selectedCurveClips = new HashSet<>();
        @Nullable ConfigAnimatedProperty dragCurveClipProperty;
        @Nullable CurveClip dragCurveClip;
        int dragCurveClipAxis = -1, dragCurveClipMode = 0; // 0 move, 1 resize-start, 2 resize-end
        double dragCurveClipGrabOffset;
        @Nullable List<CurveClip> curveClipDragSnapshot;
        /** The in-progress clip drag (expr/gradient/curve) currently overlaps another clip: draw it red and
         *  revert on release (overlaps aren't allowed, matching the clip tracks). */
        boolean subClipDragInvalid;
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
    /** Screen-pixel width of an expr clip's start/end resize zones. */
    private static final float CLIP_EDGE_PX = 4;
    /** Default duration (ticks) of a newly added expression clip. */
    private static final double DEFAULT_EXPR_CLIP_TICKS = 20;
    /** Minimum duration (ticks) an expression clip can be resized to. */
    private static final double MIN_EXPR_CLIP_TICKS = 1e-3;

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
        if (st.selectedProperty == null) return false;
        if (st.selectedProperty instanceof ColorAnimatedProperty color && !st.selectedGradientClips.isEmpty()) {
            if (!track.lock()) removeSelectedGradientClips(ctx, st, color);
            return true;
        }
        if (st.selectedProperty instanceof ColorAnimatedProperty color && st.selectedStop != null) {
            if (!track.lock()) removeStopEdit(ctx, st, color, st.selectedStop);
            return true;
        }
        if (st.selectedProperty instanceof ConfigAnimatedProperty cfg && !st.selectedCurveClips.isEmpty()) {
            if (!track.lock()) removeSelectedCurveClips(ctx, st, cfg);
            return true;
        }
        if (!st.selectedExprClips.isEmpty()) {
            if (!track.lock()) removeSelectedExprClips(ctx, st, st.selectedProperty);
            return true;
        }
        if (!st.selectedKeys.isEmpty()) {
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
    public boolean clearSubSelection(TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        // the curve box builds per-property clip/stop elements, so clearing a shown property needs a rebuild
        var hadContent = st.selectedProperty != null || st.explicitSelection;
        st.selectedProperty = null;
        st.selectedAxis = -1;
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        st.selectedKeys.clear();
        st.selectedExprClips.clear();
        st.selectedStop = null;
        st.selectedGradientClip = null;
        st.selectedGradientClips.clear();
        st.selectedCurveClip = null;
        st.selectedCurveClips.clear();
        st.explicitSelection = false;
        return hadContent;
    }

    /** Sentinel for {@link #clearOtherSubSelections}: the single-reference color-stop selection has no set. */
    private static final Object STOP_TOKEN = new Object();

    /** Make the {@code keep} sub-selection the only active one, clearing every other kind (keyframes vs
     *  expr/curve/gradient clips vs color stop stay mutually exclusive). Pass the set being kept (or
     *  {@link #STOP_TOKEN} for a stop). This is what makes Delete/copy operate on exactly one kind (bug 2). */
    private void clearOtherSubSelections(AnimationTrackUIState st, Object keep) {
        if (keep != st.selectedKeys) { st.selectedKeys.clear(); st.selKeyAxis = -1; st.selKeyIndex = -1; }
        if (keep != st.selectedExprClips) st.selectedExprClips.clear();
        if (keep != st.selectedCurveClips) { st.selectedCurveClip = null; st.selectedCurveClips.clear(); }
        if (keep != st.selectedGradientClips) { st.selectedGradientClip = null; st.selectedGradientClips.clear(); }
        if (keep != STOP_TOKEN) st.selectedStop = null;
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

    /** Double-clicking a lane expr-clip bar expands the track and selects that clip; otherwise a keyframe
     *  dot expands + selects that property's keyframe. */
    private void onLaneDoubleClick(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st, UIEvent e) {
        // expr clip bars take priority (they cover a range, keyframes are points)
        for (var property : track.properties()) {
            for (int axis = 0; axis < property.channelCount(); axis++) {
                for (var clip : property.exprClips(axis)) {
                    var x0 = ctx.originX() + (float) ((clip.start() - ctx.scrollTicks()) * ctx.scale());
                    var x1 = ctx.originX() + (float) ((clip.end() - ctx.scrollTicks()) * ctx.scale());
                    if (e.x >= x0 && e.x <= x1) {
                        st.expanded = true;
                        selectProperty(ctx, track, st, property, axis);
                        st.selectedExprClips.clear();
                        st.selectedExprClips.add(clip);
                        inspectExprClip(ctx, track, property, axis, clip);
                        ctx.requestRebuild();
                        e.stopPropagation();
                        return;
                    }
                }
            }
        }
        // color properties: nearest stop by screen-x → expand + select the property + that stop
        ColorAnimatedProperty bestColor = null;
        ColorAnimatedProperty.ColorKey bestStop = null;
        float bestStopDist = TimelineContext.KEY_HIT_PX + 1;
        for (var property : track.properties()) {
            if (property instanceof ColorAnimatedProperty color) {
                for (var stop : color.stops()) {
                    var sx = ctx.originX() + (float) ((stop.tick - ctx.scrollTicks()) * ctx.scale());
                    var d = Math.abs(e.x - sx);
                    if (d < bestStopDist) { bestStopDist = d; bestColor = color; bestStop = stop; }
                }
            }
        }
        if (bestColor != null) {
            st.expanded = true;
            selectProperty(ctx, track, st, bestColor, -1);
            selectStop(ctx, track, bestColor, st, bestStop);
            ctx.requestRebuild();
            e.stopPropagation();
            return;
        }
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

    /** Lane content drawn under the playhead (default: expr clip bars + keyframe dots). The speed track
     *  overrides to draw a curve preview. */
    protected void drawLaneContent(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        drawColorLaneBars(ctx, graphics, track, x, y, width, height);
        drawExprClipBars(ctx, graphics, track, x, y, width, height);
        drawKeyframeDots(ctx, graphics, track, x, y, width, height);
    }

    /** Draw each color property's gradient as a thin bar across the collapsed lane (+ stop ticks). */
    private void drawColorLaneBars(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        var barH = 6f;
        var by = y + height / 2f - barH / 2f;
        for (var property : track.properties()) {
            if (property instanceof ColorAnimatedProperty color) {
                drawGradientStrip(ctx, graphics, color, x, by, width, barH);
                for (var clip : color.gradientClips()) {
                    var x0 = tickToCurveX(ctx, (float) clip.start(), x);
                    var x1 = tickToCurveX(ctx, (float) clip.end(), x);
                    if (x1 < x || x0 > x + width || clip.gradient() == null) continue;
                    var cx0 = Math.max(x, x0);
                    var cx1 = Math.min(x + width, x1);
                    drawGradientColorRegion(graphics, clip.gradient(), cx0, by, cx1 - cx0, barH);
                    DrawerHelper.drawSolidRect(graphics, cx0, by, cx1 - cx0, 1, withAlpha(ColorPattern.WHITE.color, 0x88));
                    DrawerHelper.drawSolidRect(graphics, cx0, by + barH - 1, cx1 - cx0, 1, withAlpha(ColorPattern.WHITE.color, 0x88));
                }
                for (var stop : color.stops()) {
                    var sx = tickToCurveX(ctx, stop.tick, x);
                    if (sx < x || sx > x + width) continue;
                    DrawerHelper.drawSolidRect(graphics, sx - 0.5f, by, 1, barH, ColorPattern.WHITE.color);
                }
            }
        }
    }

    /** Draw each property's expression clips as thin channel-colored bars across the collapsed lane. */
    private void drawExprClipBars(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        var barH = 4f;
        var by = y + height / 2f - barH / 2f;
        for (var property : track.properties()) {
            for (int axis = 0; axis < property.channelCount(); axis++) {
                for (var clip : property.exprClips(axis)) {
                    var x0 = ctx.originX() + (float) ((clip.start() - ctx.scrollTicks()) * ctx.scale());
                    var x1 = ctx.originX() + (float) ((clip.end() - ctx.scrollTicks()) * ctx.scale());
                    if (x1 < x || x0 > x + width) continue;
                    var cx0 = Math.max(x, x0);
                    var cx1 = Math.min(x + width, x1);
                    var color = clip.error() != null ? ColorPattern.RED.color : channelColor(axis).color;
                    DrawerHelper.drawSolidRect(graphics, cx0, by, Math.max(1, cx1 - cx0), barH, withAlpha(color, 0xAA));
                }
            }
            // curve clips: a thin channel-colored bar at the top edge of the lane
            if (property instanceof ConfigAnimatedProperty cfg) {
                for (int axis = 0; axis < cfg.channelCount(); axis++) {
                    for (var clip : cfg.curveClips(axis)) {
                        var x0 = ctx.originX() + (float) ((clip.start() - ctx.scrollTicks()) * ctx.scale());
                        var x1 = ctx.originX() + (float) ((clip.end() - ctx.scrollTicks()) * ctx.scale());
                        if (x1 < x || x0 > x + width) continue;
                        var cx0 = Math.max(x, x0);
                        var cx1 = Math.min(x + width, x1);
                        DrawerHelper.drawSolidRect(graphics, cx0, y + 1, Math.max(1, cx1 - cx0), 2, withAlpha(channelColor(axis).color, 0xAA));
                    }
                }
            }
        }
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private void drawKeyframeDots(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        for (var property : track.properties()) {
            if (property instanceof ColorAnimatedProperty) continue; // shown as a gradient bar instead
            for (var time : property.keyframeTimes()) {
                var dx = ctx.originX() + (float) ((time - ctx.scrollTicks()) * ctx.scale());
                if (dx < x || dx > x + width) continue;
                DrawerHelper.drawSolidRect(graphics, dx - 1.5f, y + height / 2f - 1.5f, 3, 3, ColorPattern.ORANGE.color);
            }
        }
    }

    // ------------------------------------------------------------------ expanded panels

    /** Auto-select the first property (state-only) so the curve panel isn't blank on the initial expand.
     *  Runs only once: after the user explicitly clears the selection, the box stays empty. */
    protected void autoSelectFirstProperty(AnimationTrackUIState st, AnimationTrack animation) {
        if (st.autoSelectedOnce) return;
        st.autoSelectedOnce = true;
        if (st.selectedProperty == null && !animation.properties().isEmpty()) {
            selectPropertyState(st, animation.properties().getFirst(), -1);
        }
    }

    @Override
    public UIElement buildExpandedLeft(TimelineContext ctx, Track track, TrackUIState state) {
        var animation = (AnimationTrack) track;
        var st = (AnimationTrackUIState) state;
        // auto-select the first property so the curve panel isn't blank when expanded
        autoSelectFirstProperty(st, animation);
        var scroller = new ScrollerView();
        scroller.setId("timeline.animProperties");
        scroller.viewPort.getStyle().background(OreSprites.RECT2);
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
        var box = new UIElement().setId("timeline.curveBox").layout(layout -> layout.widthPercent(100).heightPercent(100))
                .style(style -> style
                        .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                            if (st.selectedProperty instanceof ColorAnimatedProperty color) {
                                drawColorEditor(ctx, graphics, color, st, x, y, w, h);
                            } else {
                                drawCurveEditor(ctx, graphics, animation, st, x, y, w, h);
                            }
                        })
                        .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                            ctx.drawPlayhead(graphics, x, y, w, h, pt);
                            drawKeyTooltip(ctx, graphics, animation, st, mx, my, x, y, w, h);
                            if (st.keyMarquee) drawKeyMarquee(graphics, st);
                        }));
        container.addChild(box);
        // per-clip / per-stop sub-elements (own their own hit-testing, selection, drag, right-click)
        addCurveBoxItems(ctx, animation, st, box);
        // box-level handlers keep only empty-space press, double-click add, marquee/keyframe drag, zoom.
        container.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (st.selectedProperty instanceof ColorAnimatedProperty color) onColorMouseDown(ctx, e, animation, color, st);
            else onCurveMouseDown(ctx, e, animation, st);
        });
        container.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
            if (st.selectedProperty instanceof ColorAnimatedProperty color) onColorDoubleClick(ctx, e, animation, color, st);
            else onCurveDoubleClick(ctx, e, animation, st);
        });
        container.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            if (st.keyMarquee) { st.kmX1 = e.x; st.kmY1 = e.y; }
            else onCurveDrag(ctx, e, st);
        });
        container.addEventListener(UIEvents.DRAG_END, e -> {
            if (st.keyMarquee) finishKeyMarquee(ctx, animation, st, e.currentElement);
            else onCurveDragEnd(ctx, st);
        });
        container.addEventListener(UIEvents.MOUSE_WHEEL, e -> {
            if (st.selectedProperty instanceof ColorAnimatedProperty) ctx.zoom(e);
            else onCurveWheel(ctx, e, st);
        });
        return container;
    }

    /** Build the interactive sub-elements (clips / stops) of the expanded curve/color box for the currently
     *  selected property, each as an absolute-positioned child that owns its selection + drag + right-click. */
    private void addCurveBoxItems(TimelineContext ctx, AnimationTrack animation, AnimationTrackUIState st, UIElement box) {
        var property = st.selectedProperty;
        if (property == null || !animation.properties().contains(property)) return;
        if (property instanceof ColorAnimatedProperty color) {
            for (var clip : color.gradientClips()) box.addChild(createGradientClipElement(ctx, animation, color, st, clip, box));
            // stops added last so their (narrow) markers win hit-testing over the gradient clips they overlap
            for (var stop : color.stops()) box.addChild(createColorStopElement(ctx, animation, color, st, stop, box));
            return;
        }
        // expr clips (added first so the later curve clips win overlapping hit-tests, matching the old order)
        for (var axis : activeAxes(st)) {
            for (var clip : property.exprClips(axis)) box.addChild(createExprClipElement(ctx, animation, property, st, axis, clip, box));
        }
        if (property instanceof ConfigAnimatedProperty cfg) {
            for (var axis : activeAxes(st)) {
                for (var clip : cfg.curveClips(axis)) box.addChild(createCurveClipElement(ctx, animation, cfg, st, axis, clip, box));
            }
        }
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
        var label = new Label().setText(property.type().path());
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
        var label = new Label().setText(property.type().path() + "." + property.type().channelKey(axis));
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
        // group the not-yet-added properties into a nested tree by their dotted path() (e.g.
        // "config.physics.friction" -> config > physics > friction), so the menu branches by category
        var root = new MenuNode();
        var any = false;
        for (var type : fxObject.getFXObjectType().animatableProperties()) {
            if (track.property(type) != null) continue; // only show not-yet-added properties
            any = true;
            var segments = type.path().split("\\.");
            var node = root;
            for (int i = 0; i < segments.length - 1; i++) {
                node = node.children.computeIfAbsent(segments[i], k -> new MenuNode());
            }
            node.leaves.add(type);
        }
        if (!any) return;
        var menu = TreeBuilder.Menu.start();
        emitAddPropertyMenu(menu, root, ctx, track, st);
        ctx.openMenu(x, y, menu);
    }

    /** A node in the add-property menu tree: named sub-branches + leaf properties at this level. */
    private static final class MenuNode {
        final java.util.LinkedHashMap<String, MenuNode> children = new java.util.LinkedHashMap<>();
        final List<AnimatedPropertyType> leaves = new ArrayList<>();
    }

    private void emitAddPropertyMenu(TreeBuilder.Menu menu, MenuNode node, TimelineContext ctx,
                                     AnimationTrack track, AnimationTrackUIState st) {
        node.children.forEach((segment, child) ->
                menu.branch(segment, sub -> emitAddPropertyMenu(sub, child, ctx, track, st)));
        for (var type : node.leaves) {
            menu.leaf(Component.translatable(propertyKey(type)), () -> addProperty(ctx, track, st, type));
        }
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
            st.selectedExprClips.clear();
            st.selectedStop = null;
            st.selectedGradientClip = null;
            st.selectedGradientClips.clear();
            st.selectedCurveClip = null;
            st.selectedCurveClips.clear();
        }
        st.expandedProperties.remove(property);
    }

    /** Select a property (sets the active track for delete-routing, no track highlight) and inspect it.
     *  Rebuilds the timeline so the curve/color box re-creates its per-property clip/stop child elements —
     *  {@link #selectPropertyState} stays rebuild-free for the auto-select that runs during a build. */
    private void selectProperty(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                               AnimatedProperty property, int axis) {
        selectPropertyState(st, property, axis);
        st.explicitSelection = true;
        ctx.setActiveTrack(track);
        inspectChannel(ctx, track, property, axis);
        ctx.requestRebuild();
    }

    /** State-only selection (used on rebuild auto-select; does not touch the inspector or active track). */
    private void selectPropertyState(AnimationTrackUIState st, AnimatedProperty property, int axis) {
        st.selectedProperty = property;
        st.selectedAxis = axis;
        st.selKeyAxis = -1;
        st.selKeyIndex = -1;
        st.selectedKeys.clear();
        st.selectedExprClips.clear();
        st.selectedStop = null;
        st.selectedGradientClip = null;
        st.selectedGradientClips.clear();
        st.selectedCurveClip = null;
        st.selectedCurveClips.clear();
    }

    /** Inspect the channel a selection controls: the property type's own config (e.g. rotation interp
     *  mode) or the track config. The controlled channel is the selected sub-axis, or channel 0 for a
     *  single-channel property, or none (-1) for a multi-channel property row. Edits are undoable via the
     *  property copy/restoreFrom snapshot pattern. */
    private void inspectChannel(TimelineContext ctx, AnimationTrack track, AnimatedProperty property, int axis) {
        var channel = axis >= 0 ? axis : (property.channelCount() == 1 ? 0 : -1);
        var before = new AnimatedProperty[]{property.copy()};
        Runnable onChanged = () -> {
            var prev = before[0];
            var after = property.copy();
            before[0] = after;
            ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                    () -> { property.restoreFrom(after); ctx.refreshPreview(); },
                    () -> { property.restoreFrom(prev); ctx.refreshPreview(); });
            ctx.refreshPreview();
        };
        var cfg = IConfigurable.create(group -> {
            var typeCfg = property.inspect(onChanged);
            if (typeCfg != null) {
                typeCfg.buildConfigurator(group);
            } else if (channel < 0) {
                trackConfigurator(ctx, track).buildConfigurator(group);
            }
        });
        ctx.inspectProperty(track, cfg);
    }

    /** Inspect a selected expression clip: its {@code y = f(t)} source (t clip-local). Edits are undoable
     *  by snapshotting the clip's expression. */
    private void inspectExprClip(TimelineContext ctx, AnimationTrack track, AnimatedProperty property, int axis, ExprClip clip) {
        var before = new String[]{clip.expression()};
        var cfg = IConfigurable.create(group -> {
            var exprField = new StringConfigurator(
                    "photon.gui.editor.timeline.property.expression",
                    clip::expression,
                    expr -> {
                        var prev = before[0];
                        clip.expression(expr);
                        before[0] = expr;
                        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                                () -> { clip.expression(expr); ctx.refreshPreview(); },
                                () -> { clip.expression(prev); ctx.refreshPreview(); });
                        ctx.refreshPreview();
                    },
                    "", true).setTips(
                    Component.translatable("photon.gui.editor.timeline.property.expression.tips.0"),
                    Component.translatable("photon.gui.editor.timeline.property.expression.tips.1"),
                    Component.translatable("photon.gui.editor.timeline.property.expression.tips.2"));
            group.addConfigurator(exprField);
        });
        ctx.inspectProperty(track, cfg);
    }

    private static String propertyKey(AnimatedPropertyType type) {
        return type.displayNameKey();
    }

    private static ColorPattern channelColor(int axis) {
        return CHANNEL_COLORS[axis % CHANNEL_COLORS.length];
    }

    private static long encodeKey(int axis, int index) { return ((long) axis << 32) | (index & 0xffffffffL); }
    private static int keyAxis(long id) { return (int) (id >>> 32); }
    private static int keyIndex(long id) { return (int) id; }

    // ------------------------------------------------------------------ curve editor

    private float[] effectiveRange(AnimatedProperty property) {
        var fixedMin = property.type().fixedRangeMin();
        var min = fixedMin != null ? fixedMin : property.rangeMin(); // pinned bottom (e.g. speed's 0)
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

    /** Grab mode for a clip sub-element from the cursor x: 1 near its start edge, 2 near its end edge, else
     *  0 (body). Uses the element's own laid-out content rect. */
    private int edgeMode(float mouseX, UIElement el) {
        var x = el.getContentX();
        var w = el.getContentWidth();
        if (mouseX <= x + CLIP_EDGE_PX) return 1;
        if (mouseX >= x + w - CLIP_EDGE_PX) return 2;
        return 0;
    }

    /** Re-lay-out an absolute lane child spanning {@code [start, start+duration)} ticks (box-local left/width),
     *  hiding it entirely when scrolled fully off the box (culling). */
    private void repositionSpan(TimelineContext ctx, UIElement el, UIElement box, double startTick, double durationTicks) {
        var x0 = (float) ((startTick - ctx.scrollTicks()) * ctx.scale());
        var w = (float) Math.max(2, durationTicks * ctx.scale());
        var boxW = box.getContentWidth();
        el.setDisplay(x0 + w >= 0 && x0 <= boxW);
        el.layout(layout -> { layout.left(x0); layout.width(w); });
    }

    /** Ticks for a curve polyline: uniform across {@code [startTick, endTick]} PLUS each keyframe's exact
     *  tick, so a near-vertical jump (adjacent keys are clamped ~0.001 ticks apart) renders vertical instead
     *  of slanted across the 2px sampling step. Sorted ascending, de-duplicated. */
    protected List<Float> curvePolylineTicks(AnimatedProperty property, int axis, float startTick, float endTick, float stepTicks) {
        var set = new java.util.TreeSet<Float>();
        for (float t = startTick; t <= endTick; t += stepTicks) set.add(t);
        set.add(endTick);
        var count = property.keyCount(axis);
        for (int k = 0; k < count; k++) {
            var kx = property.key(axis, k).x;
            if (kx >= startTick && kx <= endTick) set.add(kx);
        }
        // expr clip edges (sampled on both sides so a curve↔expression discontinuity renders vertical)
        for (var clip : property.exprClips(axis)) {
            for (var edge : new float[]{(float) clip.start(), (float) clip.end()}) {
                if (edge >= startTick && edge <= endTick) {
                    set.add(edge);
                    set.add(Math.nextDown(edge));
                    set.add(Math.nextUp(edge));
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** Build a step (hold) staircase polyline for a discrete channel across {@code [scroll, endTick]}: a
     *  horizontal hold at each keyframe's value with a vertical riser at the next keyframe's tick. */
    private List<Vector2f> steppedPoints(TimelineContext ctx, AnimatedProperty property, int axis,
                                         float scroll, float endTick, float x, float y, float height, float min, float max) {
        var points = new ArrayList<Vector2f>();
        var prev = property.sampleChannelStepped(axis, scroll);
        points.add(new Vector2f(x, valueToCurveY(prev, y, height, min, max)));
        var count = property.keyCount(axis);
        for (int k = 0; k < count; k++) {
            var key = property.key(axis, k);
            if (key.x <= scroll || key.x > endTick) continue;
            var kx = x + (key.x - scroll) * ctx.scale();
            points.add(new Vector2f(kx, valueToCurveY(prev, y, height, min, max)));   // hold to the riser
            points.add(new Vector2f(kx, valueToCurveY(key.y, y, height, min, max)));  // vertical jump
            prev = key.y;
        }
        points.add(new Vector2f(x + (endTick - scroll) * ctx.scale(), valueToCurveY(prev, y, height, min, max)));
        return points;
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
        var scroll = ctx.scrollTicks();
        var endTick = scroll + width / ctx.scale();
        var axes = activeAxes(st);
        var step = 2 / ctx.scale();
        // expression clips + curve clips (f(t)->curve) are real child elements now (drawn over this box).
        var stepped = property.type().stepped();
        for (var axis : axes) {
            // effective value = expression clip override where present, else the keyframe curve; clip
            // boundaries are folded into the tick set so a curve↔expression jump renders cleanly.
            // Discrete (int/bool) channels render as a step (hold) staircase instead of a smooth curve.
            var points = stepped
                    ? steppedPoints(ctx, property, axis, scroll, endTick, x, y, height, min, max)
                    : new ArrayList<Vector2f>();
            if (!stepped) {
                for (var t : curvePolylineTicks(property, axis, scroll, endTick, step)) {
                    points.add(new Vector2f(x + (t - scroll) * ctx.scale(), valueToCurveY(property.sampleChannelValue(axis, t), y, height, min, max)));
                }
            }
            DrawerHelper.drawLines(graphics, points, channelColor(axis).color, channelColor(axis).color, 0.5f);
        }
        for (var axis : axes) {
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
        // tangent handles only when exactly one key is selected (never for stepped/discrete channels)
        if (!stepped && st.selectedKeys.size() == 1 && st.selKeyAxis >= 0 && isAxisActive(st, st.selKeyAxis)
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
            if (hit != null) {
                if (!track.lock()) removeKeyframe(ctx, track, st, property, hit[0], hit[1]);
                e.stopPropagation();
                return;
            }
            // expr/curve clips are elements (they own their own right-click); the box adds new clips.
            if (!track.lock()) openAddExprClipMenu(ctx, track, st, property, bx, e);
            e.stopPropagation();
            return;
        }
        if (e.button != 0 || track.lock()) return;
        // tangent handle drag (only when exactly one key is selected; stepped channels have no handles)
        if (!property.type().stepped() && st.selectedKeys.size() == 1 && st.selKeyAxis >= 0 && isAxisActive(st, st.selKeyAxis)) {
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
            clearOtherSubSelections(st, st.selectedKeys); // keyframe selection is exclusive with clips/stop (bug 2)
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
            return;
        }
        // expr/curve clips are elements now; an empty press → clear clip selection and start a keyframe marquee
        if (!e.isShiftDown()) st.selectedExprClips.clear();
        st.keyMarquee = true;
        st.keyMarqueeAdditive = e.isShiftDown();
        st.kmX0 = st.kmX1 = e.x;
        st.kmY0 = st.kmY1 = e.y;
        el.startDrag(null, null);
        e.stopPropagation();
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
            // a pinned bottom bound (e.g. speed's 0) is not editable
            if (property.type().fixedRangeMin() == null && e.y >= by + bh - 9) { openRangeEditor(ctx, el, property, false, bh); e.stopPropagation(); return; }
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
        clearOtherSubSelections(st, st.selectedKeys);
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
        var fixedMin = property.type().fixedRangeMin();
        float nmin = editingMax ? oldMin : v;
        float nmax = editingMax ? v : oldMax;
        if (fixedMin != null) {
            nmin = fixedMin;                       // bottom is pinned
            if (nmax <= nmin) nmax = nmin + 1;
        } else {
            if (nmax < nmin) { var t = nmin; nmin = nmax; nmax = t; } // new max below min → swap so min <= max
            if (nmax == nmin) nmax = nmin + 1;                        // avoid a zero-width range
        }
        if (nmin == oldMin && nmax == oldMax) return;
        var fMin = nmin;
        var fMax = nmax;
        property.setRange(fMin, fMax);
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.setRange(fMin, fMax); ctx.refreshPreview(); },
                () -> { property.setRange(oldMin, oldMax); ctx.refreshPreview(); });
        ctx.refreshPreview();
    }

    // ------------------------------------------------------------------ expression clips

    /** Select an expression clip (shift toggles membership) and inspect it. */
    private void selectExprClip(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                                AnimatedProperty property, int axis, ExprClip clip, boolean additive) {
        st.explicitSelection = true;
        ctx.setActiveTrack(track);
        clearOtherSubSelections(st, st.selectedExprClips);
        if (additive) {
            if (!st.selectedExprClips.remove(clip)) st.selectedExprClips.add(clip);
        } else if (!st.selectedExprClips.contains(clip)) {
            st.selectedExprClips.clear();
            st.selectedExprClips.add(clip);
        }
        inspectExprClip(ctx, track, property, axis, clip);
    }

    /** An expression clip as an absolute-positioned full-height child of the curve box: draws its region +
     *  expression text, owns select (shift toggles the multi-set), body-move / edge-resize, right-click menu. */
    private UIElement createExprClipElement(TimelineContext ctx, AnimationTrack track, AnimatedProperty property,
                                            AnimationTrackUIState st, int axis, ExprClip clip, UIElement box) {
        var el = new UIElement().setId("timeline.exprClip").layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left((float) ((clip.start() - ctx.scrollTicks()) * ctx.scale()));
            layout.width((float) Math.max(2, clip.duration() * ctx.scale()));
            layout.top(0);
            layout.heightPercent(100);
        }).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var selected = st.selectedExprClips.contains(clip);
                    var base = clip.error() != null ? ColorPattern.RED.color : channelColor(axis).color;
                    var invalid = st.subClipDragInvalid && st.clipDragOrigins.containsKey(clip);
                    DrawerHelper.drawSolidRect(graphics, x, y, w, h,
                            invalid ? ColorPattern.T_RED.color : withAlpha(base, selected ? 0x66 : 0x33));
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var error = clip.error() != null;
                    var selected = st.selectedExprClips.contains(clip);
                    var invalid = st.subClipDragInvalid && st.clipDragOrigins.containsKey(clip);
                    var base = error ? ColorPattern.RED.color : channelColor(axis).color;
                    DrawerHelper.drawBorder(graphics, x, y, w, h,
                            invalid ? ColorPattern.RED.color : selected ? ColorPattern.WHITE.color : base, 1);
                    if (w > 20) {
                        var text = error ? Component.translatable("photon.gui.editor.timeline.expression_error").getString() : clip.expression();
                        if (text != null && !text.isBlank()) {
                            DrawerHelper.drawText(graphics, text, x + 2, y + 1, 1f, (error ? ColorPattern.RED : ColorPattern.WHITE).color);
                        }
                    }
                    if (!track.lock() && mx >= x && mx <= x + w && my >= y && my <= y + h
                            && (mx <= x + CLIP_EDGE_PX || mx >= x + w - CLIP_EDGE_PX)) {
                        Icons.ARROW_LEFT_RIGHT.draw(graphics, mx, my, mx - 5, my - 5, 10, 10, pt);
                    }
                }));
        el.addEventListener(UIEvents.MOUSE_DOWN, e -> onExprClipMouseDown(ctx, e, track, property, st, axis, clip, el));
        el.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> onClipDrag(ctx, e, st));
        el.addEventListener(UIEvents.DRAG_END, e -> { onClipDragEnd(ctx, st); e.stopPropagation(); });
        ctx.registerLaneItem(el, () -> repositionSpan(ctx, el, box, clip.start(), clip.duration()));
        return el;
    }

    private void onExprClipMouseDown(TimelineContext ctx, UIEvent e, AnimationTrack track, AnimatedProperty property,
                                     AnimationTrackUIState st, int axis, ExprClip clip, UIElement el) {
        // keyframes (still box-drawn in Stage 1) keep grab priority over a clip they sit within: if one is
        // under the cursor, don't consume — let the press bubble up to the box's keyframe handler.
        if (keyframeUnderCursor(ctx, st, property, el, e)) return;
        ctx.setActiveTrack(track);
        st.explicitSelection = true;
        if (e.button == 1) {
            if (!track.lock()) openExprClipMenu(ctx, track, st, property, axis, clip, e.x, e.y);
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        e.stopPropagation();
        if (track.lock()) return;
        selectExprClip(ctx, track, st, property, axis, clip, e.isShiftDown());
        if (e.isShiftDown()) return; // shift toggles membership only (no drag)
        var bx = el.getParent().getContentX();
        beginClipDrag(ctx, st, property, axis, clip, edgeMode(e.x, el), curveXToTick(ctx, e.x, bx));
        el.startDrag(null, null);
    }

    private void beginClipDrag(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property,
                               int axis, ExprClip clip, int mode, float grabTick) {
        st.dragProperty = property;
        st.dragClip = clip;
        st.dragClipAxis = axis;
        st.dragClipMode = mode;
        st.dragClipGrabOffset = grabTick - clip.start();
        st.dragClipSnapshot = property.snapshotExprClips(axis);
        st.clipDragOrigins.clear();
        if (mode == 0) { // group move: capture every selected clip's origin
            for (var c : st.selectedExprClips) st.clipDragOrigins.put(c, new double[]{c.start(), c.duration()});
        } else {
            st.clipDragOrigins.put(clip, new double[]{clip.start(), clip.duration()});
        }
        st.subClipDragInvalid = false;
        ctx.beginScrub();
    }

    private void onClipDrag(TimelineContext ctx, UIEvent e, AnimationTrackUIState st) {
        if (st.dragClip == null || st.dragProperty == null || st.dragClipSnapshot == null) return;
        var property = st.dragProperty;
        var axis = st.dragClipAxis;
        var bx = e.currentElement.getParent().getContentX(); // element is the clip; its parent is the box
        var cursorTick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var anchorOrig = st.clipDragOrigins.get(st.dragClip);
        if (anchorOrig == null) return;
        property.restoreExprClips(axis, st.dragClipSnapshot); // reset then re-apply (no compounding)
        var ctrl = e.isCtrlDown();
        var exclude = st.clipDragOrigins.keySet(); // the moved clips don't snap to their own edges
        if (st.dragClipMode == 0) {
            // move the whole selection by a common Δtick, snapping the anchor's start/end, clamping tick >= 0
            var target = snapClipStart(ctx, cursorTick - st.dragClipGrabOffset, anchorOrig[1], ctrl, exclude);
            double dTick = target - anchorOrig[0];
            double lo = -Double.MAX_VALUE;
            for (var origin : st.clipDragOrigins.values()) lo = Math.max(lo, -origin[0]); // keep every start >= 0
            dTick = Math.max(dTick, lo);
            for (var entry : st.clipDragOrigins.entrySet()) {
                entry.getKey().start(entry.getValue()[0] + dTick);
            }
        } else if (st.dragClipMode == 1) {
            var origEnd = anchorOrig[0] + anchorOrig[1];
            var newStart = Math.min(Math.max(0, ctx.snapKeyTick(cursorTick, ctrl, exclude)), origEnd - MIN_EXPR_CLIP_TICKS);
            st.dragClip.start(newStart).duration(origEnd - newStart);
        } else {
            var newEnd = Math.max(anchorOrig[0] + MIN_EXPR_CLIP_TICKS, ctx.snapKeyTick(cursorTick, ctrl, exclude));
            st.dragClip.duration(newEnd - anchorOrig[0]);
        }
        st.subClipDragInvalid = exprDragOverlaps(st, property, axis); // overlap not allowed → flag red + revert
        ctx.setDragGuideTicks(st.dragClip.start(), st.dragClip.end()); // yellow edge guides across the lanes
        ctx.refreshLaneLayout(); // move the clip element(s) to follow the mutated ticks
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void onClipDragEnd(TimelineContext ctx, AnimationTrackUIState st) {
        var property = st.dragProperty;
        var axis = st.dragClipAxis;
        var before = st.dragClipSnapshot;
        var invalid = st.subClipDragInvalid;
        st.dragClip = null;
        st.dragProperty = null;
        st.dragClipSnapshot = null;
        st.clipDragOrigins.clear();
        st.subClipDragInvalid = false;
        ctx.endScrub();
        ctx.clearDragGuideTicks();
        if (property == null || before == null) return;
        if (invalid) { // overlapping drop → snap back to where the drag started
            property.restoreExprClips(axis, before);
            ctx.refreshLaneLayout();
            ctx.refreshPreview();
            return;
        }
        var after = property.snapshotExprClips(axis);
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { property.restoreExprClips(axis, after); ctx.refreshLaneLayout(); ctx.refreshPreview(); },
                () -> { property.restoreExprClips(axis, before); ctx.refreshLaneLayout(); ctx.refreshPreview(); });
    }

    /** Two tick ranges {@code [aStart,aEnd)} and {@code [bStart,bEnd)} overlap. */
    private static boolean rangesOverlap(double aStart, double aEnd, double bStart, double bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    /** True if any expr clip currently in the drag ({@link AnimationTrackUIState#clipDragOrigins}) overlaps a
     *  clip on the same axis that is NOT part of the drag (clips move together, so they can't newly self-overlap). */
    private boolean exprDragOverlaps(AnimationTrackUIState st, AnimatedProperty property, int axis) {
        var moved = st.clipDragOrigins.keySet();
        for (var clip : property.exprClips(axis)) {
            if (!moved.contains(clip)) continue;
            for (var other : property.exprClips(axis)) {
                if (other == clip || moved.contains(other)) continue;
                if (rangesOverlap(clip.start(), clip.end(), other.start(), other.end())) return true;
            }
        }
        return false;
    }

    private boolean gradientDragOverlaps(ColorAnimatedProperty color, GradientClip clip) {
        for (var other : color.gradientClips()) {
            if (other == clip) continue;
            if (rangesOverlap(clip.start(), clip.end(), other.start(), other.end())) return true;
        }
        return false;
    }

    private boolean curveDragOverlaps(ConfigAnimatedProperty cfg, int axis, CurveClip clip) {
        for (var other : cfg.curveClips(axis)) {
            if (other == clip) continue;
            if (rangesOverlap(clip.start(), clip.end(), other.start(), other.end())) return true;
        }
        return false;
    }

    /** Snap a moving clip's start, preferring whichever of its start/end lands on a snap target closer.
     *  {@code exclude} are the sub-clips being dragged (so they don't snap to their own edges). */
    private double snapClipStart(TimelineContext ctx, double start, double duration, boolean ctrl, java.util.Set<?> exclude) {
        var snapStart = ctx.snapKeyTick(start, ctrl, exclude);
        var snapEnd = ctx.snapKeyTick(start + duration, ctrl, exclude) - duration;
        var leftSnapped = snapStart != start;
        var rightSnapped = snapEnd != start;
        if (leftSnapped && (!rightSnapped || Math.abs(snapStart - start) <= Math.abs(snapEnd - start))) return snapStart;
        return rightSnapped ? snapEnd : start;
    }

    private void openAddExprClipMenu(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                                     AnimatedProperty property, float bx, UIEvent e) {
        if (activeAxes(st).length != 1) return; // add on a single selected sub-property only
        var axis = activeAxes(st)[0];
        var startTick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var snapped = ctx.snapKeyTick(startTick, e.isCtrlDown());
        var menu = TreeBuilder.Menu.start();
        menu.leaf(Component.translatable("photon.gui.editor.timeline.expr_clip.add"),
                () -> addExprClip(ctx, track, st, property, axis, snapped));
        if (property instanceof ConfigAnimatedProperty cfg) {
            menu.leaf(Component.translatable("photon.gui.editor.timeline.add_curve_clip"),
                    () -> addCurveClipEdit(ctx, track, st, cfg, axis, snapped));
        }
        ctx.openMenu(e.x, e.y, menu);
    }

    private void openExprClipMenu(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                                  AnimatedProperty property, int axis, ExprClip clip, float x, float y) {
        var menu = TreeBuilder.Menu.start();
        menu.leaf(Component.translatable("photon.gui.editor.timeline.expr_clip.remove"),
                () -> removeExprClipEdit(ctx, st, property, axis, clip));
        ctx.openMenu(x, y, menu);
    }

    private void addExprClip(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                             AnimatedProperty property, int axis, double startTick) {
        var before = property.snapshotExprClips(axis);
        var clip = new ExprClip(startTick, DEFAULT_EXPR_CLIP_TICKS, "");
        property.addExprClip(axis, clip);
        var after = property.snapshotExprClips(axis);
        st.selectedKeys.clear();
        st.selectedExprClips.clear();
        st.selectedExprClips.add(clip);
        inspectExprClip(ctx, track, property, axis, clip);
        pushRebuildEdit(ctx,
                () -> property.restoreExprClips(axis, after),
                () -> property.restoreExprClips(axis, before));
    }

    private void removeExprClipEdit(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property, int axis, ExprClip clip) {
        var before = property.snapshotExprClips(axis);
        property.removeExprClip(axis, clip);
        var after = property.snapshotExprClips(axis);
        st.selectedExprClips.remove(clip);
        pushRebuildEdit(ctx,
                () -> property.restoreExprClips(axis, after),
                () -> property.restoreExprClips(axis, before));
    }

    /** Remove every selected expression clip (grouped by axis), one undo. */
    private void removeSelectedExprClips(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property) {
        var byAxis = new HashMap<Integer, List<ExprClip>>();
        for (var clip : st.selectedExprClips) {
            var axis = findClipAxis(property, clip);
            if (axis >= 0) byAxis.computeIfAbsent(axis, a -> new ArrayList<>()).add(clip);
        }
        if (byAxis.isEmpty()) return;
        var beforeByAxis = new HashMap<Integer, List<ExprClip>>();
        for (var axis : byAxis.keySet()) beforeByAxis.put(axis, property.snapshotExprClips(axis));
        byAxis.forEach((axis, clips) -> clips.forEach(clip -> property.removeExprClip(axis, clip)));
        var afterByAxis = new HashMap<Integer, List<ExprClip>>();
        for (var axis : byAxis.keySet()) afterByAxis.put(axis, property.snapshotExprClips(axis));
        st.selectedExprClips.clear();
        pushRebuildEdit(ctx,
                () -> afterByAxis.forEach(property::restoreExprClips),
                () -> beforeByAxis.forEach(property::restoreExprClips));
    }

    private static int findClipAxis(AnimatedProperty property, ExprClip clip) {
        for (int axis = 0; axis < property.channelCount(); axis++) {
            if (property.exprClips(axis).contains(clip)) return axis;
        }
        return -1;
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
            var property = st.selectedProperty;
            var range = effectiveRange(property);
            var factor = e.deltaY > 0 ? 1 / 1.1f : 1.1f;
            var fixedMin = property.type().fixedRangeMin();
            if (fixedMin != null) {
                // pinned bottom (e.g. speed): scroll scales only the top bound
                property.setRange(fixedMin, fixedMin + Math.max(1e-3f, (range[1] - fixedMin) * factor));
            } else {
                var center = (range[0] + range[1]) / 2f;
                var half = Math.max(1e-3f, (range[1] - range[0]) / 2f * factor);
                property.setRange(center - half, center + half);
            }
            e.stopPropagation();
        } else {
            ctx.zoom(e);
        }
    }

    // ------------------------------------------------------------------ color / gradient editor

    /** Draw a horizontal gradient by stepping 2px columns and sampling the color at each column's tick. */
    private void drawGradientStrip(TimelineContext ctx, GuiGraphics graphics, ColorAnimatedProperty color,
                                   float x, float y, float width, float height) {
        var step = 2f;
        for (float cx = x; cx < x + width; cx += step) {
            var w = Math.min(step, x + width - cx);
            var tick = curveXToTick(ctx, cx + w / 2f, x);
            DrawerHelper.drawSolidRect(graphics, cx, y, w, height, color.sampleColor(tick));
        }
    }

    private static float colorBandTop(float boxY) {
        return boxY + 6;
    }

    private static float colorBandH(float boxH) {
        return Math.max(8, boxH - 20);
    }

    /** Draw a gradient across [x, x+width] by stepping columns and sampling {@code gc} over its [0,1]. */
    private void drawGradientColorRegion(GuiGraphics graphics, GradientColor gc, float x, float y, float width, float height) {
        var step = 2f;
        for (float cx = x; cx < x + width; cx += step) {
            var w = Math.min(step, x + width - cx);
            var frac = Math.max(0f, Math.min(1f, (cx + w / 2f - x) / width));
            DrawerHelper.drawSolidRect(graphics, cx, y, w, height, gc.getColor(frac));
        }
    }

    /** Full gradient-lane editor: a wide gradient band across the time axis + draggable color stops, with
     *  gradient clips (f(t)->gradient) overlaid as full-band regions. */
    private void drawColorEditor(TimelineContext ctx, GuiGraphics graphics, ColorAnimatedProperty color,
                                 AnimationTrackUIState st, float x, float y, float width, float height) {
        DrawerHelper.drawSolidRect(graphics, x, y, width, height, ColorPattern.BLACK.color);
        drawCurveGrid(ctx, graphics, x, y, width, height);
        var bandTop = colorBandTop(y);
        var bandH = colorBandH(height);
        drawGradientStrip(ctx, graphics, color, x, bandTop, width, bandH);
        // gradient clips + color stops (which override the stops in their range) are real child elements now.
    }

    private void onColorMouseDown(TimelineContext ctx, UIEvent e, AnimationTrack track, ColorAnimatedProperty color, AnimationTrackUIState st) {
        if (e.button == 0) { ctx.setActiveTrack(track); st.explicitSelection = true; }
        // gradient clips + stops are real elements now; the box only opens the empty-space add menu.
        if (e.button == 1 && !track.lock()) {
            openColorClipMenu(ctx, track, color, st, e.currentElement.getContentX(), e);
            e.stopPropagation();
        }
    }

    /** A gradient clip as an absolute-positioned child of the color box (spanning the gradient band): draws
     *  its gradient, owns shift/ctrl multi-select (bug 1), body-move / edge-resize drag, right-click remove. */
    private UIElement createGradientClipElement(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty color,
                                                AnimationTrackUIState st, GradientClip clip, UIElement box) {
        var el = new UIElement().setId("timeline.gradientClip").layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left((float) ((clip.start() - ctx.scrollTicks()) * ctx.scale()));
            layout.width((float) Math.max(2, clip.duration() * ctx.scale()));
            layout.top(6);      // band top (see colorBandTop)
            layout.bottom(14);  // band bottom (colorBandTop + colorBandH = y + h - 14)
        }).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    if (clip.gradient() != null) drawGradientColorRegion(graphics, clip.gradient(), x, y, w, h);
                    if (st.subClipDragInvalid && st.dragGradientClip == clip) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_RED.color); // overlapping drop is invalid
                    }
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var invalid = st.subClipDragInvalid && st.dragGradientClip == clip;
                    var sel = st.selectedGradientClips.contains(clip);
                    DrawerHelper.drawBorder(graphics, x, y, w, h,
                            invalid ? ColorPattern.RED.color : sel ? ColorPattern.WHITE.color : withAlpha(ColorPattern.WHITE.color, 0x88), 1);
                    if (!track.lock() && mx >= x && mx <= x + w && my >= y && my <= y + h
                            && (mx <= x + CLIP_EDGE_PX || mx >= x + w - CLIP_EDGE_PX)) {
                        Icons.ARROW_LEFT_RIGHT.draw(graphics, mx, my, mx - 5, my - 5, 10, 10, pt);
                    }
                }));
        el.addEventListener(UIEvents.MOUSE_DOWN, e -> onGradientClipMouseDown(ctx, e, track, color, st, clip, el));
        el.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> onGradientClipDrag(ctx, e, st));
        el.addEventListener(UIEvents.DRAG_END, e -> { onGradientClipDragEnd(ctx, st); e.stopPropagation(); });
        ctx.registerLaneItem(el, () -> repositionSpan(ctx, el, box, clip.start(), clip.duration()));
        return el;
    }

    private void onGradientClipMouseDown(TimelineContext ctx, UIEvent e, AnimationTrack track,
                                         ColorAnimatedProperty color, AnimationTrackUIState st, GradientClip clip, UIElement el) {
        ctx.setActiveTrack(track);
        st.explicitSelection = true;
        if (e.button == 1) {
            if (!track.lock()) removeGradientClipEdit(ctx, st, color, clip);
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        e.stopPropagation();
        if (track.lock()) return;
        clearOtherSubSelections(st, st.selectedGradientClips); // exclusive with keys/expr/curve/stop
        if (e.isShiftDown() || e.isCtrlDown()) { // bug 1: toggle this clip in the multi-selection (no drag)
            if (!st.selectedGradientClips.remove(clip)) st.selectedGradientClips.add(clip);
            st.selectedGradientClip = clip;
            return;
        }
        // keep the multi-selection when grabbing one of its members; otherwise select just this clip
        if (!st.selectedGradientClips.contains(clip)) {
            st.selectedGradientClips.clear();
            st.selectedGradientClips.add(clip);
        }
        st.selectedGradientClip = clip;
        inspectGradientClip(ctx, track, clip);
        var bx = el.getParent().getContentX();
        beginGradientClipDrag(ctx, st, color, clip, edgeMode(e.x, el), curveXToTick(ctx, e.x, bx));
        el.startDrag(null, null);
    }

    private void beginGradientClipDrag(TimelineContext ctx, AnimationTrackUIState st, ColorAnimatedProperty color, GradientClip clip, int mode, float cursorTick) {
        st.dragGradientClip = clip;
        st.dragGradientClipMode = mode;
        st.dragGradientClipGrabOffset = cursorTick - clip.start();
        st.gradientClipDragSnapshot = color.snapshotGradientClips();
        st.subClipDragInvalid = false;
        ctx.beginScrub();
    }

    private void onGradientClipDrag(TimelineContext ctx, UIEvent e, AnimationTrackUIState st) {
        if (st.dragGradientClip == null || !(st.selectedProperty instanceof ColorAnimatedProperty color)) return;
        var bx = e.currentElement.getParent().getContentX(); // element is the clip; its parent is the box
        var clip = st.dragGradientClip;
        var cursorTick = curveXToTick(ctx, e.x, bx);
        var ctrl = e.isCtrlDown();
        var exclude = java.util.Set.of(clip); // don't snap the clip to its own moving edges
        if (st.dragGradientClipMode == 0) { // move
            var start = Math.max(0, cursorTick - st.dragGradientClipGrabOffset);
            clip.start(Math.max(0, ctx.snapKeyTick(start, ctrl, exclude)));
        } else if (st.dragGradientClipMode == 1) { // resize start, keep end fixed
            var end = clip.end();
            var newStart = Math.min(Math.max(0, ctx.snapKeyTick(cursorTick, ctrl, exclude)), end - MIN_EXPR_CLIP_TICKS);
            clip.start(newStart).duration(end - newStart);
        } else { // resize end
            var newEnd = Math.max(clip.start() + MIN_EXPR_CLIP_TICKS, ctx.snapKeyTick(cursorTick, ctrl, exclude));
            clip.duration(newEnd - clip.start());
        }
        st.subClipDragInvalid = gradientDragOverlaps(color, clip); // overlap not allowed → flag red + revert
        ctx.setDragGuideTicks(clip.start(), clip.end()); // yellow edge guides across the lanes
        ctx.refreshLaneLayout(); // move the gradient-clip element to follow the mutated ticks
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void onGradientClipDragEnd(TimelineContext ctx, AnimationTrackUIState st) {
        if (st.dragGradientClip == null || st.gradientClipDragSnapshot == null
                || !(st.selectedProperty instanceof ColorAnimatedProperty color)) {
            st.dragGradientClip = null;
            st.gradientClipDragSnapshot = null;
            st.subClipDragInvalid = false;
            return;
        }
        var before = st.gradientClipDragSnapshot;
        var invalid = st.subClipDragInvalid;
        st.dragGradientClip = null;
        st.gradientClipDragSnapshot = null;
        st.subClipDragInvalid = false;
        ctx.endScrub();
        ctx.clearDragGuideTicks();
        if (invalid) { // overlapping drop → snap back to where the drag started
            color.restoreGradientClips(before);
            ctx.refreshLaneLayout();
            ctx.refreshPreview();
            return;
        }
        var after = color.snapshotGradientClips();
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { color.restoreGradientClips(after); clearGradientClipSelection(st); ctx.refreshLaneLayout(); ctx.refreshPreview(); },
                () -> { color.restoreGradientClips(before); clearGradientClipSelection(st); ctx.refreshLaneLayout(); ctx.refreshPreview(); });
    }

    private static void clearGradientClipSelection(AnimationTrackUIState st) {
        st.selectedGradientClip = null;
        st.selectedGradientClips.clear();
    }

    private void selectGradientClip(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty color,
                                    AnimationTrackUIState st, GradientClip clip) {
        clearOtherSubSelections(st, st.selectedGradientClips);
        st.selectedGradientClip = clip;
        st.selectedGradientClips.clear();
        st.selectedGradientClips.add(clip);
        st.explicitSelection = true;
        ctx.setActiveTrack(track);
        inspectGradientClip(ctx, track, clip);
    }

    /** Inspect a selected gradient clip: its {@link GradientColor} via the LDLib2 gradient editor. */
    private void inspectGradientClip(TimelineContext ctx, AnimationTrack track, GradientClip clip) {
        var before = new GradientColor[]{clip.gradient().copy()};
        var cfg = IConfigurable.create(group -> group.addConfigurator(new GradientColorConfigurator(
                "photon.gui.editor.timeline.property.color",
                () -> clip.gradient().copy(),
                gc -> {
                    var prev = before[0];
                    copyGradientInto(clip.gradient(), gc);
                    var after = clip.gradient().copy();
                    before[0] = after;
                    ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                            () -> { copyGradientInto(clip.gradient(), after); ctx.refreshPreview(); },
                            () -> { copyGradientInto(clip.gradient(), prev); ctx.refreshPreview(); });
                    ctx.refreshPreview();
                }, clip.gradient().copy(), true)));
        ctx.inspectProperty(track, cfg);
    }

    /** Deep-copy the gradient stops of {@code src} into {@code target} (keeps {@code target}'s identity). */
    private static void copyGradientInto(GradientColor target, GradientColor src) {
        target.getAP().clear();
        for (var v : src.getAP()) target.getAP().add(new Vector2f(v));
        target.getRgbP().clear();
        for (var v : src.getRgbP()) target.getRgbP().add(new Vector4f(v));
    }

    private void removeGradientClipEdit(TimelineContext ctx, AnimationTrackUIState st, ColorAnimatedProperty color, GradientClip clip) {
        var before = color.snapshotGradientClips();
        color.gradientClips().remove(clip);
        var after = color.snapshotGradientClips();
        clearGradientClipSelection(st);
        pushRebuildEdit(ctx,
                () -> { color.restoreGradientClips(after); clearGradientClipSelection(st); },
                () -> { color.restoreGradientClips(before); clearGradientClipSelection(st); });
    }

    private void removeSelectedGradientClips(TimelineContext ctx, AnimationTrackUIState st, ColorAnimatedProperty color) {
        if (st.selectedGradientClips.isEmpty()) return;
        var before = color.snapshotGradientClips();
        color.gradientClips().removeAll(st.selectedGradientClips);
        var after = color.snapshotGradientClips();
        clearGradientClipSelection(st);
        pushRebuildEdit(ctx,
                () -> { color.restoreGradientClips(after); clearGradientClipSelection(st); },
                () -> { color.restoreGradientClips(before); clearGradientClipSelection(st); });
    }

    private void openColorClipMenu(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty color,
                                   AnimationTrackUIState st, float bx, UIEvent e) {
        var tick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var menu = TreeBuilder.Menu.start();
        menu.leaf(Component.translatable("photon.gui.editor.timeline.add_gradient_clip"),
                () -> addGradientClipEdit(ctx, track, color, st, tick));
        ctx.openMenu(e.x, e.y, menu);
    }

    private void addGradientClipEdit(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty color,
                                     AnimationTrackUIState st, float tick) {
        var argb = color.sampleColor(tick);
        var clip = new GradientClip(tick, DEFAULT_EXPR_CLIP_TICKS, new GradientColor(argb, argb));
        var before = color.snapshotGradientClips();
        color.gradientClips().add(clip);
        var after = color.snapshotGradientClips();
        selectGradientClip(ctx, track, color, st, clip);
        pushRebuildEdit(ctx,
                () -> { color.restoreGradientClips(after); clearGradientClipSelection(st); },
                () -> { color.restoreGradientClips(before); clearGradientClipSelection(st); });
    }

    private void onColorDoubleClick(TimelineContext ctx, UIEvent e, AnimationTrack track, ColorAnimatedProperty color, AnimationTrackUIState st) {
        if (track.lock()) return;
        var bx = e.currentElement.getContentX();
        var tick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var argb = color.sampleColor(tick);
        var before = color.snapshotStops();
        var created = color.addStop(tick, argb);
        var after = color.snapshotStops();
        selectStop(ctx, track, color, st, created);
        pushRebuildEdit(ctx,
                () -> { color.restoreStops(after); st.selectedStop = null; },
                () -> { color.restoreStops(before); st.selectedStop = null; });
        e.stopPropagation();
    }

    private void onColorDrag(TimelineContext ctx, UIEvent e, AnimationTrackUIState st) {
        if (st.dragStop == null || !(st.selectedProperty instanceof ColorAnimatedProperty color)) return;
        var bx = e.currentElement.getParent().getContentX(); // element is the stop marker; its parent is the box
        var tick = (float) Math.max(0, ctx.snapKeyTick(Math.max(0, curveXToTick(ctx, e.x, bx)), e.isCtrlDown()));
        st.dragStop.tick = tick;
        color.sort();
        ctx.refreshLaneLayout(); // move the stop marker element to follow the mutated tick
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void onColorDragEnd(TimelineContext ctx, AnimationTrackUIState st) {
        if (st.dragStop == null || st.stopDragSnapshot == null || !(st.selectedProperty instanceof ColorAnimatedProperty color)) {
            st.dragStop = null;
            st.stopDragSnapshot = null;
            return;
        }
        var before = st.stopDragSnapshot;
        var after = color.snapshotStops();
        st.dragStop = null;
        st.stopDragSnapshot = null;
        ctx.endScrub();
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { color.restoreStops(after); st.selectedStop = null; ctx.refreshLaneLayout(); ctx.refreshPreview(); },
                () -> { color.restoreStops(before); st.selectedStop = null; ctx.refreshLaneLayout(); ctx.refreshPreview(); });
    }

    private void removeStopEdit(TimelineContext ctx, AnimationTrackUIState st, ColorAnimatedProperty color, ColorAnimatedProperty.ColorKey stop) {
        if (color.stops().size() <= 1) return; // keep at least one stop
        var before = color.snapshotStops();
        color.removeStop(stop);
        var after = color.snapshotStops();
        st.selectedStop = null;
        pushRebuildEdit(ctx,
                () -> { color.restoreStops(after); st.selectedStop = null; },
                () -> { color.restoreStops(before); st.selectedStop = null; });
    }

    /** A color stop as an absolute-positioned full-height marker child of the color box: draws the band tick
     *  line + the swatch marker, owns select + body-drag (move tick) + right-click remove. */
    private UIElement createColorStopElement(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty color,
                                             AnimationTrackUIState st, ColorAnimatedProperty.ColorKey stop, UIElement box) {
        var el = new UIElement().setId("timeline.colorStop").layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left((float) ((stop.tick - ctx.scrollTicks()) * ctx.scale()) - 4);
            layout.width(8);
            layout.top(0);
            layout.heightPercent(100);
        }).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var cx = x + w / 2f;
                    var bandTop = colorBandTop(y);
                    var bandH = colorBandH(h);
                    var markerY = bandTop + bandH;
                    var selected = st.selectedStop == stop;
                    DrawerHelper.drawSolidRect(graphics, cx - 0.5f, bandTop, 1, bandH, withAlpha(ColorPattern.WHITE.color, selected ? 0xFF : 0x66));
                    DrawerHelper.drawSolidRect(graphics, cx - 4, markerY + 1, 8, 6, (selected ? ColorPattern.WHITE : ColorPattern.GRAY).color);
                    DrawerHelper.drawSolidRect(graphics, cx - 3, markerY + 2, 6, 4, 0xFF000000 | (stop.argb & 0xFFFFFF));
                }));
        el.addEventListener(UIEvents.MOUSE_DOWN, e -> onColorStopMouseDown(ctx, e, track, color, st, stop, el));
        el.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> onColorDrag(ctx, e, st));
        el.addEventListener(UIEvents.DRAG_END, e -> { onColorDragEnd(ctx, st); e.stopPropagation(); });
        // double-clicking an existing stop must not add a new one on top of it
        el.addEventListener(UIEvents.DOUBLE_CLICK, UIEvent::stopPropagation);
        ctx.registerLaneItem(el, () -> {
            var cx = (float) ((stop.tick - ctx.scrollTicks()) * ctx.scale());
            el.setDisplay(cx >= -8 && cx <= box.getContentWidth() + 8);
            el.layout(layout -> layout.left(cx - 4));
        });
        return el;
    }

    private void onColorStopMouseDown(TimelineContext ctx, UIEvent e, AnimationTrack track, ColorAnimatedProperty color,
                                      AnimationTrackUIState st, ColorAnimatedProperty.ColorKey stop, UIElement el) {
        ctx.setActiveTrack(track);
        st.explicitSelection = true;
        if (e.button == 1) {
            if (!track.lock()) removeStopEdit(ctx, st, color, stop);
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        e.stopPropagation();
        if (track.lock()) return;
        selectStop(ctx, track, color, st, stop);
        st.dragStop = stop;
        st.stopDragSnapshot = color.snapshotStops();
        ctx.beginScrub();
        el.startDrag(null, null);
    }

    /** Select a color stop (active track for delete, no track highlight) and inspect its color. */
    private void selectStop(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty color,
                            AnimationTrackUIState st, ColorAnimatedProperty.ColorKey stop) {
        clearOtherSubSelections(st, STOP_TOKEN); // stop selection is exclusive with clips/keys
        st.selectedStop = stop;
        st.explicitSelection = true;
        ctx.setActiveTrack(track);
        inspectColorStop(ctx, track, stop);
    }

    /** Inspect a selected color stop: an ARGB {@link ColorConfigurator}, undoable per change. */
    private void inspectColorStop(TimelineContext ctx, AnimationTrack track, ColorAnimatedProperty.ColorKey stop) {
        var before = new int[]{stop.argb};
        var cfg = IConfigurable.create(group -> group.addConfigurator(new ColorConfigurator(
                "photon.gui.editor.timeline.property.color",
                () -> stop.argb,
                v -> {
                    var prev = before[0];
                    stop.argb = v;
                    before[0] = v;
                    ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                            () -> { stop.argb = v; ctx.refreshPreview(); },
                            () -> { stop.argb = prev; ctx.refreshPreview(); });
                    ctx.refreshPreview();
                }, stop.argb, true)));
        ctx.inspectProperty(track, cfg);
    }

    // ------------------------------------------------------------------ curve clips (config NF / NF3)

    /** Draw the clip's curve as a polyline over its span, auto-fit to the curve's sampled value range. */
    private void drawClipCurvePreview(GuiGraphics graphics, CurveClip clip, float x0, float x1,
                                      float boxX, float y, float width, float height, int color) {
        var curve = clip.curve();
        if (curve == null || x1 <= x0) return;
        var n = 48;
        var vals = new float[n + 1];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            float v;
            try {
                v = curve.get(i / (float) n, () -> 0f).floatValue();
            } catch (Exception e) {
                v = 0;
            }
            vals[i] = v;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (hi - lo < 1e-4f) { lo -= 0.5f; hi += 0.5f; }
        var pts = new ArrayList<Vector2f>();
        for (int i = 0; i <= n; i++) {
            var px = x0 + (x1 - x0) * (i / (float) n);
            if (px < boxX - 2 || px > boxX + width + 2) continue;
            var py = y + height * (1 - (vals[i] - lo) / (hi - lo));
            pts.add(new Vector2f(px, py));
        }
        if (pts.size() > 1) DrawerHelper.drawLines(graphics, pts, color, color, 0.5f);
    }

    private static void clearCurveClipSelection(AnimationTrackUIState st) {
        st.selectedCurveClip = null;
        st.selectedCurveClips.clear();
    }

    private void selectCurveClip(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                                 ConfigAnimatedProperty cfg, int axis, CurveClip clip, boolean additive) {
        st.explicitSelection = true;
        ctx.setActiveTrack(track);
        clearOtherSubSelections(st, st.selectedCurveClips);
        if (additive) {
            if (!st.selectedCurveClips.remove(clip)) st.selectedCurveClips.add(clip);
        } else if (!st.selectedCurveClips.contains(clip)) {
            st.selectedCurveClips.clear();
            st.selectedCurveClips.add(clip);
        }
        st.selectedCurveClip = clip;
        inspectCurveClip(ctx, track, clip, curveConfigFor(cfg));
    }

    /** A curve clip (f(t)->curve) as an absolute-positioned full-height child of the curve box: draws its
     *  curve preview, owns select (shift toggles the multi-set), body-move / edge-resize, right-click remove. */
    private UIElement createCurveClipElement(TimelineContext ctx, AnimationTrack track, ConfigAnimatedProperty cfg,
                                             AnimationTrackUIState st, int axis, CurveClip clip, UIElement box) {
        var el = new UIElement().setId("timeline.curveClip").layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left((float) ((clip.start() - ctx.scrollTicks()) * ctx.scale()));
            layout.width((float) Math.max(2, clip.duration() * ctx.scale()));
            layout.top(0);
            layout.heightPercent(100);
        }).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var selected = st.selectedCurveClips.contains(clip);
                    var base = channelColor(axis).color;
                    var invalid = st.subClipDragInvalid && st.dragCurveClip == clip;
                    DrawerHelper.drawSolidRect(graphics, x, y, w, h,
                            invalid ? ColorPattern.T_RED.color : withAlpha(base, selected ? 0x44 : 0x22));
                    drawClipCurvePreview(graphics, clip, x, x + w, x, y, w, h, base);
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var invalid = st.subClipDragInvalid && st.dragCurveClip == clip;
                    var selected = st.selectedCurveClips.contains(clip);
                    var base = channelColor(axis).color;
                    DrawerHelper.drawBorder(graphics, x, y, w, h,
                            invalid ? ColorPattern.RED.color : selected ? ColorPattern.WHITE.color : base, 1);
                    if (!track.lock() && mx >= x && mx <= x + w && my >= y && my <= y + h
                            && (mx <= x + CLIP_EDGE_PX || mx >= x + w - CLIP_EDGE_PX)) {
                        Icons.ARROW_LEFT_RIGHT.draw(graphics, mx, my, mx - 5, my - 5, 10, 10, pt);
                    }
                }));
        el.addEventListener(UIEvents.MOUSE_DOWN, e -> onCurveClipMouseDown(ctx, e, track, cfg, st, axis, clip, el));
        el.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> onCurveClipDrag(ctx, e, st));
        el.addEventListener(UIEvents.DRAG_END, e -> { onCurveClipDragEnd(ctx, st); e.stopPropagation(); });
        ctx.registerLaneItem(el, () -> repositionSpan(ctx, el, box, clip.start(), clip.duration()));
        return el;
    }

    private void onCurveClipMouseDown(TimelineContext ctx, UIEvent e, AnimationTrack track, ConfigAnimatedProperty cfg,
                                      AnimationTrackUIState st, int axis, CurveClip clip, UIElement el) {
        if (keyframeUnderCursor(ctx, st, cfg, el, e)) return; // keyframes keep grab priority (see onExprClipMouseDown)
        ctx.setActiveTrack(track);
        st.explicitSelection = true;
        if (e.button == 1) {
            if (!track.lock()) removeCurveClipEdit(ctx, st, cfg, axis, clip);
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        e.stopPropagation();
        if (track.lock()) return;
        selectCurveClip(ctx, track, st, cfg, axis, clip, e.isShiftDown());
        if (e.isShiftDown()) return; // shift toggles membership only (no drag)
        var bx = el.getParent().getContentX();
        beginCurveClipDrag(ctx, st, cfg, axis, clip, edgeMode(e.x, el), curveXToTick(ctx, e.x, bx));
        el.startDrag(null, null);
    }

    private void beginCurveClipDrag(TimelineContext ctx, AnimationTrackUIState st, ConfigAnimatedProperty cfg,
                                    int axis, CurveClip clip, int mode, float grabTick) {
        st.dragCurveClipProperty = cfg;
        st.dragCurveClip = clip;
        st.dragCurveClipAxis = axis;
        st.dragCurveClipMode = mode;
        st.dragCurveClipGrabOffset = grabTick - clip.start();
        st.curveClipDragSnapshot = cfg.snapshotCurveClips(axis);
        st.subClipDragInvalid = false;
        ctx.beginScrub();
    }

    private void onCurveClipDrag(TimelineContext ctx, UIEvent e, AnimationTrackUIState st) {
        if (st.dragCurveClip == null || st.dragCurveClipProperty == null) return;
        var bx = e.currentElement.getParent().getContentX(); // element is the clip; its parent is the box
        var clip = st.dragCurveClip;
        var cursorTick = Math.max(0, curveXToTick(ctx, e.x, bx));
        var ctrl = e.isCtrlDown();
        var exclude = java.util.Set.of(clip); // don't snap the clip to its own moving edges
        if (st.dragCurveClipMode == 0) {
            var start = Math.max(0, cursorTick - st.dragCurveClipGrabOffset);
            clip.start(Math.max(0, snapClipStart(ctx, start, clip.duration(), ctrl, exclude)));
        } else if (st.dragCurveClipMode == 1) {
            var end = clip.end();
            var newStart = Math.min(Math.max(0, ctx.snapKeyTick(cursorTick, ctrl, exclude)), end - MIN_EXPR_CLIP_TICKS);
            clip.start(newStart).duration(end - newStart);
        } else {
            var newEnd = Math.max(clip.start() + MIN_EXPR_CLIP_TICKS, ctx.snapKeyTick(cursorTick, ctrl, exclude));
            clip.duration(newEnd - clip.start());
        }
        st.subClipDragInvalid = curveDragOverlaps(st.dragCurveClipProperty, st.dragCurveClipAxis, clip); // overlap not allowed
        ctx.setDragGuideTicks(clip.start(), clip.end()); // yellow edge guides across the lanes
        ctx.refreshLaneLayout(); // move the curve-clip element to follow the mutated ticks
        ctx.refreshPreview();
        e.stopPropagation();
    }

    private void onCurveClipDragEnd(TimelineContext ctx, AnimationTrackUIState st) {
        var cfg = st.dragCurveClipProperty;
        var axis = st.dragCurveClipAxis;
        var before = st.curveClipDragSnapshot;
        var invalid = st.subClipDragInvalid;
        st.dragCurveClip = null;
        st.dragCurveClipProperty = null;
        st.curveClipDragSnapshot = null;
        st.subClipDragInvalid = false;
        ctx.endScrub();
        ctx.clearDragGuideTicks();
        if (cfg == null || before == null) return;
        if (invalid) { // overlapping drop → snap back to where the drag started
            cfg.restoreCurveClips(axis, before);
            ctx.refreshLaneLayout();
            ctx.refreshPreview();
            return;
        }
        var after = cfg.snapshotCurveClips(axis);
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { cfg.restoreCurveClips(axis, after); ctx.refreshLaneLayout(); ctx.refreshPreview(); },
                () -> { cfg.restoreCurveClips(axis, before); ctx.refreshLaneLayout(); ctx.refreshPreview(); });
    }

    /** The backing config field's {@link NumberFunctionConfig} (real value range/axes), or a generic default. */
    private static NumberFunctionConfig curveConfigFor(ConfigAnimatedProperty cfg) {
        if (cfg.type() instanceof ConfigPropertyType cpt) {
            var c = cpt.numberFunctionConfig();
            if (c != null) return c;
        }
        return CURVE_CLIP_CONFIG;
    }

    private void addCurveClipEdit(TimelineContext ctx, AnimationTrack track, AnimationTrackUIState st,
                                  ConfigAnimatedProperty cfg, int axis, double startTick) {
        var curve = new Curve();
        curve.loadConfig(curveConfigFor(cfg)); // seed the value range/default from the real config field
        var clip = new CurveClip(startTick, DEFAULT_EXPR_CLIP_TICKS, curve);
        var before = cfg.snapshotCurveClips(axis);
        cfg.curveClips(axis).add(clip);
        var after = cfg.snapshotCurveClips(axis);
        selectCurveClip(ctx, track, st, cfg, axis, clip, false);
        pushRebuildEdit(ctx,
                () -> { cfg.restoreCurveClips(axis, after); clearCurveClipSelection(st); },
                () -> { cfg.restoreCurveClips(axis, before); clearCurveClipSelection(st); });
    }

    private void removeCurveClipEdit(TimelineContext ctx, AnimationTrackUIState st, ConfigAnimatedProperty cfg, int axis, CurveClip clip) {
        var before = cfg.snapshotCurveClips(axis);
        cfg.curveClips(axis).remove(clip);
        var after = cfg.snapshotCurveClips(axis);
        clearCurveClipSelection(st);
        pushRebuildEdit(ctx,
                () -> { cfg.restoreCurveClips(axis, after); clearCurveClipSelection(st); },
                () -> { cfg.restoreCurveClips(axis, before); clearCurveClipSelection(st); });
    }

    private void removeSelectedCurveClips(TimelineContext ctx, AnimationTrackUIState st, ConfigAnimatedProperty cfg) {
        if (st.selectedCurveClips.isEmpty()) return;
        var n = cfg.channelCount();
        var before = new ArrayList<List<CurveClip>>();
        for (int a = 0; a < n; a++) before.add(cfg.snapshotCurveClips(a));
        for (int a = 0; a < n; a++) cfg.curveClips(a).removeAll(st.selectedCurveClips);
        var after = new ArrayList<List<CurveClip>>();
        for (int a = 0; a < n; a++) after.add(cfg.snapshotCurveClips(a));
        clearCurveClipSelection(st);
        pushRebuildEdit(ctx,
                () -> { for (int a = 0; a < n; a++) cfg.restoreCurveClips(a, after.get(a)); clearCurveClipSelection(st); },
                () -> { for (int a = 0; a < n; a++) cfg.restoreCurveClips(a, before.get(a)); clearCurveClipSelection(st); });
    }

    /** Inspect a selected curve clip: edit its {@code Curve} via a {@link NumberFunctionConfigurator} using
     *  the backing config field's real value range/axes. */
    private void inspectCurveClip(TimelineContext ctx, AnimationTrack track, CurveClip clip, NumberFunctionConfig config) {
        var before = new NumberFunction[]{clip.curve() == null ? new Curve() : clip.curve().copy()};
        var cfg = IConfigurable.create(group -> group.addConfigurator(new NumberFunctionConfigurator(
                "photon.gui.editor.timeline.property.curve",
                clip::curve,
                newFn -> {
                    var prev = before[0];
                    clip.curve(newFn);
                    var after = newFn == null ? new Curve() : newFn.copy();
                    before[0] = after;
                    ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                            () -> { clip.curve(after.copy()); ctx.refreshPreview(); },
                            () -> { clip.curve(prev.copy()); ctx.refreshPreview(); });
                    ctx.refreshPreview();
                }, true, config)));
        ctx.inspectProperty(track, cfg);
    }

    // ------------------------------------------------------------------ sub-selection copy / paste

    private enum SubKind { GRADIENT, CURVE, EXPR, STOP }
    private static SubKind clipboardKind;
    private static final List<GradientClip> clipboardGradientClips = new ArrayList<>();
    private static final List<CurveClip> clipboardCurveClips = new ArrayList<>();
    private static final List<ExprClip> clipboardExprClips = new ArrayList<>();
    private static final List<ColorAnimatedProperty.ColorKey> clipboardStops = new ArrayList<>();
    /** The earliest start/tick of the copied items; paste shifts them so this lands at the playhead. */
    private static double clipboardAnchor;

    @Override
    public boolean copySubSelection(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        if (!st.selectedGradientClips.isEmpty()) {
            clipboardGradientClips.clear();
            clipboardAnchor = Double.MAX_VALUE;
            for (var c : st.selectedGradientClips) { clipboardGradientClips.add(c.copy()); clipboardAnchor = Math.min(clipboardAnchor, c.start()); }
            clipboardKind = SubKind.GRADIENT;
            return true;
        }
        if (!st.selectedCurveClips.isEmpty()) {
            clipboardCurveClips.clear();
            clipboardAnchor = Double.MAX_VALUE;
            for (var c : st.selectedCurveClips) { clipboardCurveClips.add(c.copy()); clipboardAnchor = Math.min(clipboardAnchor, c.start()); }
            clipboardKind = SubKind.CURVE;
            return true;
        }
        if (!st.selectedExprClips.isEmpty()) {
            clipboardExprClips.clear();
            clipboardAnchor = Double.MAX_VALUE;
            for (var c : st.selectedExprClips) { clipboardExprClips.add(c.copy()); clipboardAnchor = Math.min(clipboardAnchor, c.start()); }
            clipboardKind = SubKind.EXPR;
            return true;
        }
        if (st.selectedStop != null) {
            clipboardStops.clear();
            clipboardStops.add(st.selectedStop.copy());
            clipboardAnchor = st.selectedStop.tick;
            clipboardKind = SubKind.STOP;
            return true;
        }
        return false;
    }

    @Override
    public boolean pasteSubSelection(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        var property = st.selectedProperty;
        if (property == null || clipboardKind == null || track.lock()) return false;
        var delta = Math.max(0, ctx.currentTimeTicks()) - clipboardAnchor;
        var axes = activeAxes(st);
        var axis = axes.length == 1 ? axes[0] : 0;
        switch (clipboardKind) {
            case GRADIENT -> {
                if (!(property instanceof ColorAnimatedProperty color) || clipboardGradientClips.isEmpty()) return false;
                var before = color.snapshotGradientClips();
                for (var c : clipboardGradientClips) color.gradientClips().add(c.copy().start(Math.max(0, c.start() + delta)));
                pushSubEdit(ctx, () -> color.restoreGradientClips(before), color::snapshotGradientClips, color::restoreGradientClips, st);
                return true;
            }
            case CURVE -> {
                if (!(property instanceof ConfigAnimatedProperty cfg) || clipboardCurveClips.isEmpty()) return false;
                var before = cfg.snapshotCurveClips(axis);
                for (var c : clipboardCurveClips) cfg.curveClips(axis).add(c.copy().start(Math.max(0, c.start() + delta)));
                var after = cfg.snapshotCurveClips(axis);
                pushAxisEdit(ctx, st, () -> cfg.restoreCurveClips(axis, after), () -> cfg.restoreCurveClips(axis, before));
                return true;
            }
            case EXPR -> {
                if (clipboardExprClips.isEmpty()) return false;
                var before = property.snapshotExprClips(axis);
                for (var c : clipboardExprClips) property.addExprClip(axis, c.copy().start(Math.max(0, c.start() + delta)));
                var after = property.snapshotExprClips(axis);
                pushAxisEdit(ctx, st, () -> property.restoreExprClips(axis, after), () -> property.restoreExprClips(axis, before));
                return true;
            }
            case STOP -> {
                if (!(property instanceof ColorAnimatedProperty color) || clipboardStops.isEmpty()) return false;
                var before = color.snapshotStops();
                for (var s : clipboardStops) color.addStop((float) Math.max(0, s.tick + delta), s.argb);
                pushSubEdit(ctx, () -> color.restoreStops(before), color::snapshotStops, color::restoreStops, st);
                return true;
            }
        }
        return false;
    }

    /** Push an already-applied structural sub-item edit (add/remove of a clip or stop, which changes the
     *  box's child-element set) so both the just-applied change and later redo/undo rebuild the box. */
    private void pushRebuildEdit(TimelineContext ctx, Runnable redo, Runnable undo) {
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { redo.run(); ctx.requestRebuild(); ctx.refreshPreview(); },
                () -> { undo.run(); ctx.requestRebuild(); ctx.refreshPreview(); });
        ctx.requestRebuild();
        ctx.refreshPreview();
    }

    /** Push an undoable paste for a whole-list sub-selection (gradient clips / color stops). */
    private <T> void pushSubEdit(TimelineContext ctx, Runnable restoreBefore,
                                 java.util.function.Supplier<List<T>> snapshot, java.util.function.Consumer<List<T>> restore, AnimationTrackUIState st) {
        var after = snapshot.get();
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { restore.accept(after); clearGradientClipSelection(st); clearCurveClipSelection(st); st.selectedStop = null; ctx.requestRebuild(); ctx.refreshPreview(); },
                () -> { restoreBefore.run(); clearGradientClipSelection(st); clearCurveClipSelection(st); st.selectedStop = null; ctx.requestRebuild(); ctx.refreshPreview(); });
        ctx.requestRebuild();
        ctx.refreshPreview();
    }

    /** Push an undoable paste for a per-axis sub-selection (curve clips / expr clips). */
    private void pushAxisEdit(TimelineContext ctx, AnimationTrackUIState st, Runnable redo, Runnable undo) {
        ctx.pushApplied("photon.gui.editor.timeline.edit_curve",
                () -> { redo.run(); clearCurveClipSelection(st); st.selectedExprClips.clear(); ctx.requestRebuild(); ctx.refreshPreview(); },
                () -> { undo.run(); clearCurveClipSelection(st); st.selectedExprClips.clear(); ctx.requestRebuild(); ctx.refreshPreview(); });
        ctx.requestRebuild();
        ctx.refreshPreview();
    }

    /** True if a keyframe of {@code property} sits under the cursor, hit-tested in the box coordinate space
     *  (the clip element's parent is the box). Used so full-height clip elements yield to keyframe grabs. */
    private boolean keyframeUnderCursor(TimelineContext ctx, AnimationTrackUIState st, AnimatedProperty property, UIElement el, UIEvent e) {
        var box = el.getParent();
        if (box == null) return false;
        return hitKey(ctx, st, property, box.getContentX(), box.getContentY(), box.getContentHeight(),
                effectiveRange(property), e.x, e.y) != null;
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
        clearOtherSubSelections(st, st.selectedKeys); // a marquee selects keyframes, exclusive with clips/stop
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
