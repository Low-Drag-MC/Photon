package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.CommandEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.view.timeline.ClipTrackEditor;
import com.lowdragmc.photon.gui.editor.view.timeline.TimelineContext;
import com.lowdragmc.photon.gui.editor.view.timeline.TrackEditor;
import com.lowdragmc.photon.gui.editor.view.timeline.TrackUIState;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unity/Premiere-style timeline editor panel and the generic host for the extensible track framework.
 * The chrome (transport, {@link SplitView}, two synced {@link ScrollerView}s, ruler, h-scroll,
 * zoom/scroll, reorder, selection, history) lives here; all type-specific UI/behavior is delegated to a
 * {@link TrackEditor} resolved from the {@code photon:track_editor} registry by track name. This class
 * implements {@link TimelineContext} — the seam editors talk to.
 */
public class FXTimelineView extends View implements TimelineContext {
    public static final int RULER_HEIGHT = 14;
    public static final int HSCROLL_HEIGHT = 8;
    public static final float DEFAULT_SCALE = 2.0f;
    public static final float MIN_SCALE = 0.05f;
    public static final float MAX_SCALE = 50.0f;
    /** Trailing padding (ticks) added past the last clip so you can scroll a little beyond it. */
    public static final int CONTENT_PAD = 10;
    /** Snap threshold in screen pixels (scale-independent, so zooming gives finer control). */
    public static final float SNAP_PX = 6;
    /** Minimum height of an expanded panel when dragging its resize handle. */
    public static final int MIN_EXPANDED = 32;

    /** A clip's view element, kept so horizontal scroll/zoom can reposition without a full rebuild. */
    private record ClipView(Clip clip, UIElement element) {}
    /** Drag payload used to reorder tracks by their header. */
    private record TrackDrag(Track track) {}

    public final FXEditor fxEditor;
    private final Button playButton = new Button();
    private final ScrollerView leftScroller = new ScrollerView();
    private final ScrollerView rightScroller = new ScrollerView();
    private final UIElement headersContainer = new UIElement();
    private final UIElement lanesContainer = new UIElement();
    private final UIElement ruler = new UIElement();
    private final Scroller hScroll = new Scroller.Horizontal();
    private final List<ClipView> clipViews = new ArrayList<>();

    /** Per-track UI state (editors are reused directly from the registry). */
    private final Map<Track, TrackUIState> states = new HashMap<>();

    /** Horizontal scale (pixels per tick) and left-most visible tick. */
    private float scale = DEFAULT_SCALE;
    private float scrollTicks = 0;
    private boolean syncingScroll = false;

    // drag/scrub state
    private boolean wasPlaying = false;
    private long previewTime = 0;
    @Nullable
    private Clip dragGuideClip;
    @Nullable
    private Track reorderTarget;
    private boolean reorderBelow = false;

    // selection
    @Nullable
    private Clip selectedClip;
    @Nullable
    private Track selectedClipTrack;
    @Nullable
    private Track selectedTrack;
    /** The track currently in record mode (Unity-style), or null. */
    @Nullable
    private Track recordingTrack;

    /** Clipboards for copy/paste (deep copies), shared across the panel. */
    @Nullable
    private static Track clipboardTrack;
    @Nullable
    private static Clip clipboardClip;

    public FXTimelineView(FXEditor fxEditor) {
        super("editor.timeline", new TextTexture("TL"));
        this.fxEditor = fxEditor;
        setId("timeline");
        setFocusable(true);
        getLayout().widthPercent(100);
        getLayout().heightPercent(100);
        getLayout().flexDirection(FlexDirection.COLUMN);

        addChild(createTransportBar());
        addChild(createSplit());

        addEventListener(UIEvents.KEY_DOWN, this::onKeyDown);
        addEventListener(UIEvents.EXECUTE_COMMAND, this::onCommand);
        addEventListener(UIEvents.TICK, e -> { updateHScroller(); pollRecording(); });
    }

    // ------------------------------------------------------------------ TimelineContext

    @Override public FXEditor editor() { return fxEditor; }
    @Override @Nullable public FXRuntime runtime() { return fxEditor.runtime; }
    @Override public float scale() { return scale; }
    @Override public float scrollTicks() { return scrollTicks; }
    @Override public long currentTimeTicks() { return fxEditor.sceneView.particleManager.getRealTime(); }
    @Override public double majorTickInterval() { return niceInterval(60 / scale); }
    @Override public void pushEdit(String name, Runnable doFn, Runnable undoFn) {
        fxEditor.historyView.pushHistory(Component.translatable(name), EditAction.of(doFn, undoFn));
    }
    @Override public void pushApplied(String name, Runnable redo, Runnable undo) {
        fxEditor.historyView.pushHistory(Component.translatable(name), EditAction.of(redo, undo), false);
    }
    @Override public void refreshPreview() { fxEditor.sceneView.simulateTo(currentTimeTicks()); }
    @Override public void openMenu(float x, float y, TreeBuilder.Menu menu) { fxEditor.openMenu(x, y, menu); }
    @Override public void requestRebuild() { rebuild(); }
    @Override public boolean isTrackSelected(Track track) {
        return selectedTrack == track && selectedClip == null && !subSelectionActive(track);
    }
    private boolean subSelectionActive(Track track) {
        var editor = editorFor(track);
        var state = states.get(track);
        return editor != null && state != null && editor.hasSubSelection(state);
    }
    @Override @Nullable public Clip selectedClip() { return selectedClip; }
    @Override @Nullable public Track selectedClipTrack() { return selectedClipTrack; }
    @Override public boolean isClipSelected(Clip clip) { return selectedClip == clip; }
    @Override public void registerClipView(Clip clip, UIElement element) { clipViews.add(new ClipView(clip, element)); }
    @Override public void setDragGuide(@Nullable Clip clip) { dragGuideClip = clip; }
    @Override public void zoom(UIEvent event) { onZoom(event); }

    @Override
    public void drawPlayhead(GuiGraphics graphics, float x, float y, float width, float height, float partialTick) {
        var playheadX = originX() + (fxEditor.sceneView.particleManager.getRealTime(partialTick) - scrollTicks) * scale;
        if (playheadX < x || playheadX > x + width) return;
        DrawerHelper.drawSolidRect(graphics, playheadX, y, 1, height, ColorPattern.RED.color);
    }

    @Override
    public void beginScrub() {
        var pm = fxEditor.sceneView.particleManager;
        wasPlaying = pm.isPlaying();
        previewTime = pm.getRealTime();
        pm.pause();
    }

    @Override
    public void endScrub() {
        fxEditor.sceneView.simulateTo(previewTime);
        if (wasPlaying) fxEditor.sceneView.particleManager.play();
    }

    // ------------------------------------------------------------------ layout

    private UIElement createTransportBar() {
        var bar = new UIElement().setId("timeline.transport").layout(layout -> {
            layout.widthPercent(100);
            layout.height(14);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
        });
        playButton.setText("photon.gui.editor.timeline.play").setOnClick(e -> togglePlay());
        playButton.setId("timeline.play").layout(layout -> layout.width(46))
                .addEventListener(UIEvents.TICK, e -> playButton.text.setText(Component.translatable(
                        isPlaying() ? "photon.gui.editor.timeline.pause" : "photon.gui.editor.timeline.play")));
        var stopButton = new Button().setText("photon.gui.editor.timeline.stop").setOnClick(e -> stop());
        stopButton.setId("timeline.stop").layout(layout -> layout.width(46));
        var timeLabel = new Label().setText("0.0s").layout(layout -> layout.flex(1).alignSelf(AlignItems.CENTER))
                .addEventListener(UIEvents.TICK, e -> ((Label) e.currentElement)
                        .setText("%.2fs".formatted(currentTimeTicks() / 20f)));
        return bar.addChildren(playButton, stopButton, timeLabel);
    }

    private UIElement createSplit() {
        var split = new SplitView.Horizontal();
        split.setId("timeline.split");
        split.getLayout().widthPercent(100);
        split.getLayout().flex(1);
        split.setPercentage(22);
        split.left(createLeftColumn());
        split.right(createRightColumn());
        return split;
    }

    private UIElement createLeftColumn() {
        var column = new UIElement().setId("timeline.left").layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
        });
        var header = new UIElement().setId("timeline.addTrackBar").layout(layout -> {
            layout.widthPercent(100);
            layout.height(RULER_HEIGHT);
            layout.flexDirection(FlexDirection.ROW);
        }).style(style -> style.backgroundTexture(ColorPattern.T_GRAY.rectTexture()));
        var addBtn = new Button().setText("+").setOnClick(e -> openAddTrackMenu(e.x, e.y));
        addBtn.setId("timeline.addTrack").layout(layout -> layout.width(14).height(RULER_HEIGHT))
                .style(style -> style.tooltips("photon.gui.editor.timeline.add_track"));
        header.addChild(addBtn);
        leftScroller.setId("timeline.headers");
        leftScroller.getLayout().widthPercent(100);
        leftScroller.getLayout().flex(1);
        leftScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
                .verticalScrollDisplay(ScrollDisplay.NEVER).horizontalScrollDisplay(ScrollDisplay.NEVER));
        headersContainer.layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(1);
        });
        // right-click anywhere in the left panel (incl. empty space below the tracks) to add a track
        leftScroller.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 1) { openAddTrackMenu(e.x, e.y); e.stopPropagation(); }
        });
        leftScroller.addScrollViewChild(headersContainer);
        leftScroller.verticalScroller.setOnValueChanged(v -> syncScroll(leftScroller, rightScroller));
        var spacer = new UIElement().layout(layout -> layout.widthPercent(100).height(HSCROLL_HEIGHT));
        return column.addChildren(header, leftScroller, spacer);
    }

    private UIElement createRightColumn() {
        var column = new UIElement().setId("timeline.right").layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
        });
        ruler.setId("timeline.ruler").layout(layout -> {
            layout.widthPercent(100);
            layout.height(RULER_HEIGHT);
        }).setOverflowVisible(false).style(style -> style.backgroundTexture(this::drawRuler))
                .addEventListener(UIEvents.MOUSE_DOWN, e -> { e.currentElement.startDrag(null, null); scrubTo(e); })
                .addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::scrubTo)
                .addEventListener(UIEvents.MOUSE_WHEEL, this::onZoom);
        rightScroller.setId("timeline.lanes");
        rightScroller.getLayout().widthPercent(100);
        rightScroller.getLayout().flex(1);
        rightScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
                .verticalScrollDisplay(ScrollDisplay.AUTO).horizontalScrollDisplay(ScrollDisplay.NEVER));
        lanesContainer.layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(1);
        });
        // cross-track snap guides at the dragged clip's edges spanning all lanes
        lanesContainer.style(style -> style.overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
            if (dragGuideClip == null) return;
            drawGuideLine(graphics, dragGuideClip.start(), x, y, w, h);
            drawGuideLine(graphics, dragGuideClip.end(), x, y, w, h);
        }));
        rightScroller.addScrollViewChild(lanesContainer);
        rightScroller.verticalScroller.setOnValueChanged(v -> syncScroll(rightScroller, leftScroller));

        hScroll.setId("timeline.hscroll");
        hScroll.headButton.setDisplay(false);
        hScroll.tailButton.setDisplay(false);
        hScroll.setRange(0, 0.0001f).setValue(0f, false)
                .setOnValueChanged(v -> {
                    scrollTicks = Float.isFinite(v) ? Math.max(0, v) : 0;
                    repositionClips();
                }).layout(layout -> layout.widthPercent(100).height(HSCROLL_HEIGHT));
        return column.addChildren(ruler, rightScroller, hScroll);
    }

    @Override
    public float originX() {
        var x = rightScroller.viewPort.getContentX();
        return x != 0 ? x : ruler.getContentX();
    }

    private void syncScroll(ScrollerView from, ScrollerView to) {
        if (syncingScroll) return;
        syncingScroll = true;
        to.verticalScroller.setValue(from.verticalScroller.getValue(), true);
        syncingScroll = false;
    }

    // ------------------------------------------------------------------ horizontal scale / scroll

    private float viewWidth() {
        var w = rightScroller.viewPort.getContentWidth();
        return w > 1 ? w : ruler.getContentWidth();
    }

    private double contentMaxTick() {
        var runtime = fxEditor.runtime;
        if (runtime == null) return 0;
        double max = 0;
        for (var track : runtime.fxData.timeline().tracks()) {
            var editor = editorFor(track);
            if (editor != null) max = Math.max(max, editor.contentMaxTick(track));
        }
        return max;
    }

    private void updateHScroller() {
        var viewW = viewWidth();
        if (viewW <= 1) return;
        var visibleTicks = viewW / scale;
        var contentMax = contentMaxTick();
        double total;
        if (contentMax <= visibleTicks) {
            total = visibleTicks;
            scrollTicks = 0;
        } else {
            total = contentMax + CONTENT_PAD;
        }
        if (total < 1) total = 1;
        var maxScroll = (float) Math.max(0, total - visibleTicks);
        scrollTicks = Math.max(0, Math.min(scrollTicks, maxScroll));
        hScroll.setRange(0, maxScroll > 0 ? maxScroll : 0.0001f);
        hScroll.setValue(scrollTicks, false);
        hScroll.setScrollBarSize((float) Math.max(5, Math.min(99, visibleTicks / total * 100)));
    }

    private void onZoom(UIEvent event) {
        var viewW = viewWidth();
        if (viewW <= 1) return;
        var tickUnder = xToTick(event.x);
        var factor = event.deltaY > 0 ? 1.1f : 1 / 1.1f;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        scrollTicks = (float) Math.max(0, tickUnder - (event.x - originX()) / scale);
        updateHScroller();
        repositionClips();
        event.stopPropagation();
    }

    @Override
    public float tickToLocalX(double tick) {
        return (float) ((tick - scrollTicks) * scale);
    }

    @Override
    public double xToTick(float mouseX) {
        return (mouseX - originX()) / scale + scrollTicks;
    }

    private void repositionClips() {
        for (var view : clipViews) {
            view.element().layout(layout -> {
                layout.left(tickToLocalX(view.clip().start()));
                layout.width((float) Math.max(2, view.clip().duration() * scale));
            });
        }
    }

    // ------------------------------------------------------------------ ruler drawing

    private void drawRuler(GuiGraphics graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTick) {
        DrawerHelper.drawSolidRect(graphics, x, y, width, height, ColorPattern.BLACK.color);
        var origin = originX();
        var visibleTicks = width / scale;
        var major = niceInterval(60 / scale);
        var minor = major / 5;
        var endTick = scrollTicks + visibleTicks;
        if (minor * scale >= 4) {
            for (double t = Math.floor(scrollTicks / minor) * minor; t <= endTick; t += minor) {
                if (t < 0) continue;
                var mx = origin + (float) ((t - scrollTicks) * scale);
                if (mx < x || mx > x + width) continue;
                DrawerHelper.drawSolidRect(graphics, mx, y + height * 0.6f, 1, height * 0.4f, ColorPattern.T_GRAY.color);
            }
        }
        for (double t = Math.floor(scrollTicks / major) * major; t <= endTick; t += major) {
            if (t < 0) continue;
            var mx = origin + (float) ((t - scrollTicks) * scale);
            if (mx < x || mx > x + width) continue;
            DrawerHelper.drawSolidRect(graphics, mx, y, 1, height, ColorPattern.GRAY.color);
            DrawerHelper.drawText(graphics, String.valueOf(Math.round(t)), mx + 2, y + 3, 0.5f, ColorPattern.WHITE.color);
        }
        drawContentExtent(graphics, x, y, width, height);
        drawPlayhead(graphics, x, y, width, height, partialTick);
    }

    /** A 1px blue bar along the ruler's bottom edge, spanning tick 0 to the furthest content
     *  (last keyframe / last clip end), so authors can see the effect's total extent at a glance. */
    private void drawContentExtent(GuiGraphics graphics, float x, float y, float width, float height) {
        var contentMax = contentMaxTick();
        if (contentMax <= 0) return;
        var origin = originX();
        var startX = Math.max(x, origin + (float) ((0 - scrollTicks) * scale));
        var endX = Math.min(x + width, origin + (float) ((contentMax - scrollTicks) * scale));
        if (endX <= startX) return;
        DrawerHelper.drawSolidRect(graphics, startX, y + height - 1, endX - startX, 1, ColorPattern.BLUE.color);
    }

    private double niceInterval(double raw) {
        if (raw < 1) return 1;
        var pow = Math.pow(10, Math.floor(Math.log10(raw)));
        var n = raw / pow;
        var nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return nice * pow;
    }

    private void drawGuideLine(GuiGraphics graphics, double tick, float x, float y, float width, float height) {
        var lx = originX() + (float) ((tick - scrollTicks) * scale);
        if (lx < x || lx > x + width) return;
        DrawerHelper.drawSolidRect(graphics, lx, y, 1, height, ColorPattern.YELLOW.color);
    }

    // ------------------------------------------------------------------ track rows (generic host)

    @Nullable
    private TrackEditor editorFor(Track track) {
        return track.type() == null ? null : track.type().editor();
    }

    private TrackUIState stateFor(Track track, TrackEditor editor) {
        return states.computeIfAbsent(track, t -> editor.createState());
    }

    /** Rebuild the whole track list UI from the model. Per-track UI state (expand/selection) is kept. */
    public void rebuild() {
        headersContainer.clearAllChildren();
        lanesContainer.clearAllChildren();
        clipViews.clear();
        var runtime = fxEditor.runtime;
        if (runtime == null) return;
        for (var track : runtime.fxData.timeline().tracks()) {
            var editor = editorFor(track);
            if (editor == null) {
                Photon.LOGGER.warn("No track editor registered for '{}'", track.name());
                continue;
            }
            var state = stateFor(track, editor);
            UIElement leftWrapper = null;
            UIElement rightWrapper = null;
            if (editor.isExpandable()) {
                leftWrapper = new UIElement().setId("timeline.expandLeft").layout(layout ->
                        layout.widthPercent(100).height(state.expandedHeight).flexDirection(FlexDirection.COLUMN));
                var left = editor.buildExpandedLeft(this, track, state);
                if (left != null) leftWrapper.addChild(left.layout(l -> l.flex(1)));

                rightWrapper = new UIElement().setId("timeline.expandRight").layout(layout ->
                        layout.widthPercent(100).height(state.expandedHeight).flexDirection(FlexDirection.COLUMN));
                var right = editor.buildExpandedRight(this, track, state);
                if (right != null) rightWrapper.addChild(right.layout(l -> l.flex(1)));

                // a resize grip at the bottom of both panels (drag either to set the lane height)
                leftWrapper.addChild(createResizeHandle(state, leftWrapper, rightWrapper));
                rightWrapper.addChild(createResizeHandle(state, leftWrapper, rightWrapper));
                leftWrapper.setDisplay(state.expanded);
                rightWrapper.setDisplay(state.expanded);
            }
            headersContainer.addChild(createTrackHeader(track, editor, state, leftWrapper, rightWrapper));
            lanesContainer.addChild(editor.buildLane(this, track, state));
            if (leftWrapper != null) {
                headersContainer.addChild(leftWrapper);
                lanesContainer.addChild(rightWrapper);
            }
        }
        updateHScroller();
    }

    private UIElement createResizeHandle(TrackUIState state, UIElement leftWrapper, UIElement rightWrapper) {
        var handle = new UIElement().setId("timeline.expandResize").layout(layout -> layout.widthPercent(100).height(4))
                .style(style -> style.backgroundTexture((graphics, mx, my, x, y, w, h, pt) ->
                        DrawerHelper.drawSolidRect(graphics, x + w / 2 - 6, y + h / 2f, 12, 1, ColorPattern.GRAY.color)));
        handle.addEventListener(UIEvents.MOUSE_DOWN, e -> { handle.startDrag(null, null); e.stopPropagation(); });
        handle.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            var newH = Math.max(MIN_EXPANDED, Math.round(e.y - rightWrapper.getPositionY()));
            state.expandedHeight = newH;
            leftWrapper.layout(l -> l.height(newH));
            rightWrapper.layout(l -> l.height(newH));
        });
        return handle;
    }

    private UIElement createTrackHeader(Track track, TrackEditor editor, TrackUIState state,
                                       @Nullable UIElement leftWrapper, @Nullable UIElement rightWrapper) {
        var header = new UIElement().setId("timeline.trackHeader").layout(layout -> {
            layout.widthPercent(100);
            layout.height(TimelineContext.ROW_HEIGHT);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
            layout.paddingAll(2);
        }).style(style -> style.backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    DrawerHelper.drawSolidRect(graphics, x, y, w, h,
                            isTrackSelected(track) ? ColorPattern.GRAY.color : ColorPattern.T_GRAY.color);
                    if (track.mute()) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_RED.color);
                    } else if (track.lock()) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_YELLOW.color);
                    }
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    if (reorderTarget == track) {
                        DrawerHelper.drawSolidRect(graphics, x, reorderBelow ? y + h - 1 : y, w, 1, ColorPattern.WHITE.color);
                    }
                }));
        header.addEventListener(UIEvents.DRAG_PERFORM, e -> onReorderDrop(e, track));
        header.addEventListener(UIEvents.DRAG_ENTER, e -> updateReorderTarget(header, track, e));
        header.addEventListener(UIEvents.DRAG_UPDATE, e -> updateReorderTarget(header, track, e));
        header.addEventListener(UIEvents.DRAG_LEAVE, e -> { if (reorderTarget == track) reorderTarget = null; });
        header.addEventListener(UIEvents.MOUSE_DOWN, e -> { if (e.button == 0) selectTrack(track); });

        var chip = new UIElement().setId("timeline.trackHeader.chip").layout(layout -> {
            layout.width(4);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture(editor.chipColor().rectTexture()));
        chip.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            chip.startDrag(new TrackDrag(track), new TextTexture(trackTitle(track)));
            e.stopPropagation();
        });

        UIElement expand = null;
        if (editor.isExpandable() && leftWrapper != null) {
            final var lw = leftWrapper;
            final var rw = rightWrapper;
            var toggle = new Toggle().noText().setOn(state.expanded)
                    .setOnToggleChanged(on -> { // no rebuild: just flip the panels' display
                        state.expanded = on;
                        lw.setDisplay(on);
                        rw.setDisplay(on);
                    });
            toggle.getToggleStyle().baseTexture(IGuiTexture.EMPTY).hoverTexture(IGuiTexture.EMPTY)
                    .markTexture(Icons.DOWN_ARROW_NO_BAR_S_LIGHT).unmarkTexture(Icons.RIGHT_ARROW_NO_BAR_S_LIGHT);
            toggle.setId("timeline.trackHeader.expand").layout(layout -> layout.width(10).height(10).alignSelf(AlignItems.CENTER))
                    .style(style -> style.tooltips("photon.gui.editor.timeline.expand"));
            expand = toggle;
        }

        var content = editor.buildHeaderContent(this, track, state);

        var muteToggle = new Toggle().noText().setOn(track.mute())
                .setOnToggleChanged(on -> {
                    track.mute(on);
                    refreshPreview();
                    pushApplied("photon.gui.editor.timeline.mute",
                            () -> { track.mute(on); rebuild(); refreshPreview(); },
                            () -> { track.mute(!on); rebuild(); refreshPreview(); });
                });
        muteToggle.getToggleStyle().markTexture(Icons.EYE_OFF.copy().scale(0.8f)).unmarkTexture(Icons.EYE.copy().setColor(0xff222222).scale(0.8f));
        muteToggle.setId("timeline.trackHeader.mute").layout(layout -> layout.aspectRatio(1).heightPercent(100))
                .style(style -> style.tooltips("photon.gui.editor.timeline.mute"));
        var lockToggle = new Toggle().noText().setOn(track.lock())
                .setOnToggleChanged(on -> {
                    track.lock(on);
                    pushApplied("photon.gui.editor.timeline.lock",
                            () -> { track.lock(on); rebuild(); },
                            () -> { track.lock(!on); rebuild(); });
                });
        lockToggle.getToggleStyle().markTexture(PhotonIcons.LOCK.copy().scale(0.8f)).unmarkTexture(PhotonIcons.UNLOCK.copy().setColor(0xff222222).scale(0.8f));
        lockToggle.setId("timeline.trackHeader.lock").layout(layout -> layout.aspectRatio(1).heightPercent(100))
                .style(style -> style.tooltips("photon.gui.editor.timeline.lock"));

        var menu = new Button().setText("...").setOnClick(e -> openTrackMenu(track, editor, e.x, e.y));
        menu.setId("timeline.trackHeader.menu").layout(layout -> layout.aspectRatio(1).heightPercent(100));
        var controls = editor.buildHeaderControls(this, track, state);
        header.addChild(chip);
        if (expand != null) header.addChild(expand);
        header.addChild(content);
        if (controls != null) header.addChild(controls);
        return header.addChildren(muteToggle, lockToggle, menu);
    }

    private String trackTitle(Track track) {
        if (!track.displayName().isEmpty()) return track.displayName();
        var runtime = fxEditor.runtime;
        var bound = (runtime == null || track.targetId() == null) ? null : runtime.objects.get(track.targetId());
        return bound == null ? "None" : bound.getName();
    }

    // ------------------------------------------------------------------ menus / reorder

    private void openAddTrackMenu(float x, float y) {
        var runtime = fxEditor.runtime;
        if (runtime == null) return;
        var menu = TreeBuilder.Menu.start();
        for (var type : PhotonRegistries.TIMELINE_TRACKS) {
            menu.leaf(Component.translatable("photon.timeline_track." + type.name()),
                    () -> addTrack(type.create(), runtime.fxData.timeline().tracks().size()));
        }
        if (clipboardTrack != null) {
            menu.leaf("ldlib.gui.editor.menu.paste", () -> addTrack(clipboardTrack.copy(), runtime.fxData.timeline().tracks().size()));
        }
        fxEditor.openMenu(x, y, menu);
    }

    private void openTrackMenu(Track track, TrackEditor editor, float x, float y) {
        var runtime = fxEditor.runtime;
        if (runtime == null) return;
        var tracks = runtime.fxData.timeline().tracks();
        var menu = TreeBuilder.Menu.start();
        editor.buildTrackMenu(menu, this, track);
        menu.leaf("ldlib.gui.editor.menu.copy", () -> clipboardTrack = track.copy());
        menu.leaf("photon.gui.editor.timeline.duplicate", () -> addTrack(track.copy(), tracks.indexOf(track) + 1));
        if (clipboardTrack != null) {
            menu.leaf("ldlib.gui.editor.menu.paste", () -> addTrack(clipboardTrack.copy(), tracks.size()));
        }
        menu.leaf("ldlib.gui.editor.menu.remove", () -> removeTrack(track));
        fxEditor.openMenu(x, y, menu);
    }

    private void updateReorderTarget(UIElement header, Track track, UIEvent event) {
        if (event.dragHandler.getDraggingObject() instanceof TrackDrag) {
            reorderTarget = track;
            reorderBelow = event.y > header.getPositionY() + header.getSizeHeight() / 2f;
        }
    }

    private void onReorderDrop(UIEvent event, Track target) {
        var below = reorderBelow;
        reorderTarget = null;
        if (event.dragHandler.getDraggingObject() instanceof TrackDrag(var dragged) && dragged != target) {
            reorderTrack(dragged, target, below);
            event.stopPropagation();
        }
    }

    // ------------------------------------------------------------------ snapping (cross-track)

    @Override
    public double snapTick(double tick, @Nullable Clip exclude, boolean ctrl) {
        if (ctrl) return tick;
        var runtime = fxEditor.runtime;
        if (runtime == null) return tick;
        double threshold = SNAP_PX / scale;
        double best = tick;
        double bestDist = threshold;
        for (var track : runtime.fxData.timeline().tracks()) {
            for (var other : track.clips()) {
                if (other == exclude) continue;
                for (var cand : new double[]{other.start(), other.end()}) {
                    var d = Math.abs(cand - tick);
                    if (d < bestDist) { bestDist = d; best = cand; }
                }
            }
        }
        for (var cand : new double[]{0, currentTimeTicks()}) {
            var d = Math.abs(cand - tick);
            if (d < bestDist) { bestDist = d; best = cand; }
        }
        return best;
    }

    @Override
    public double snapKeyTick(double tick, boolean ctrl) {
        if (ctrl) return tick;
        var runtime = fxEditor.runtime;
        if (runtime == null) return tick;
        double threshold = SNAP_PX / scale;
        double best = tick;
        double bestDist = threshold;
        for (var track : runtime.fxData.timeline().tracks()) {
            for (var clip : track.clips()) {
                for (var cand : new double[]{clip.start(), clip.end()}) {
                    var d = Math.abs(cand - tick);
                    if (d < bestDist) { bestDist = d; best = cand; }
                }
            }
            if (track instanceof AnimationTrack animation) {
                for (var property : animation.properties()) {
                    for (var t : property.keyframeTimes()) {
                        var d = Math.abs(t - tick);
                        if (d < bestDist) { bestDist = d; best = t; }
                    }
                }
            }
        }
        for (var cand : new double[]{0, currentTimeTicks()}) {
            var d = Math.abs(cand - tick);
            if (d < bestDist) { bestDist = d; best = cand; }
        }
        return best;
    }

    @Override
    public double snapMoveStart(double start, double duration, @Nullable Clip exclude, boolean ctrl) {
        if (ctrl) return start;
        var snapStart = snapTick(start, exclude, false);
        var snapEndStart = snapTick(start + duration, exclude, false) - duration;
        var leftSnapped = snapStart != start;
        var rightSnapped = snapEndStart != start;
        if (leftSnapped && (!rightSnapped || Math.abs(snapStart - start) <= Math.abs(snapEndStart - start))) {
            return snapStart;
        }
        return rightSnapped ? snapEndStart : start;
    }

    // ------------------------------------------------------------------ undoable mutations

    private void addTrack(Track track, int index) {
        if (fxEditor.runtime == null) return;
        var tracks = fxEditor.runtime.fxData.timeline().tracks();
        pushEdit("photon.gui.editor.timeline.add_track",
                () -> { tracks.add(Math.min(index, tracks.size()), track); rebuild(); refreshPreview(); },
                () -> { tracks.remove(track); rebuild(); refreshPreview(); });
    }

    private void removeTrack(Track track) {
        if (fxEditor.runtime == null) return;
        var tracks = fxEditor.runtime.fxData.timeline().tracks();
        var index = tracks.indexOf(track);
        var editor = editorFor(track);
        pushEdit("photon.gui.editor.timeline.remove_track",
                () -> {
                    tracks.remove(track);
                    if (selectedTrack == track) selectedTrack = null;
                    if (selectedClipTrack == track) { selectedClip = null; selectedClipTrack = null; }
                    if (recordingTrack == track) {
                        recordingTrack = null;
                        if (fxEditor.runtime != null) fxEditor.runtime.timelinePlayer.setRecording(false);
                    }
                    if (editor != null) editor.onRemoved(this, track, stateFor(track, editor));
                    states.remove(track);
                    rebuild();
                    refreshPreview();
                },
                () -> { tracks.add(Math.min(index, tracks.size()), track); rebuild(); refreshPreview(); });
    }

    private void reorderTrack(Track dragged, Track target, boolean below) {
        if (fxEditor.runtime == null) return;
        var tracks = fxEditor.runtime.fxData.timeline().tracks();
        var oldIndex = tracks.indexOf(dragged);
        pushEdit("photon.gui.editor.timeline.reorder_track",
                () -> {
                    tracks.remove(dragged);
                    var i = tracks.indexOf(target);
                    if (i < 0) i = tracks.size();
                    else if (below) i += 1;
                    tracks.add(Math.min(i, tracks.size()), dragged);
                    rebuild();
                    refreshPreview();
                },
                () -> {
                    tracks.remove(dragged);
                    tracks.add(Math.min(oldIndex, tracks.size()), dragged);
                    rebuild();
                    refreshPreview();
                });
    }

    @Override
    public double defaultClipDuration(@Nullable UUID objectId) {
        if (objectId != null && fxEditor.runtime != null
                && fxEditor.runtime.objects.get(objectId) instanceof FXObject object) {
            var life = subtreeLifetime(object);
            if (life > 0) return life;
        }
        return 40;
    }

    private int subtreeLifetime(FXObject object) {
        var max = object instanceof Emitter emitter ? Math.max(0, emitter.getStartDelay() + emitter.getLifetime()) : 0;
        for (var child : object.transform().children()) {
            if (child.sceneObject() instanceof FXObject childObject) {
                max = Math.max(max, subtreeLifetime(childObject));
            }
        }
        return max;
    }

    @Override
    public void addClip(Track track, Clip clip) {
        pushEdit("photon.gui.editor.timeline.add_clip",
                () -> { track.clips().add(clip); rebuild(); refreshPreview(); },
                () -> { track.clips().remove(clip); rebuild(); refreshPreview(); });
    }

    @Override
    public void removeClip(Track track, Clip clip) {
        var index = track.clips().indexOf(clip);
        pushEdit("photon.gui.editor.timeline.remove_clip",
                () -> {
                    track.clips().remove(clip);
                    if (selectedClip == clip) { selectedClip = null; selectedClipTrack = null; }
                    rebuild();
                    refreshPreview();
                },
                () -> { track.clips().add(Math.min(index, track.clips().size()), clip); rebuild(); refreshPreview(); });
    }

    @Override
    public void bind(Track track, @Nullable UUID targetId) {
        var old = track.targetId();
        var editor = editorFor(track);
        pushEdit("photon.gui.editor.timeline.bind",
                () -> { if (editor != null) editor.onTargetWillChange(this, track, old); track.targetId(targetId); rebuild(); refreshPreview(); },
                () -> { if (editor != null) editor.onTargetWillChange(this, track, targetId); track.targetId(old); rebuild(); refreshPreview(); });
    }

    // ------------------------------------------------------------------ transport / preview / keys

    private boolean isPlaying() { return fxEditor.sceneView.particleManager.isPlaying(); }

    private void togglePlay() {
        var pm = fxEditor.sceneView.particleManager;
        if (pm.isPlaying()) pm.pause();
        else pm.play();
    }

    private void stop() {
        var pm = fxEditor.sceneView.particleManager;
        pm.pause();
        fxEditor.sceneView.reset();
        if (fxEditor.runtime != null) {
            fxEditor.runtime.emmit(fxEditor.sceneView.effect);
        }
    }

    private void scrubTo(UIEvent event) {
        var time = Math.max(0, Math.round(xToTick(event.x)));
        fxEditor.sceneView.simulateTo(time);
    }

    private void onKeyDown(UIEvent event) {
        if (event.keyCode == GLFW.GLFW_KEY_SPACE) {
            togglePlay();
            event.stopPropagation();
        } else if (event.keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteSelection();
            event.stopPropagation();
        }
    }

    private void onCommand(UIEvent event) {
        if (CommandEvents.UNDO.equals(event.command)) {
            fxEditor.historyView.undo();
            event.stopPropagation();
        } else if (CommandEvents.REDO.equals(event.command)) {
            fxEditor.historyView.redo();
            event.stopPropagation();
        } else if (CommandEvents.COPY.equals(event.command)) {
            copySelection();
            event.stopPropagation();
        } else if (CommandEvents.PASTE.equals(event.command)) {
            pasteClipboard();
            event.stopPropagation();
        }
    }

    private void copySelection() {
        if (selectedClip != null) {
            clipboardClip = selectedClip.copy();
        } else if (selectedTrack != null) {
            clipboardTrack = selectedTrack.copy();
        }
    }

    private void pasteClipboard() {
        if (fxEditor.runtime == null) return;
        var targetTrack = selectedClipTrack != null ? selectedClipTrack : selectedTrack;
        if (clipboardClip != null && targetTrack != null) {
            addClip(targetTrack, clipboardClip.copy().start(Math.max(0, currentTimeTicks())));
        } else if (clipboardTrack != null) {
            addTrack(clipboardTrack.copy(), fxEditor.runtime.fxData.timeline().tracks().size());
        }
    }

    // ------------------------------------------------------------------ selection / inspector

    private void clearFxObjectSelection() {
        fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
        fxEditor.hierarchyView.treeList.setSelected(java.util.Collections.emptySet(), false);
        fxEditor.sceneView.fxObjectInfoView.clear();
    }

    @Override
    public void selectClip(Track track, Clip clip) {
        var editor = editorFor(track);
        if (!(editor instanceof ClipTrackEditor cte)) return;
        clearFxObjectSelection();
        fxEditor.inspectorView.inspect(cte.clipConfigurator(this, track, clip), null, () -> {
            if (selectedClip == clip) {
                selectedClip = null;
                selectedClipTrack = null;
                applyClipSelectionClasses();
            }
        });
        selectedClip = clip;
        selectedClipTrack = track;
        selectedTrack = track; // active track for Delete-routing/paste (highlight suppressed: selectedClip != null)
        applyClipSelectionClasses();
    }

    @Override
    public void setActiveTrack(Track track) {
        selectedTrack = track;
        selectedClip = null;
        selectedClipTrack = null;
        applyClipSelectionClasses();
    }

    @Override
    public void inspectProperty(Track track, IConfigurable configurable) {
        clearFxObjectSelection();
        fxEditor.inspectorView.inspect(configurable, null, () -> {});
    }

    @Override
    public boolean isRecording(Track track) {
        return recordingTrack == track;
    }

    @Override
    public void setRecordingTrack(@Nullable Track track) {
        if (recordingTrack == track) return;
        // exit the previous recording session (push its single undo)
        if (recordingTrack != null) {
            var prev = editorFor(recordingTrack);
            if (prev != null) prev.endRecording(this, recordingTrack, stateFor(recordingTrack, prev));
        }
        recordingTrack = track;
        var runtime = fxEditor.runtime;
        if (runtime != null) runtime.timelinePlayer.setRecording(track != null);
        if (track != null) {
            fxEditor.sceneView.particleManager.pause();
            var editor = editorFor(track);
            if (editor != null) editor.beginRecording(this, track, stateFor(track, editor));
        }
        refreshPreview();
        rebuild(); // sync record toggles across tracks
    }

    private void pollRecording() {
        if (recordingTrack == null) return;
        var runtime = fxEditor.runtime;
        if (runtime != null) {
            // freeze the per-frame re-apply only while paused (so manual edits persist); during playback
            // leave it on so animation still interpolates smoothly between ticks.
            runtime.timelinePlayer.setRecording(!fxEditor.sceneView.particleManager.isPlaying());
        }
        var editor = editorFor(recordingTrack);
        if (editor != null) editor.pollRecording(this, recordingTrack, stateFor(recordingTrack, editor));
    }

    private void applyClipSelectionClasses() {
        for (var view : clipViews) {
            if (view.clip() == selectedClip) {
                view.element().addClass("__selected__");
            } else {
                view.element().removeClass("__selected__");
            }
        }
    }

    @Override
    public void selectTrack(Track track) {
        var editor = editorFor(track);
        if (editor == null) return;
        if (selectedTrack == track && selectedClip == null && !subSelectionActive(track)) return; // already selected
        editor.clearSubSelection(stateFor(track, editor));
        clearFxObjectSelection();
        fxEditor.inspectorView.inspect(editor.trackConfigurator(this, track), null, () -> {
            if (selectedTrack == track) selectedTrack = null;
        });
        selectedTrack = track;
        selectedClip = null;
        selectedClipTrack = null;
        applyClipSelectionClasses();
    }

    private void deleteSelection() {
        // a selected clip first (its track may differ from the active track)
        if (selectedClip != null && selectedClipTrack != null) {
            var clipEditor = editorFor(selectedClipTrack);
            if (clipEditor != null && clipEditor.deleteSelection(this, selectedClipTrack, stateFor(selectedClipTrack, clipEditor))) return;
        }
        var track = selectedTrack;
        if (track == null) return;
        var editor = editorFor(track);
        if (editor != null && editor.deleteSelection(this, track, stateFor(track, editor))) return;
        // no sub-selection consumed → remove the whole track
        fxEditor.inspectorView.clear();
        removeTrack(track);
    }

    public void clear() {
        headersContainer.clearAllChildren();
        lanesContainer.clearAllChildren();
        clipViews.clear();
        states.clear();
        selectedClip = null;
        selectedClipTrack = null;
        selectedTrack = null;
        recordingTrack = null;
    }
}
