package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.gui.editor.FXEditor;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Host services a {@link TrackEditor} uses to build its UI and mutate the timeline, without knowing
 * about the concrete {@code FXTimelineView}. This is the seam that makes the track system extensible:
 * a new track type only needs a {@link Track} + a registered {@link TrackEditor} that talks to this
 * context.
 */
public interface TimelineContext {
    int ROW_HEIGHT = 20;
    int EXPANDED_HEIGHT = 50;
    /** Pixels from a clip edge that count as a resize grab zone. */
    float EDGE_PX = 4;
    /** Pixel radius for clicking a keyframe point / tangent handle in the curve editor. */
    float KEY_HIT_PX = 4;

    FXEditor editor();

    @Nullable
    FXRuntime runtime();

    // ---- horizontal scale / scroll mapping (shared with the ruler) ----
    float scale();

    float scrollTicks();

    /** Screen x of {@code scrollTicks} (the lanes' viewport content origin). */
    float originX();

    /** Local x within a lane for a tick (relative to the lane content origin). */
    float tickToLocalX(double tick);

    double xToTick(float mouseX);

    long currentTimeTicks();

    /** The ruler's current major-tick interval (ticks), so panels can align gridlines to the ruler. */
    double majorTickInterval();

    void drawPlayhead(GuiGraphics graphics, float x, float y, float width, float height, float partialTick);

    /** Mouse-wheel zoom anchored at the cursor (shared by ruler/lanes/curve panels). */
    void zoom(UIEvent event);

    // ---- history + live preview ----
    void pushEdit(String name, Runnable doFn, Runnable undoFn);

    void pushApplied(String name, Runnable redo, Runnable undo);

    void refreshPreview();

    void beginScrub();

    void endScrub();

    void openMenu(float x, float y, TreeBuilder.Menu menu);

    /** Rebuild the whole track list (use sparingly; prefer scoped updates). */
    void requestRebuild();

    // ---- selection ----
    void selectTrack(Track track);

    /** Make {@code track} the active track (for Delete-routing / paste) without highlighting it or
     *  clearing its sub-selection; used when selecting a keyframe/property inside it. */
    void setActiveTrack(Track track);

    /** Inspect a sub-selection's configurable (e.g. a property's interp mode) without selecting the
     *  whole track. */
    void inspectProperty(Track track, com.lowdragmc.lowdraglib2.configurator.IConfigurable configurable);

    boolean isTrackSelected(Track track);

    // ---- record mode ----
    /** Enter/exit record mode for {@code track} (null = stop). Pauses playback on enter. */
    void setRecordingTrack(@Nullable Track track);

    boolean isRecording(Track track);

    void selectClip(Track track, Clip clip);

    @Nullable
    Clip selectedClip();

    @Nullable
    Track selectedClipTrack();

    boolean isClipSelected(Clip clip);

    /** The full multi-selection set of clips (read-only view). */
    java.util.Set<Clip> selectedClips();

    /** Replace (or add to, when {@code additive}) the multi-selection with {@code clips}. */
    void selectClips(java.util.Collection<Clip> clips, boolean additive);

    /** Toggle one clip's membership in the multi-selection (Ctrl/Shift-click). */
    void toggleClipSelection(Track track, Clip clip);

    /** Whether the in-progress clip group drag is currently invalid (overlap / bad destination). */
    boolean isClipGroupDragInvalid();

    // ---- multi-clip group drag (driven from a clip element, owned by the host) ----
    void beginClipGroupDrag(Clip anchor, double grabOffsetTicks);

    void updateClipGroupDrag(float cursorX, float cursorY, boolean ctrl);

    void endClipGroupDrag(boolean commit);

    // ---- lane sub-item helpers ----
    /** A registered lane sub-element and how to re-lay-it-out when the tick/value mapping changes
     *  (zoom/scroll/range). Registered elements are cleared on {@code rebuild()}. */
    record LaneItem(com.lowdragmc.lowdraglib2.gui.ui.UIElement element, Runnable reposition) {}

    /** Register any lane sub-element (clip / keyframe / stop) with its reposition callback so scroll/zoom
     *  re-lays-it-out without a full rebuild. */
    void registerLaneItem(com.lowdragmc.lowdraglib2.gui.ui.UIElement element, Runnable reposition);

    /** Re-run every registered {@link LaneItem}'s reposition from the current model — call this after a drag
     *  mutates a clip/stop tick so its element follows live (the same path scroll/zoom uses). */
    void refreshLaneLayout();

    // ---- clip helpers (shared by clip-based tracks) ----
    /** Register a clip's element (and its track) so scroll/zoom + marquee + group-drag can find it. */
    void registerClipView(Track track, Clip clip, com.lowdragmc.lowdraglib2.gui.ui.UIElement element);

    double snapTick(double tick, @Nullable Clip exclude, boolean ctrl);

    double snapMoveStart(double start, double duration, @Nullable Clip exclude, boolean ctrl);

    /** Snap a keyframe tick to clip edges, the playhead, tick 0 and every other keyframe time. */
    double snapKeyTick(double tick, boolean ctrl);

    /** Set/clear the cross-track snap guide lines drawn over all lanes while dragging a clip. */
    void setDragGuide(@Nullable Clip clip);

    /** Show vertical yellow snap-guide lines across all lanes at {@code startTick}/{@code endTick} while a
     *  lane sub-item (expr / gradient / curve clip) is dragged; clear with {@link #clearDragGuideTicks()}. */
    void setDragGuideTicks(double startTick, double endTick);

    void clearDragGuideTicks();

    // ---- shared undoable mutations ----
    void addClip(Track track, Clip clip);

    void removeClip(Track track, Clip clip);

    /** Add {@code child} as a new track inside {@code group} (undoable). */
    void addChildTrack(com.lowdragmc.photon.client.fx.timeline.TrackGroup group, Track child);

    void bind(Track track, @Nullable UUID targetId);

    double defaultClipDuration(@Nullable UUID objectId);

    /** Vertically center a label, clip overflow, and roll long text on hover. */
    static void styleLabel(TextElement label) {
        label.textStyle(style -> style.textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HOVER_ROLL));
        label.setOverflowVisible(false);
    }
}
