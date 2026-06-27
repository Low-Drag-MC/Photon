package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.CommandEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unity/Premiere-style timeline editor panel. A {@link SplitView} separates a left header panel
 * (add-track button over a list of track headers) from a right timeline panel (ruler over clip
 * lanes). The two track lists live in separate {@link ScrollerView}s whose vertical scroll is kept
 * in sync (the left scrollbars are hidden). Horizontal navigation is a zoomable scale +
 * {@code scrollTicks} offset driven by a proportional scrollbar and mouse-wheel zoom.
 */
public class FXTimelineView extends View {
    public static final int ROW_HEIGHT = 20;
    public static final int RULER_HEIGHT = 14;
    public static final int HSCROLL_HEIGHT = 8;
    public static final float DEFAULT_SCALE = 2.0f;
    public static final float MIN_SCALE = 0.05f;
    public static final float MAX_SCALE = 50.0f;
    /** Trailing padding (ticks) added past the last clip so you can scroll a little beyond it. */
    public static final int CONTENT_PAD = 10;
    /** Pixels from a clip edge that count as a resize grab zone. */
    public static final float EDGE_PX = 4;
    /** Snap threshold in screen pixels (scale-independent, so zooming gives finer control). */
    public static final float SNAP_PX = 6;

    private static final int DRAG_MOVE = 0;
    private static final int DRAG_LEFT = 1;
    private static final int DRAG_RIGHT = 2;

    /** Drag payload used to reorder tracks by their header. */
    private record TrackDrag(Track track) {}
    /** A clip's view element, kept so horizontal scroll/zoom can reposition without a full rebuild. */
    private record ClipView(Clip clip, UIElement element, UIElement lane) {}

    public final FXEditor fxEditor;
    private final Button playButton = new Button();
    private final ScrollerView leftScroller = new ScrollerView();
    private final ScrollerView rightScroller = new ScrollerView();
    private final UIElement headersContainer = new UIElement();
    private final UIElement lanesContainer = new UIElement();
    private final UIElement ruler = new UIElement();
    private final Scroller hScroll = new Scroller.Horizontal();
    private final List<ClipView> clipViews = new ArrayList<>();

    /** Horizontal scale (pixels per tick) and left-most visible tick. */
    private float scale = DEFAULT_SCALE;
    private float scrollTicks = 0;
    private boolean syncingScroll = false;

    // drag/scrub state
    private boolean wasPlaying = false;
    private long previewTime = 0;
    private double grabOffsetTicks = 0;
    /** The clip being dragged (for the cross-track snap guide), and the drag mode + history snapshot. */
    @Nullable
    private Clip draggingClip;
    private int clipDragMode = DRAG_MOVE;
    private double dragFixedEnd = 0;
    private double dragBeforeStart = 0;
    private double dragBeforeDuration = 0;
    /** True while the dragged clip currently overlaps another (drawn red, reverted on release). */
    private boolean dragInvalid = false;
    /** Track header currently targeted by a reorder drag, and whether to insert after it. */
    @Nullable
    private Track reorderTarget;
    private boolean reorderBelow = false;

    // selection (mutually exclusive with the hierarchy fx-object selection via the inspector onClose)
    @Nullable
    private Clip selectedClip;
    @Nullable
    private Track selectedClipTrack;
    @Nullable
    private Track selectedTrack;
    /** Control track currently being renamed (its header shows a TextField instead of a Label). */
    @Nullable
    private Track editingNameTrack;
    /** Name before the current rename edit (for the undo entry). */
    private String editingNameBefore = "";
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

        // NB: do NOT focus() on MOUSE_DOWN — the framework already focuses the nearest focusable
        // ancestor on click (ModularUI), and re-grabbing focus here would steal it from a context
        // menu opened during the same click and immediately close it (Menu.onBlur auto-closes).
        addEventListener(UIEvents.KEY_DOWN, this::onKeyDown);
        // command events (undo/redo/copy/paste) target the focused element WITHOUT bubbling, so the
        // editor-level HistoryView never sees them while the timeline is focused — handle them here.
        addEventListener(UIEvents.EXECUTE_COMMAND, this::onCommand);
        // keep the horizontal scrollbar in step with the viewport size each frame
        addEventListener(UIEvents.TICK, e -> updateHScroller());
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
        var stopButton = new Button().setText("photon.gui.editor.timeline.stop")
                .setOnClick(e -> stop());
        stopButton.setId("timeline.stop").layout(layout -> layout.width(46));
        var timeLabel = new Label().setText("0.0s").layout(layout -> layout.flex(1))
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
        // header: + add-track button
        var header = new UIElement().setId("timeline.addTrackBar").layout(layout -> {
            layout.widthPercent(100);
            layout.height(RULER_HEIGHT);
            layout.flexDirection(FlexDirection.ROW);
        }).style(style -> style.backgroundTexture(ColorPattern.T_GRAY.rectTexture()));
        var addBtn = new Button().setText("+").setOnClick(e -> openAddTrackMenu(e.x, e.y));
        addBtn.setId("timeline.addTrack").layout(layout -> layout.width(14).height(RULER_HEIGHT))
                .style(style -> style.tooltips("photon.gui.editor.timeline.add_track"));
        header.addChild(addBtn);
        // headers scroller (vertical only, scrollbars hidden)
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
        leftScroller.addScrollViewChild(headersContainer);
        leftScroller.verticalScroller.setOnValueChanged(v -> syncScroll(leftScroller, rightScroller));
        // bottom spacer so rows align with the right column (which has the h-scrollbar there)
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
                .addEventListener(UIEvents.MOUSE_DOWN, e -> {
                    e.currentElement.startDrag(null, null);
                    scrubTo(e);
                })
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
        // cross-track snap guides: vertical lines at the dragged clip's edges spanning all lanes
        lanesContainer.style(style -> style.overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
            if (draggingClip == null) return;
            drawGuideLine(graphics, draggingClip.start(), x, y, w, h);
            drawGuideLine(graphics, draggingClip.end(), x, y, w, h);
        }));
        rightScroller.addScrollViewChild(lanesContainer);
        rightScroller.verticalScroller.setOnValueChanged(v -> syncScroll(rightScroller, leftScroller));

        hScroll.setId("timeline.hscroll");
        hScroll.headButton.setDisplay(false);
        hScroll.tailButton.setDisplay(false);
        hScroll.setRange(0, 0.0001f).setValue(0f, false)
                .setOnValueChanged(v -> {
                    // a full-width bar over an empty range can yield NaN/Inf; keep scrollTicks sane
                    scrollTicks = Float.isFinite(v) ? Math.max(0, v) : 0;
                    repositionClips();
                }).layout(layout -> layout.widthPercent(100).height(HSCROLL_HEIGHT));
        return column.addChildren(ruler, rightScroller, hScroll);
    }

    /**
     * The shared horizontal origin (screen x of tick {@code scrollTicks}) = the lanes' viewport content
     * origin. The ruler draws against this so it lines up with the (possibly padded) lane content.
     */
    private float originX() {
        var x = rightScroller.viewPort.getContentX();
        return x != 0 ? x : ruler.getContentX();
    }

    /** Record an undoable edit that is executed now. */
    private void pushEdit(String name, Runnable doFn, Runnable undoFn) {
        fxEditor.historyView.pushHistory(Component.translatable(name), EditAction.of(doFn, undoFn));
    }

    /** Record an undoable edit whose effect is already applied (e.g. a finished drag). */
    private void pushApplied(String name, Runnable redo, Runnable undo) {
        fxEditor.historyView.pushHistory(Component.translatable(name), EditAction.of(redo, undo), false);
    }

    private void syncScroll(ScrollerView from, ScrollerView to) {
        if (syncingScroll) return;
        syncingScroll = true;
        to.verticalScroller.setValue(from.verticalScroller.getValue(), true);
        syncingScroll = false;
    }

    // ------------------------------------------------------------------ horizontal scale / scroll

    /** Right-panel viewport width in px (used to compute how many ticks are visible). */
    private float viewWidth() {
        var w = rightScroller.viewPort.getContentWidth();
        return w > 1 ? w : ruler.getContentWidth();
    }

    private double contentMaxTick() {
        var runtime = fxEditor.runtime;
        if (runtime == null) return 0;
        double max = 0;
        for (var track : runtime.fxData.timeline().tracks()) {
            for (var clip : track.clips()) {
                max = Math.max(max, clip.end());
            }
        }
        return max;
    }

    /** Recompute the proportional horizontal scrollbar from scale, content extent and viewport. */
    private void updateHScroller() {
        var viewW = viewWidth();
        if (viewW <= 1) return;
        var visibleTicks = viewW / scale;
        var contentMax = contentMaxTick();
        double total;
        if (contentMax <= visibleTicks) {
            total = visibleTicks; // content fits: thumb full, empty space past content stays visible
            scrollTicks = 0;
        } else {
            total = contentMax + CONTENT_PAD;
        }
        if (total < 1) total = 1;
        var maxScroll = (float) Math.max(0, total - visibleTicks);
        scrollTicks = Math.max(0, Math.min(scrollTicks, maxScroll));
        // never let min==max (NaN normalized value) and keep the thumb < 100% so drag math stays finite
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

    /** Local x within a lane for a tick (relative to the lane content origin == {@link #originX()}). */
    private float tickToLocalX(double tick) {
        return (float) ((tick - scrollTicks) * scale);
    }

    private double xToTick(float mouseX) {
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
        var origin = originX(); // align ruler ticks to the (possibly padded) lane content
        var visibleTicks = width / scale;
        var major = niceInterval(60 / scale);
        var minor = major / 5;
        var endTick = scrollTicks + visibleTicks;
        // minor ticks (clamped to the ruler bounds so they don't bleed into the left panel)
        if (minor * scale >= 4) {
            for (double t = Math.floor(scrollTicks / minor) * minor; t <= endTick; t += minor) {
                if (t < 0) continue;
                var mx = origin + (float) ((t - scrollTicks) * scale);
                if (mx < x || mx > x + width) continue;
                DrawerHelper.drawSolidRect(graphics, mx, y + height * 0.6f, 1, height * 0.4f, ColorPattern.T_GRAY.color);
            }
        }
        // major ticks + labels
        for (double t = Math.floor(scrollTicks / major) * major; t <= endTick; t += major) {
            if (t < 0) continue;
            var mx = origin + (float) ((t - scrollTicks) * scale);
            if (mx < x || mx > x + width) continue;
            DrawerHelper.drawSolidRect(graphics, mx, y, 1, height, ColorPattern.GRAY.color);
            DrawerHelper.drawText(graphics, String.valueOf(Math.round(t)), mx + 2, y + 3, 0.5f, ColorPattern.GRAY.color);
        }
        drawPlayhead(graphics, x, y, width, height, partialTick);
    }

    /** Round to a "nice" 1/2/5×10ⁿ interval (min 1 tick) for ~60px major spacing. */
    private double niceInterval(double raw) {
        if (raw < 1) return 1;
        var pow = Math.pow(10, Math.floor(Math.log10(raw)));
        var n = raw / pow;
        var nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return nice * pow;
    }

    private void drawPlayhead(GuiGraphics graphics, float contentX, float y, float width, float height, float partialTick) {
        var playheadX = originX() + (currentTimeTicks(partialTick) - scrollTicks) * scale;
        if (playheadX < contentX || playheadX > contentX + width) return;
        DrawerHelper.drawSolidRect(graphics, playheadX, y, 1, height, ColorPattern.RED.color);
    }

    // ------------------------------------------------------------------ track rows

    /** Rebuild the whole track list UI from the model. Call after structural edits. */
    public void rebuild() {
        headersContainer.clearAllChildren();
        lanesContainer.clearAllChildren();
        clipViews.clear();
        var runtime = fxEditor.runtime;
        if (runtime == null) return;
        for (var track : runtime.fxData.timeline().tracks()) {
            headersContainer.addChild(createTrackHeader(runtime, track));
            lanesContainer.addChild(createTrackLane(runtime, track));
        }
        updateHScroller();
    }

    private UIElement createTrackHeader(FXRuntime runtime, Track track) {
        var isControl = track instanceof ControlTrack;
        var header = new UIElement().setId("timeline.trackHeader").layout(layout -> {
            layout.widthPercent(100);
            layout.height(ROW_HEIGHT);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
            layout.paddingAll(2);
        }).style(style -> style.backgroundTexture((graphics, mx, my, x, y, w, h, pt) ->
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h,
                                selectedTrack == track ? ColorPattern.GRAY.color : ColorPattern.T_GRAY.color))
                // reorder insertion line (above/below) while dragging a track header
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    if (reorderTarget == track) {
                        DrawerHelper.drawSolidRect(graphics, x, reorderBelow ? y + h - 1 : y, w, 1, ColorPattern.WHITE.color);
                    }
                }));
        header.addEventListener(UIEvents.DRAG_PERFORM, e -> onReorderDrop(e, track));
        header.addEventListener(UIEvents.DRAG_ENTER, e -> updateReorderTarget(header, track, e));
        header.addEventListener(UIEvents.DRAG_UPDATE, e -> updateReorderTarget(header, track, e));
        header.addEventListener(UIEvents.DRAG_LEAVE, e -> { if (reorderTarget == track) reorderTarget = null; });
        header.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) selectTrack(track);
        });

        var chip = new UIElement().setId("timeline.trackHeader.chip").layout(layout -> {
            layout.width(4);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture(
                (isControl ? ColorPattern.CYAN : ColorPattern.GREEN).rectTexture()));
        chip.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            chip.startDrag(new TrackDrag(track), new TextTexture(trackTitle(runtime, track)));
            e.stopPropagation();
        });

        var content = createTrackHeaderContent(runtime, track, isControl);

        // apply immediately (the toggle already reflects the new state); record history with
        // execute=false so undo/redo rebuild later without destroying the toggle during its own click
        var muteToggle = new Toggle().setText("M").setOn(track.mute())
                .setOnToggleChanged(on -> {
                    track.mute(on);
                    refreshPreview();
                    pushApplied("photon.gui.editor.timeline.mute",
                            () -> { track.mute(on); rebuild(); refreshPreview(); },
                            () -> { track.mute(!on); rebuild(); refreshPreview(); });
                });
        muteToggle.setId("timeline.trackHeader.mute").layout(layout -> layout.width(12).heightPercent(100))
                .style(style -> style.tooltips("photon.gui.editor.timeline.mute"));
        var lockToggle = new Toggle().setText("L").setOn(track.lock())
                .setOnToggleChanged(on -> {
                    track.lock(on);
                    pushApplied("photon.gui.editor.timeline.lock",
                            () -> { track.lock(on); rebuild(); },
                            () -> { track.lock(!on); rebuild(); });
                });
        lockToggle.setId("timeline.trackHeader.lock").layout(layout -> layout.width(12).heightPercent(100))
                .style(style -> style.tooltips("photon.gui.editor.timeline.lock"));

        var menu = new Button().setText("...").setOnClick(e -> openTrackMenu(track, e.x, e.y));
        menu.setId("timeline.trackHeader.menu").layout(layout -> layout.width(12).heightPercent(100));
        return header.addChildren(chip, content, muteToggle, lockToggle, menu);
    }

    private UIElement createTrackHeaderContent(FXRuntime runtime, Track track, boolean isControl) {
        if (isControl) {
            if (editingNameTrack == track) {
                var field = new TextField().setText(track.displayName(), false).setTextResponder(track::displayName);
                field.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
                field.addEventListener(UIEvents.FOCUS_OUT, e -> commitRename(track));
                var focused = new boolean[]{false};
                field.addEventListener(UIEvents.TICK, e -> {
                    if (!focused[0]) { focused[0] = true; field.focus(); }
                });
                return field;
            }
            var label = new Label().setText(track.displayName().isEmpty() ? "Control" : track.displayName());
            styleLabel(label);
            label.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
            label.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
                editingNameBefore = track.displayName();
                editingNameTrack = track;
                rebuild();
            });
            return label;
        }
        var bound = track.targetId() == null ? null : runtime.objects.get(track.targetId());
        var slot = new UIElement().setId("timeline.trackHeader.target").layout(layout -> layout.flex(1).heightPercent(100))
                .style(style -> style.backgroundTexture(ColorPattern.BLACK.rectTexture()));
        var label = new Label().setText(bound == null ? "None" : bound.getName());
        styleLabel(label);
        label.layout(layout -> layout.flex(1).heightPercent(100));
        slot.addChild(label);
        slot.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) openObjectPicker(runtime, track, e.x, e.y);
        });
        slot.addEventListener(UIEvents.DRAG_PERFORM, e -> onBindDrop(e, track));
        return slot;
    }

    /** Vertically center a label, clip overflow, and roll long text on hover. */
    private static void styleLabel(TextElement label) {
        label.textStyle(style -> style.textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HOVER_ROLL));
        label.setOverflowVisible(false);
    }

    private UIElement createTrackLane(FXRuntime runtime, Track track) {
        var isControl = track instanceof ControlTrack;
        var lane = new UIElement().setId("timeline.trackLane").layout(layout -> {
            layout.widthPercent(100);
            layout.height(ROW_HEIGHT);
        }).setOverflowVisible(false).style(style -> style
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.BLACK.color);
                    if (selectedTrack == track) {
                        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_WHITE.color);
                    }
                })
                // playhead in the overlay so it draws on top of the clips
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> drawPlayhead(graphics, x, y, w, h, pt)));
        lane.addEventListener(UIEvents.MOUSE_WHEEL, this::onZoom);
        lane.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) {
                selectTrack(track); // empty area (clips stopPropagation and select themselves)
            } else if (e.button == 1 && !isControl && !track.lock()) {
                openAddClipMenu(track, e.x, e.y); // right-click an activator lane to add a clip
            }
        });
        if (isControl) {
            lane.addEventListener(UIEvents.DRAG_PERFORM, e -> onControlClipDrop(e, track));
        } else {
            lane.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
                if (track.lock()) return;
                addClip(track, new Clip(Math.max(0, Math.round(xToTick(e.x))),
                        defaultClipDuration(track.targetId()), 1.0f));
            });
        }
        for (var clip : track.clips()) {
            lane.addChild(createClipElement(runtime, track, clip, lane));
        }
        return lane;
    }

    private UIElement createClipElement(FXRuntime runtime, Track track, Clip clip, UIElement lane) {
        var isControl = track instanceof ControlTrack;
        var baseColor = isControl ? ColorPattern.CYAN : ColorPattern.GREEN;
        var fillColor = isControl ? ColorPattern.T_CYAN : ColorPattern.T_GREEN;
        var clipHeight = ROW_HEIGHT - 6;
        var element = new UIElement().setId("timeline.clip").layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(tickToLocalX(clip.start()));
            layout.top((ROW_HEIGHT - clipHeight) / 2f); // vertically centered in the lane
            layout.width((float) Math.max(2, clip.duration() * scale));
            layout.height(clipHeight);
            layout.paddingAll(2);
        }).style(style -> style
                // body + border drawn 1px inside the element box so abutting clips don't overlap;
                // a clip overlapping another while dragged is shown red (and reverted on release)
                .backgroundTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var invalid = draggingClip == clip && dragInvalid;
                    DrawerHelper.drawSolidRect(graphics, x + 1, y, Math.max(1, w - 2), h,
                            (invalid ? ColorPattern.T_RED : fillColor).color);
                })
                .overlayTexture((graphics, mx, my, x, y, w, h, pt) -> {
                    var bx = x + 1;
                    var bw = Math.max(1, w - 2);
                    var invalid = draggingClip == clip && dragInvalid;
                    DrawerHelper.drawBorder(graphics, bx, y, bw, h, (invalid ? ColorPattern.RED : baseColor).color, 1);
                    if (selectedClip == clip && !invalid) {
                        DrawerHelper.drawBorder(graphics, bx, y, bw, h, ColorPattern.WHITE.color, 1);
                    }
                    // draw a resize arrow at the cursor when hovering an edge
                    if (!track.lock() && my >= y && my <= y + h && (mx <= bx + EDGE_PX || mx >= bx + bw - EDGE_PX)
                            && mx >= bx && mx <= bx + bw) {
                        Icons.ARROW_LEFT_RIGHT.draw(graphics, mx, my, mx - 5, my - 5, 10, 10, pt);
                    }
                }));
        if (selectedClip == clip) {
            element.addClass("__selected__");
        }
        clipViews.add(new ClipView(clip, element, lane));

        if (isControl && clip.targetId() != null) {
            var bound = runtime.objects.get(clip.targetId());
            var label = new Label().setText(bound == null ? "?" : bound.getName());
            styleLabel(label);
            label.layout(layout -> layout.flex(1).heightPercent(100));
            element.addChild(label);
        }

        element.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 1) {
                selectClip(track, clip);
                if (!track.lock()) openClipMenu(track, clip, e.x, e.y);
                e.stopPropagation();
                return;
            }
            selectClip(track, clip);
            if (track.lock()) { // locked: select but don't edit
                e.stopPropagation();
                return;
            }
            var localX = e.x - element.getPositionX();
            var w = element.getSizeWidth();
            clipDragMode = localX <= EDGE_PX ? DRAG_LEFT : (localX >= w - EDGE_PX ? DRAG_RIGHT : DRAG_MOVE);
            grabOffsetTicks = xToTick(e.x) - clip.start();
            dragFixedEnd = clip.end();
            dragBeforeStart = clip.start();
            dragBeforeDuration = clip.duration();
            draggingClip = clip;
            dragInvalid = false;
            beginScrub();
            element.startDrag(null, null);
            e.stopPropagation();
        });
        element.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            var ctrl = e.isCtrlDown();
            var cursorTick = xToTick(e.x);
            if (clipDragMode == DRAG_RIGHT) {
                var end = snapTick(cursorTick, clip, ctrl);
                clip.duration(Math.max(1, Math.round(end - clip.start())));
            } else if (clipDragMode == DRAG_LEFT) {
                var start = snapTick(cursorTick - grabOffsetTicks, clip, ctrl);
                start = Math.max(0, Math.min(start, dragFixedEnd - 1));
                clip.start(Math.round(start));
                clip.duration(Math.max(1, Math.round(dragFixedEnd - clip.start())));
            } else {
                var start = snapMoveStart(cursorTick - grabOffsetTicks, clip.duration(), clip, ctrl);
                clip.start(Math.max(0, Math.round(start)));
            }
            dragInvalid = overlaps(track, clip);
            applyClipLayout(element, clip);
            refreshPreview();
        });
        element.addEventListener(UIEvents.DRAG_END, e -> {
            draggingClip = null;
            endScrub();
            var beforeStart = dragBeforeStart;
            var beforeDuration = dragBeforeDuration;
            if (dragInvalid) {
                // overlapping is invalid: snap back to where the drag started
                dragInvalid = false;
                clip.start(beforeStart).duration(beforeDuration);
                applyClipLayout(element, clip);
                refreshPreview();
            } else {
                var afterStart = clip.start();
                var afterDuration = clip.duration();
                if (beforeStart != afterStart || beforeDuration != afterDuration) {
                    pushApplied("photon.gui.editor.timeline.edit_clip",
                            () -> { clip.start(afterStart).duration(afterDuration); repositionClips(); refreshPreview(); },
                            () -> { clip.start(beforeStart).duration(beforeDuration); repositionClips(); refreshPreview(); });
                }
            }
            // the inspector configurators use forceUpdate=true, so they re-sync from the model on their own
        });

        return element;
    }

    private void applyClipLayout(UIElement element, Clip clip) {
        element.layout(layout -> {
            layout.left(tickToLocalX(clip.start()));
            layout.width((float) Math.max(2, clip.duration() * scale));
        });
    }

    private void drawGuideLine(GuiGraphics graphics, double tick, float x, float y, float width, float height) {
        var lx = originX() + (float) ((tick - scrollTicks) * scale);
        if (lx < x || lx > x + width) return;
        DrawerHelper.drawSolidRect(graphics, lx, y, 1, height, ColorPattern.YELLOW.color);
    }

    /** Nearest clip/0/playhead boundary within {@link #SNAP_PX} screen px of {@code tick} (Ctrl disables). */
    private double snapTick(double tick, @Nullable Clip exclude, boolean ctrl) {
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

    /** Snap a moving clip by whichever of its two edges is closest to a boundary. */
    private double snapMoveStart(double start, double duration, Clip exclude, boolean ctrl) {
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

    // ------------------------------------------------------------------ menus / binding / reorder

    private void openAddTrackMenu(float x, float y) {
        var runtime = fxEditor.runtime;
        if (runtime == null) return;
        var menu = TreeBuilder.Menu.start();
        for (var holder : PhotonRegistries.TIMELINE_TRACKS) {
            var typeName = holder.annotation().name();
            menu.leaf(Component.translatable("photon.timeline_track." + typeName),
                    () -> addTrack(holder.value().get(), runtime.fxData.timeline().tracks().size()));
        }
        fxEditor.openMenu(x, y, menu);
    }

    private void openObjectPicker(FXRuntime runtime, Track track, float x, float y) {
        var menu = TreeBuilder.Menu.start();
        menu.leaf("None", () -> bind(track, null));
        for (var object : runtime.objects.values()) {
            // root may be bound by activator/control tracks (only the future animation track excludes it)
            var id = object.transform().id();
            menu.leaf(object.getName(), () -> bind(track, id));
        }
        fxEditor.openMenu(x, y, menu);
    }

    private void openTrackMenu(Track track, float x, float y) {
        var runtime = fxEditor.runtime;
        if (runtime == null) return;
        var tracks = runtime.fxData.timeline().tracks();
        var menu = TreeBuilder.Menu.start();
        menu.leaf("ldlib.gui.editor.menu.copy", () -> clipboardTrack = track.copy());
        menu.leaf("photon.gui.editor.timeline.duplicate", () -> addTrack(track.copy(), tracks.indexOf(track) + 1));
        if (clipboardTrack != null) {
            menu.leaf("ldlib.gui.editor.menu.paste", () -> addTrack(clipboardTrack.copy(), tracks.size()));
        }
        menu.leaf("ldlib.gui.editor.menu.remove", () -> removeTrack(track));
        fxEditor.openMenu(x, y, menu);
    }

    private void openAddClipMenu(Track track, float x, float y) {
        var startTick = Math.max(0, Math.round(xToTick(x)));
        var menu = TreeBuilder.Menu.start();
        menu.leaf("photon.gui.editor.timeline.add_clip",
                () -> addClip(track, new Clip(startTick, defaultClipDuration(track.targetId()), 1.0f)));
        fxEditor.openMenu(x, y, menu);
    }

    private void openClipMenu(Track track, Clip clip, float x, float y) {
        var menu = TreeBuilder.Menu.start();
        if (track instanceof ControlTrack) {
            menu.leaf(Component.translatable(clip.randomSeed()
                    ? "photon.gui.editor.timeline.seed_random_on" : "photon.gui.editor.timeline.seed_random_off"), () -> {
                var old = clip.randomSeed();
                pushEdit("photon.gui.editor.timeline.edit_clip",
                        () -> { clip.randomSeed(!old); rebuild(); refreshPreview(); },
                        () -> { clip.randomSeed(old); rebuild(); refreshPreview(); });
            });
            menu.leaf(Component.translatable("photon.gui.editor.timeline.reseed"), () -> {
                var oldSeed = clip.seed();
                var oldRandom = clip.randomSeed();
                var newSeed = new java.util.Random().nextLong();
                pushEdit("photon.gui.editor.timeline.edit_clip",
                        () -> { clip.seed(newSeed).randomSeed(false); refreshPreview(); },
                        () -> { clip.seed(oldSeed).randomSeed(oldRandom); refreshPreview(); });
            });
        }
        menu.leaf("ldlib.gui.editor.menu.copy", () -> clipboardClip = clip.copy());
        menu.leaf("ldlib.gui.editor.menu.remove", () -> removeClip(track, clip));
        fxEditor.openMenu(x, y, menu);
    }

    private void onBindDrop(UIEvent event, Track track) {
        if (fxEditor.runtime == null) return;
        if (event.dragHandler.getDraggingObject() instanceof FXHierarchyView.DraggingNode(var node)) {
            bind(track, node.getKey().transform().id());
            event.stopPropagation();
        }
    }

    private void onControlClipDrop(UIEvent event, Track track) {
        if (fxEditor.runtime == null || track.lock()) return;
        if (event.dragHandler.getDraggingObject() instanceof FXHierarchyView.DraggingNode(var node)) {
            var id = node.getKey().transform().id();
            addClip(track, new Clip(Math.max(0, Math.round(xToTick(event.x))), defaultClipDuration(id), 1.0f).targetId(id));
            event.stopPropagation();
        }
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
        pushEdit("photon.gui.editor.timeline.remove_track",
                () -> {
                    tracks.remove(track);
                    if (selectedTrack == track) selectedTrack = null;
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

    /** Default new-clip duration from the bound object's subtree lifetime (falls back to 40 ticks). */
    private double defaultClipDuration(@Nullable UUID objectId) {
        if (objectId != null && fxEditor.runtime != null
                && fxEditor.runtime.objects.get(objectId) instanceof FXObject object) {
            var life = subtreeLifetime(object);
            if (life > 0) return life;
        }
        return 40;
    }

    private int subtreeLifetime(FXObject object) {
        // include the emitter's start delay so the default clip covers delay + lifetime
        var max = object instanceof Emitter emitter
                ? Math.max(0, emitter.getStartDelay() + emitter.getLifetime()) : 0;
        for (var child : object.transform().children()) {
            if (child.sceneObject() instanceof FXObject childObject) {
                max = Math.max(max, subtreeLifetime(childObject));
            }
        }
        return max;
    }

    /** Whether {@code clip} overlaps another clip on {@code track} (same target for control tracks). */
    private boolean overlaps(Track track, Clip clip) {
        for (var other : track.clips()) {
            if (other == clip) continue;
            var sameTarget = !(track instanceof ControlTrack) || java.util.Objects.equals(other.targetId(), clip.targetId());
            if (sameTarget && clip.start() < other.end() && other.start() < clip.end()) {
                return true;
            }
        }
        return false;
    }

    private void addClip(Track track, Clip clip) {
        pushEdit("photon.gui.editor.timeline.add_clip",
                () -> { track.clips().add(clip); rebuild(); refreshPreview(); },
                () -> { track.clips().remove(clip); rebuild(); refreshPreview(); });
    }

    private void removeClip(Track track, Clip clip) {
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

    private void bind(Track track, @Nullable UUID targetId) {
        var old = track.targetId();
        pushEdit("photon.gui.editor.timeline.bind",
                () -> { track.targetId(targetId); rebuild(); refreshPreview(); },
                () -> { track.targetId(old); rebuild(); refreshPreview(); });
    }

    private void setDisplayName(Track track, String name) {
        track.displayName(name);
        rebuild(); // refresh the header label (the inspector field auto-syncs via forceUpdate)
    }

    // ------------------------------------------------------------------ transport / preview / keys

    private String trackTitle(FXRuntime runtime, Track track) {
        if (track instanceof ControlTrack) {
            return track.displayName().isEmpty() ? "Control" : track.displayName();
        }
        var bound = track.targetId() == null ? null : runtime.objects.get(track.targetId());
        return bound == null ? "None" : bound.getName();
    }

    private boolean isPlaying() {
        return fxEditor.sceneView.particleManager.isPlaying();
    }

    private void togglePlay() {
        var pm = fxEditor.sceneView.particleManager;
        if (pm.isPlaying()) {
            pm.pause();
        } else {
            pm.play();
        }
    }

    /** Stop = pause and re-arm at t=0 (clears particles, re-emits, resets the timeline clock). */
    private void stop() {
        var pm = fxEditor.sceneView.particleManager;
        pm.pause();
        fxEditor.sceneView.reset();
        if (fxEditor.runtime != null) {
            fxEditor.runtime.emmit(fxEditor.sceneView.effect);
        }
    }

    private long currentTimeTicks() {
        return fxEditor.sceneView.particleManager.getRealTime();
    }

    private float currentTimeTicks(float partialTick) {
        return fxEditor.sceneView.particleManager.getRealTime(partialTick);
    }

    /** Re-simulate up to the current playhead with the edited timeline, keeping the playhead. */
    private void refreshPreview() {
        fxEditor.sceneView.simulateTo(fxEditor.sceneView.particleManager.getRealTime());
    }

    /** Pause and remember the play state while dragging, so we can live-preview without resetting. */
    private void beginScrub() {
        var pm = fxEditor.sceneView.particleManager;
        wasPlaying = pm.isPlaying();
        previewTime = pm.getRealTime();
        pm.pause();
    }

    private void endScrub() {
        fxEditor.sceneView.simulateTo(previewTime);
        if (wasPlaying) {
            fxEditor.sceneView.particleManager.play();
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

    /** Editor commands routed to the focused timeline (they don't bubble to the editor's HistoryView). */
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

    private void commitRename(Track track) {
        var before = editingNameBefore;
        var after = track.displayName();
        editingNameTrack = null;
        rebuild(); // swap the header TextField back to a Label (inspector name auto-syncs via forceUpdate)
        if (!before.equals(after)) {
            pushApplied("photon.gui.editor.timeline.rename",
                    () -> setDisplayName(track, after),
                    () -> setDisplayName(track, before));
        }
    }

    // ------------------------------------------------------------------ selection / inspector

    /** Deselect any fx object (gizmo + hierarchy tree + info). The reverse is automatic: selecting
     *  an fx object re-inspects, which fires our clip/track inspector onClose. */
    private void clearFxObjectSelection() {
        fxEditor.sceneView.sceneEditor.setTransformGizmoTarget(null);
        fxEditor.hierarchyView.treeList.setSelected(java.util.Collections.emptySet(), false);
        fxEditor.sceneView.fxObjectInfoView.clear();
    }

    private void selectClip(Track track, Clip clip) {
        clearFxObjectSelection();
        fxEditor.inspectorView.inspect(clipConfigurable(track, clip), null, () -> {
            if (selectedClip == clip) {
                selectedClip = null;
                selectedClipTrack = null;
                applyClipSelectionClasses();
            }
        });
        selectedClip = clip;
        selectedClipTrack = track;
        selectedTrack = null;
        applyClipSelectionClasses();
    }

    /** Toggle the built-in {@code __selected__} class on clip elements (stylesheet hook). */
    private void applyClipSelectionClasses() {
        for (var view : clipViews) {
            if (view.clip() == selectedClip) {
                view.element().addClass("__selected__");
            } else {
                view.element().removeClass("__selected__");
            }
        }
    }

    private void selectTrack(Track track) {
        clearFxObjectSelection();
        fxEditor.inspectorView.inspect(trackConfigurable(track), null, () -> {
            if (selectedTrack == track) {
                selectedTrack = null;
            }
        });
        selectedTrack = track;
        selectedClip = null;
        selectedClipTrack = null;
    }

    private IConfigurable clipConfigurable(Track track, Clip clip) {
        // forceUpdate=true so the widgets poll the model each tick and stay in sync after a drag
        return IConfigurable.create(group -> {
            group.addConfigurator(new NumberConfigurator("start", clip::start,
                    v -> { clip.start(Math.max(0, v.doubleValue())); repositionClips(); refreshPreview(); },
                    clip.start(), true).setRange(0, 1_000_000));
            group.addConfigurator(new NumberConfigurator("duration", clip::duration,
                    v -> { clip.duration(Math.max(1, v.doubleValue())); repositionClips(); refreshPreview(); },
                    clip.duration(), true).setRange(1, 1_000_000));
            if (track instanceof ControlTrack) {
                group.addConfigurator(new BooleanConfigurator("randomSeed", clip::randomSeed,
                        v -> { clip.randomSeed(v); refreshPreview(); }, clip.randomSeed(), true));
                group.addConfigurator(new NumberConfigurator("seed", () -> (double) clip.seed(),
                        v -> { clip.seed(v.longValue()); refreshPreview(); }, (double) clip.seed(), true));
            }
        });
    }

    private IConfigurable trackConfigurable(Track track) {
        return IConfigurable.create(group -> {
            if (track instanceof ControlTrack) {
                group.addConfigurator(new StringConfigurator("name", track::displayName,
                        v -> { track.displayName(v); rebuild(); }, track.displayName(), true));
            }
            group.addConfigurator(new BooleanConfigurator("mute", track::mute,
                    v -> { track.mute(v); rebuild(); refreshPreview(); }, track.mute(), true));
            group.addConfigurator(new BooleanConfigurator("lock", track::lock,
                    v -> { track.lock(v); rebuild(); }, track.lock(), true));
        });
    }

    private void deleteSelection() {
        if (selectedClip != null && selectedClipTrack != null) {
            if (selectedClipTrack.lock()) return;
            // capture first: inspectorView.clear() fires the clip onClose which nulls the selection
            var track = selectedClipTrack;
            var clip = selectedClip;
            fxEditor.inspectorView.clear();
            removeClip(track, clip);
        } else if (selectedTrack != null) {
            var track = selectedTrack;
            fxEditor.inspectorView.clear();
            removeTrack(track);
        }
    }

    public void clear() {
        headersContainer.clearAllChildren();
        lanesContainer.clearAllChildren();
        clipViews.clear();
        selectedClip = null;
        selectedClipTrack = null;
        selectedTrack = null;
    }
}
