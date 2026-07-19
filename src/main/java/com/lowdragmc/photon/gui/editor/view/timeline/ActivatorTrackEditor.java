package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;

/** Editor for {@code activator} tracks: a bound-target header + time-only clips. */
public class ActivatorTrackEditor extends ClipTrackEditor {

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.GREEN;
    }

    @Override
    public ColorPattern clipFillColor() {
        return ColorPattern.T_GREEN;
    }

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        return buildTargetSlot(ctx, track, true);
    }

    @Override
    protected void onLaneDoubleClick(TimelineContext ctx, Track track, float x, float y) {
        ctx.addClip(track, new Clip(Math.max(0, Math.round(ctx.xToTick(x))),
                ctx.defaultClipDuration(track.targetId()), 1.0f));
    }

    @Override
    protected void onLaneRightClick(TimelineContext ctx, Track track, float x, float y) {
        var startTick = Math.max(0, Math.round(ctx.xToTick(x)));
        var menu = TreeBuilder.Menu.start();
        menu.leaf("photon.gui.editor.timeline.add_clip",
                () -> ctx.addClip(track, new Clip(startTick, ctx.defaultClipDuration(track.targetId()), 1.0f)));
        ctx.openMenu(x, y, menu);
    }
}
