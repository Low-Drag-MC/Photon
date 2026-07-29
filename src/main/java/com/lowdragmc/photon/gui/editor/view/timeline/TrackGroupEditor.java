package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for {@code group} tracks: a renamable header with its own expand toggle that shows/hides the
 * child rows (the host's {@code rebuild} recurses into expanded groups). The lane shows a
 * <b>non-interactive</b> preview of the children's clips / keyframes / signals. Height is auto (no resize
 * grip). Tracks are added by dragging a header into the group (host) or via the "add child track" menu.
 */
public class TrackGroupEditor extends TrackEditor {

    public static class TrackGroupUIState extends TrackUIState {
        boolean editingName;
        String editingNameBefore = "";
    }

    @Override
    public TrackGroupUIState createState() {
        return new TrackGroupUIState();
    }

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.PINK;
    }

    // ------------------------------------------------------------------ header

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (TrackGroupUIState) state;
        var row = new UIElement().layout(layout -> {
            layout.flex(1);
            layout.heightPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
        });
        // own expand toggle (groups don't use the host's expandable-panel mechanism)
        var toggle = new Toggle().noText().setOn(st.expanded)
                .setOnToggleChanged(on -> { st.expanded = on; ctx.requestRebuild(); });
        toggle.getToggleStyle().baseTexture(IGuiTexture.EMPTY).hoverTexture(IGuiTexture.EMPTY)
                .markTexture(Icons.DOWN_ARROW_NO_BAR_S_LIGHT).unmarkTexture(Icons.RIGHT_ARROW_NO_BAR_S_LIGHT);
        toggle.setId("timeline.trackHeader.expand").layout(layout -> layout.width(10).height(10).alignSelf(AlignItems.CENTER))
                .style(style -> style.tooltips("photon.gui.editor.timeline.expand"));
        row.addChild(toggle);

        if (st.editingName) {
            var field = new TextField().setText(track.displayName(), false).setTextResponder(track::displayName);
            field.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
            field.addEventListener(UIEvents.FOCUS_OUT, e -> commitRename(ctx, track, st));
            var focused = new boolean[]{false};
            field.addEventListener(UIEvents.TICK, e -> { if (!focused[0]) { focused[0] = true; field.focus(); } });
            row.addChild(field);
        } else {
            var label = new Label().setText(track.displayName().isEmpty() ? "Group" : track.displayName());
            TimelineContext.styleLabel(label);
            label.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
            label.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
                st.editingNameBefore = track.displayName();
                st.editingName = true;
                ctx.requestRebuild();
            });
            row.addChild(label);
        }
        return row;
    }

    private void commitRename(TimelineContext ctx, Track track, TrackGroupUIState st) {
        var before = st.editingNameBefore;
        var after = track.displayName();
        st.editingName = false;
        ctx.requestRebuild();
        if (!before.equals(after)) {
            ctx.pushApplied("photon.gui.editor.timeline.rename",
                    () -> { track.displayName(after); ctx.requestRebuild(); },
                    () -> { track.displayName(before); ctx.requestRebuild(); });
        }
    }

    // ------------------------------------------------------------------ lane (preview)

    @Override
    public UIElement buildLane(TimelineContext ctx, Track track, TrackUIState state) {
        var group = (TrackGroup) track;
        var lane = new UIElement().setId("timeline.trackLane").layout(layout -> {
            layout.widthPercent(100);
            layout.height(state.rowHeight);
        }).setOverflowVisible(false).style(style -> style
                .backgroundTexture(GuiTexture.of((graphics, x, y, w, h) -> {
                    DrawerHelperClient.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_DARK_GRAY.color);
                    if (ctx.isTrackSelected(track)) {
                        DrawerHelperClient.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_WHITE.color);
                    }
                    if (track.mute()) {
                        DrawerHelperClient.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_RED.color);
                    } else if (track.lock()) {
                        DrawerHelperClient.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_YELLOW.color);
                    }
                }))
                .overlayTexture(GuiTexture.of((graphics, x, y, w, h) -> {
                    drawPreview(ctx, graphics, group, x, y, w, h);
                    ctx.drawPlayhead(graphics, x, y, w, h, graphics.partialTick);
                })));
        lane.addEventListener(UIEvents.MOUSE_WHEEL, ctx::zoom);
        lane.addEventListener(UIEvents.MOUSE_DOWN, e -> { if (e.button == 0) ctx.selectTrack(track); });
        return lane;
    }

    /** Draw a compressed, non-interactive preview of every descendant leaf's clips/keyframes/signals. */
    private void drawPreview(TimelineContext ctx, GUIContext graphics, TrackGroup group, float x, float y, float width, float height) {
        var leaves = new ArrayList<Track>();
        collectLeaves(group, leaves);
        var cy = y + height / 2f;
        for (var leaf : leaves) {
            var color = leaf.type() != null ? leaf.type().editor().chipColor() : ColorPattern.GRAY;
            for (var clip : leaf.clips()) {
                var cx = ctx.originX() + (float) ((clip.start() - ctx.scrollTicks()) * ctx.scale());
                var cw = (float) Math.max(1, clip.duration() * ctx.scale());
                if (cx + cw < x || cx > x + width) continue;
                DrawerHelperClient.drawSolidRect(graphics, Math.max(x, cx), cy - 2, Math.min(cw, width), 4, color.color);
            }
            if (leaf instanceof AnimationTrack animation) {
                for (var property : animation.properties()) {
                    for (var time : property.keyframeTimes()) {
                        drawDot(graphics, ctx, time, x, width, cy, ColorPattern.ORANGE);
                    }
                }
            } else if (leaf instanceof SignalTrack signalTrack) {
                for (var signal : signalTrack.signals()) {
                    drawDot(graphics, ctx, signal.time(), x, width, cy, ColorPattern.PURPLE);
                }
            }
        }
    }

    private void drawDot(GUIContext graphics, TimelineContext ctx, double time, float x, float width, float cy, ColorPattern color) {
        var dx = ctx.originX() + (float) ((time - ctx.scrollTicks()) * ctx.scale());
        if (dx < x || dx > x + width) return;
        DrawerHelperClient.drawSolidRect(graphics, dx - 1.5f, cy - 1.5f, 3, 3, color.color);
    }

    private void collectLeaves(TrackGroup group, List<Track> out) {
        for (var child : group.children()) {
            if (child instanceof TrackGroup g) collectLeaves(g, out);
            else out.add(child);
        }
    }

    // ------------------------------------------------------------------ menu / config

    @Override
    public void buildTrackMenu(TreeBuilder.Menu menu, TimelineContext ctx, Track track) {
        if (!(track instanceof TrackGroup group)) return;
        menu.branch("photon.gui.editor.timeline.add_child_track", sub -> {
            for (var type : PhotonRegistries.TIMELINE_TRACKS) {
                sub.leaf(Component.translatable("photon.timeline_track." + type.name()),
                        () -> ctx.addChildTrack(group, type.create()));
            }
        });
    }

    @Override
    public IConfigurable trackConfigurator(TimelineContext ctx, Track track) {
        return IConfigurable.create(group -> {
            group.addConfigurator(new StringConfigurator("name", track::displayName,
                    v -> { track.displayName(v); ctx.requestRebuild(); }, track.displayName(), true));
            group.addConfigurator(new BooleanConfigurator("mute", track::mute,
                    v -> { track.mute(v); ctx.requestRebuild(); ctx.refreshPreview(); }, track.mute(), true));
            group.addConfigurator(new BooleanConfigurator("lock", track::lock,
                    v -> { track.lock(v); ctx.requestRebuild(); }, track.lock(), true));
        });
    }
}
