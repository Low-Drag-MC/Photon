package com.lowdragmc.photon.gui.editor.view.timeline;

/**
 * Per-track mutable UI state, held by the host (keyed by {@link com.lowdragmc.photon.client.fx.timeline.Track})
 * and created by {@link TrackEditor#createState()}. {@link TrackEditor}s are stateless singletons, so any
 * per-track/transient UI state lives here. Editors subclass this for their own state.
 */
public class TrackUIState {
    /** Whether the expandable editor panel is open. */
    public boolean expanded = false;
    /** Height (px) of the expanded panel (user-resizable). */
    public int expandedHeight = TimelineContext.EXPANDED_HEIGHT;
    /** Fixed height (px) of this track's header + lane row (Ctrl+wheel adjustable, Unity-style). */
    public int rowHeight = TimelineContext.ROW_HEIGHT;
}
