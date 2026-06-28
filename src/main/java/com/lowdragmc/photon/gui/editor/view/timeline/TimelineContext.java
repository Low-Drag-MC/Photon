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
    int EXPANDED_HEIGHT = 80;
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

    boolean isTrackSelected(Track track);

    void selectClip(Track track, Clip clip);

    @Nullable
    Clip selectedClip();

    @Nullable
    Track selectedClipTrack();

    boolean isClipSelected(Clip clip);

    // ---- clip helpers (shared by clip-based tracks) ----
    /** Register a clip's element so horizontal scroll/zoom repositions it without a rebuild. */
    void registerClipView(Clip clip, com.lowdragmc.lowdraglib2.gui.ui.UIElement element);

    double snapTick(double tick, @Nullable Clip exclude, boolean ctrl);

    double snapMoveStart(double start, double duration, @Nullable Clip exclude, boolean ctrl);

    /** Set/clear the cross-track snap guide lines drawn over all lanes while dragging a clip. */
    void setDragGuide(@Nullable Clip clip);

    // ---- shared undoable mutations ----
    void addClip(Track track, Clip clip);

    void removeClip(Track track, Clip clip);

    void bind(Track track, @Nullable UUID targetId);

    double defaultClipDuration(@Nullable UUID objectId);

    /** Vertically center a label, clip overflow, and roll long text on hover. */
    static void styleLabel(TextElement label) {
        label.textStyle(style -> style.textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HOVER_ROLL));
        label.setOverflowVisible(false);
    }
}
