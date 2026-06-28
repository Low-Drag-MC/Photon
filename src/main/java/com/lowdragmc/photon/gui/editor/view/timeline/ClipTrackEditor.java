package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;

/**
 * Shared base for clip-based tracks (activator, control): the lane with draggable / edge-resizable
 * clips, snapping, overlap rejection, selection and the clip inspector. Subclasses provide the header
 * content and the per-type clip creation / labelling / menu.
 */
public abstract class ClipTrackEditor extends TrackEditor {
    private static final int DRAG_MOVE = 0;
    private static final int DRAG_LEFT = 1;
    private static final int DRAG_RIGHT = 2;

    /** Per-clip-track transient drag state. */
    public static class ClipTrackUIState extends TrackUIState {
        @Nullable Clip draggingClip;
        int dragMode = DRAG_MOVE;
        double grabOffsetTicks, dragFixedEnd, dragBeforeStart, dragBeforeDuration;
        boolean dragInvalid;
    }

    @Override
    public ClipTrackUIState createState() {
        return new ClipTrackUIState();
    }

    /** Translucent clip fill color (border uses {@link #chipColor()}). */
    public abstract ColorPattern clipFillColor();

    /** Optional label drawn inside a clip (e.g. the bound object's name for control clips). */
    @Nullable
    protected String clipLabel(TimelineContext ctx, Track track, Clip clip) {
        return null;
    }

    /** Right-click on an empty part of the lane. */
    protected void onLaneRightClick(TimelineContext ctx, Track track, float x, float y) {
    }

    /** Double-click on an empty part of the lane. */
    protected void onLaneDoubleClick(TimelineContext ctx, Track track, float x, float y) {
    }

    /** A drag dropped onto the lane (e.g. an fx object dragged onto a control lane). */
    protected void onLaneDrop(TimelineContext ctx, Track track, UIEvent event) {
    }

    /** Extra clip context-menu entries (e.g. control seed options). */
    protected void buildClipMenu(TreeBuilder.Menu menu, TimelineContext ctx, Track track, Clip clip) {
    }

    /** Extra clip inspector configurators (e.g. control seed/randomSeed). */
    protected void buildClipConfigurator(com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup group,
                                         TimelineContext ctx, Track track, Clip clip) {
    }

    @Override
    public double contentMaxTick(Track track) {
        double max = 0;
        for (var clip : track.clips()) {
            max = Math.max(max, clip.end());
        }
        return max;
    }

    @Override
    public boolean deleteSelection(TimelineContext ctx, Track track, TrackUIState state) {
        var clip = ctx.selectedClip();
        if (clip != null && ctx.selectedClipTrack() == track) {
            if (track.lock()) return true; // selection consumed, but locked
            ctx.editor().inspectorView.clear();
            ctx.removeClip(track, clip);
            return true;
        }
        return false;
    }

    @Override
    public UIElement buildLane(TimelineContext ctx, Track track, TrackUIState state) {
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
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> ctx.drawPlayhead(graphics, x, y, w, h, pt)));
        lane.addEventListener(UIEvents.MOUSE_WHEEL, ctx::zoom);
        lane.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) {
                ctx.selectTrack(track);
            } else if (e.button == 1 && !track.lock()) {
                onLaneRightClick(ctx, track, e.x, e.y);
            }
        });
        lane.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
            if (!track.lock()) onLaneDoubleClick(ctx, track, e.x, e.y);
        });
        lane.addEventListener(UIEvents.DRAG_PERFORM, e -> onLaneDrop(ctx, track, e));
        for (var clip : track.clips()) {
            lane.addChild(createClipElement(ctx, track, (ClipTrackUIState) state, clip));
        }
        return lane;
    }

    private UIElement createClipElement(TimelineContext ctx, Track track, ClipTrackUIState st, Clip clip) {
        var baseColor = chipColor();
        var fillColor = clipFillColor();
        var clipHeight = TimelineContext.ROW_HEIGHT - 6;
        var element = new UIElement().setId("timeline.clip").layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(ctx.tickToLocalX(clip.start()));
            layout.top((TimelineContext.ROW_HEIGHT - clipHeight) / 2f);
            layout.width((float) Math.max(2, clip.duration() * ctx.scale()));
            layout.height(clipHeight);
            layout.paddingAll(2);
        }).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var invalid = st.draggingClip == clip && st.dragInvalid;
                    DrawerHelper.drawSolidRect(graphics, x + 1, y, Math.max(1, w - 2), h,
                            (invalid ? ColorPattern.T_RED : fillColor).color);
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var bx = x + 1;
                    var bw = Math.max(1, w - 2);
                    var invalid = st.draggingClip == clip && st.dragInvalid;
                    DrawerHelper.drawBorder(graphics, bx, y, bw, h, (invalid ? ColorPattern.RED : baseColor).color, 1);
                    if (ctx.isClipSelected(clip) && !invalid) {
                        DrawerHelper.drawBorder(graphics, bx, y, bw, h, ColorPattern.WHITE.color, 1);
                    }
                    if (!track.lock() && my >= y && my <= y + h && (mx <= bx + TimelineContext.EDGE_PX || mx >= bx + bw - TimelineContext.EDGE_PX)
                            && mx >= bx && mx <= bx + bw) {
                        Icons.ARROW_LEFT_RIGHT.draw(graphics, mx, my, mx - 5, my - 5, 10, 10, pt);
                    }
                }));
        ctx.registerClipView(clip, element);

        var label = clipLabel(ctx, track, clip);
        if (label != null) {
            var labelEl = new com.lowdragmc.lowdraglib2.gui.ui.elements.Label().setText(label);
            TimelineContext.styleLabel(labelEl);
            labelEl.layout(layout -> layout.flex(1).heightPercent(100));
            element.addChild(labelEl);
        }

        element.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 1) {
                ctx.selectClip(track, clip);
                if (!track.lock()) {
                    var menu = TreeBuilder.Menu.start();
                    buildClipMenu(menu, ctx, track, clip);
                    menu.leaf("ldlib.gui.editor.menu.remove", () -> ctx.removeClip(track, clip));
                    ctx.openMenu(e.x, e.y, menu);
                }
                e.stopPropagation();
                return;
            }
            ctx.selectClip(track, clip);
            if (track.lock()) {
                e.stopPropagation();
                return;
            }
            var localX = e.x - element.getPositionX();
            var w = element.getSizeWidth();
            st.dragMode = localX <= TimelineContext.EDGE_PX ? DRAG_LEFT : (localX >= w - TimelineContext.EDGE_PX ? DRAG_RIGHT : DRAG_MOVE);
            st.grabOffsetTicks = ctx.xToTick(e.x) - clip.start();
            st.dragFixedEnd = clip.end();
            st.dragBeforeStart = clip.start();
            st.dragBeforeDuration = clip.duration();
            st.draggingClip = clip;
            st.dragInvalid = false;
            ctx.setDragGuide(clip);
            ctx.beginScrub();
            element.startDrag(null, null);
            e.stopPropagation();
        });
        element.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            var ctrl = e.isCtrlDown();
            var cursorTick = ctx.xToTick(e.x);
            if (st.dragMode == DRAG_RIGHT) {
                var end = ctx.snapTick(cursorTick, clip, ctrl);
                clip.duration(Math.max(1, Math.round(end - clip.start())));
            } else if (st.dragMode == DRAG_LEFT) {
                var start = ctx.snapTick(cursorTick - st.grabOffsetTicks, clip, ctrl);
                start = Math.max(0, Math.min(start, st.dragFixedEnd - 1));
                clip.start(Math.round(start));
                clip.duration(Math.max(1, Math.round(st.dragFixedEnd - clip.start())));
            } else {
                var start = ctx.snapMoveStart(cursorTick - st.grabOffsetTicks, clip.duration(), clip, ctrl);
                clip.start(Math.max(0, Math.round(start)));
            }
            st.dragInvalid = overlaps(track, clip);
            applyClipLayout(ctx, element, clip);
            ctx.refreshPreview();
        });
        element.addEventListener(UIEvents.DRAG_END, e -> {
            st.draggingClip = null;
            ctx.setDragGuide(null);
            ctx.endScrub();
            var beforeStart = st.dragBeforeStart;
            var beforeDuration = st.dragBeforeDuration;
            if (st.dragInvalid) {
                st.dragInvalid = false;
                clip.start(beforeStart).duration(beforeDuration);
                applyClipLayout(ctx, element, clip);
                ctx.refreshPreview();
            } else {
                var afterStart = clip.start();
                var afterDuration = clip.duration();
                if (beforeStart != afterStart || beforeDuration != afterDuration) {
                    ctx.pushApplied("photon.gui.editor.timeline.edit_clip",
                            () -> { clip.start(afterStart).duration(afterDuration); ctx.requestRebuild(); ctx.refreshPreview(); },
                            () -> { clip.start(beforeStart).duration(beforeDuration); ctx.requestRebuild(); ctx.refreshPreview(); });
                }
            }
        });
        return element;
    }

    /** Whether {@code clip} overlaps another clip on the same lane. Activator: any overlap; control
     *  overrides to only conflict with same-target clips. */
    protected boolean overlaps(Track track, Clip clip) {
        for (var other : track.clips()) {
            if (other == clip) continue;
            if (clip.start() < other.end() && other.start() < clip.end()) {
                return true;
            }
        }
        return false;
    }

    private void applyClipLayout(TimelineContext ctx, UIElement element, Clip clip) {
        element.layout(layout -> {
            layout.left(ctx.tickToLocalX(clip.start()));
            layout.width((float) Math.max(2, clip.duration() * ctx.scale()));
        });
    }

    @Override
    public IConfigurable trackConfigurator(TimelineContext ctx, Track track) {
        return IConfigurable.create(group -> {
            buildHeaderConfigurator(group, ctx, track);
            group.addConfigurator(new BooleanConfigurator("mute", track::mute,
                    v -> { track.mute(v); ctx.requestRebuild(); ctx.refreshPreview(); }, track.mute(), true));
            group.addConfigurator(new BooleanConfigurator("lock", track::lock,
                    v -> { track.lock(v); ctx.requestRebuild(); }, track.lock(), true));
        });
    }

    /** Extra track configurators above mute/lock (e.g. the control track name). */
    protected void buildHeaderConfigurator(com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup group,
                                           TimelineContext ctx, Track track) {
    }

    /** Build the clip inspector (start/duration + per-type extras). */
    public IConfigurable clipConfigurator(TimelineContext ctx, Track track, Clip clip) {
        return IConfigurable.create(group -> {
            group.addConfigurator(new NumberConfigurator("start", clip::start,
                    v -> { clip.start(Math.max(0, v.doubleValue())); ctx.requestRebuild(); ctx.refreshPreview(); },
                    clip.start(), true).setRange(0, 1_000_000));
            group.addConfigurator(new NumberConfigurator("duration", clip::duration,
                    v -> { clip.duration(Math.max(1, v.doubleValue())); ctx.requestRebuild(); ctx.refreshPreview(); },
                    clip.duration(), true).setRange(1, 1_000_000));
            buildClipConfigurator(group, ctx, track, clip);
        });
    }
}
