package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * Client-side, type-specific UI + behavior for a kind of {@link Track}, registered under
 * {@code photon:track_editor} with the <b>same name</b> as its track type and resolved by
 * {@code FXTimelineView} via {@code track.name()}. Stateless singleton; per-track state is created via
 * {@link #createState()} and passed back into every call.
 * <p>
 * The host owns the generic chrome (chip / expand toggle / mute / lock / {@code ...} menu / reorder /
 * selection highlight); editors fill in the type-specific header content, lane, optional expanded
 * panel, extent, delete and configurator.
 * <p>
 * Editors are stateless singletons: each concrete editor declares a {@code @LDLRegisterClient}-annotated
 * {@code public static final} instance (name = its track type) registered into
 * {@code photon:track_editor}, so the host can reuse the one instance directly (no creator/cache).
 */
public abstract class TrackEditor {

    /** Create the per-track UI state object for a track of this type. */
    public TrackUIState createState() {
        return new TrackUIState();
    }

    /** Header chip / accent color. */
    public abstract ColorPattern chipColor();

    /** The middle of the header row (target slot, name field, …). */
    public abstract UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state);

    /** The lane content + interactions (clips, keyframe dots, …). */
    public abstract UIElement buildLane(TimelineContext ctx, Track track, TrackUIState state);

    /** Whether this track type has an expandable editor panel (shows the expand toggle). */
    public boolean isExpandable() {
        return false;
    }

    /** Inner content of the expanded left (header-side) panel, or null. */
    @Nullable
    public UIElement buildExpandedLeft(TimelineContext ctx, Track track, TrackUIState state) {
        return null;
    }

    /** Inner content of the expanded right (lane-side) panel, or null. */
    @Nullable
    public UIElement buildExpandedRight(TimelineContext ctx, Track track, TrackUIState state) {
        return null;
    }

    /** Largest tick this track reaches (clip ends, keyframe times…), for the scroll/ruler extent. */
    public double contentMaxTick(Track track) {
        return 0;
    }

    /** Delete this track's current sub-selection (clip / keyframe). Return false if there is none
     *  (the host then removes the whole track). */
    public boolean deleteSelection(TimelineContext ctx, Track track, TrackUIState state) {
        return false;
    }

    /** Copy this track's current sub-selection (clip / keyframe / stop) to a clipboard. Return false if
     *  there is nothing to copy (the host then falls back to copying the whole track). */
    public boolean copySubSelection(TimelineContext ctx, Track track, TrackUIState state) {
        return false;
    }

    /** Paste a previously-copied sub-selection onto this track (at the playhead). Return false if there is
     *  nothing to paste (the host then falls back to pasting a whole track). */
    public boolean pasteSubSelection(TimelineContext ctx, Track track, TrackUIState state) {
        return false;
    }

    /** Whether the user has an explicit sub-selection (e.g. a keyframe/property) that should suppress
     *  the whole-track highlight. Default false. */
    public boolean hasSubSelection(TrackUIState state) {
        return false;
    }

    /** Whether the editor's expanded box no longer matches its data (an element was added/removed by
     *  code, not a lane interaction) and must be rebuilt. Checked each tick by the host. Default false. */
    public boolean isBoxStale(TrackUIState state) {
        return false;
    }

    /** Clear any sub-selection (called when the track itself is selected). Returns true if the cleared
     *  selection had backing UI elements (so the host should rebuild). Default no-op → false. */
    public boolean clearSubSelection(TrackUIState state) {
        return false;
    }

    /** Poll for user edits while this track is in record mode (Unity-style). Default no-op. */
    public void pollRecording(TimelineContext ctx, Track track, TrackUIState state) {
    }

    /** Called when record mode is entered for this track (snapshot baselines). Default no-op. */
    public void beginRecording(TimelineContext ctx, Track track, TrackUIState state) {
    }

    /** Called when record mode exits for this track (push a single undo of the session). Default no-op. */
    public void endRecording(TimelineContext ctx, Track track, TrackUIState state) {
    }

    /** Optional trailing header controls (inserted before mute/lock), e.g. a record toggle. */
    @Nullable
    public UIElement buildHeaderControls(TimelineContext ctx, Track track, TrackUIState state) {
        return null;
    }

    /** The inspector configurator for the track (mute/lock plus type-specific fields). */
    public abstract IConfigurable trackConfigurator(TimelineContext ctx, Track track);

    /** Extra entries for the header {@code ...} menu. */
    public void buildTrackMenu(TreeBuilder.Menu menu, TimelineContext ctx, Track track) {
    }

    /** Cleanup when the track is removed (e.g. restore an animation target's authored pose). */
    public void onRemoved(TimelineContext ctx, Track track, TrackUIState state) {
    }

    /** Called before a track's target id changes (rebind), with the id being unbound. Lets e.g. an
     *  animation track restore the old target's authored pose. */
    public void onTargetWillChange(TimelineContext ctx, Track track, @Nullable java.util.UUID oldTargetId) {
    }

    /** A header "target object" slot (click to pick, drag an fx object to bind). Shared by activator
     *  and animation headers. {@code allowRoot} excludes root from the picker/drop when false. */
    protected UIElement buildTargetSlot(TimelineContext ctx, Track track, boolean allowRoot) {
        var runtime = ctx.runtime();
        var bound = (runtime == null || track.targetId() == null) ? null : runtime.objects.get(track.targetId());
        var slot = new UIElement().setId("timeline.trackHeader.target")
                .layout(layout -> layout.flex(1).heightPercent(100).paddingAll(2))
                .style(style -> style.backgroundTexture(ColorPattern.BLACK.rectTexture()));
        var label = new Label().bindDataSource(SupplierDataSource.of(() -> {
            var r = ctx.runtime();
            var b = (r == null || track.targetId() == null) ? null : r.objects.get(track.targetId());
            return Component.literal(b == null ? "None" : b.getName());
        }));
        TimelineContext.styleLabel(label);
        label.layout(layout -> layout.flex(1).heightPercent(100));
        slot.addChild(label);
        slot.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) openObjectPicker(ctx, track, allowRoot, e.x, e.y);
        });
        slot.addEventListener(UIEvents.DRAG_PERFORM, e -> onTargetDrop(ctx, track, allowRoot, e));
        return slot;
    }

    private void openObjectPicker(TimelineContext ctx, Track track, boolean allowRoot, float x, float y) {
        var runtime = ctx.runtime();
        if (runtime == null) return;
        var menu = TreeBuilder.Menu.start();
        menu.leaf("None", () -> ctx.bind(track, null));
        for (var object : runtime.objects.values()) {
            if (!allowRoot && object == runtime.root) continue;
            var id = object.transform().id();
            menu.leaf("[%s]".formatted(object.getName()), () -> ctx.bind(track, id));
        }
        ctx.openMenu(x, y, menu);
    }

    private void onTargetDrop(TimelineContext ctx, Track track, boolean allowRoot, UIEvent event) {
        var runtime = ctx.runtime();
        if (runtime == null) return;
        if (event.dragHandler.getDraggingObject() instanceof FXHierarchyView.DraggingNode(var node)) {
            if (!allowRoot && node.getKey() == runtime.root) return;
            ctx.bind(track, node.getKey().transform().id());
            event.stopPropagation();
        }
    }
}
