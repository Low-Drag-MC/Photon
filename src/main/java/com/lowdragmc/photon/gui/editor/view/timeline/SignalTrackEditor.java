package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.TagConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.Signal;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Editor for {@code signal} tracks: a renamable channel header (no bound target, like control) + a lane
 * of point-event markers. Signals are selected / marquee-selected / dragged (in time) / deleted like
 * curve keyframes — the machinery mirrors {@link AnimationTrackEditor}'s keyframe selection, but in 1-D
 * (time only) on the main lane.
 */
@OnlyIn(Dist.CLIENT)
public class SignalTrackEditor extends TrackEditor {

    public static class SignalTrackUIState extends TrackUIState {
        boolean editingName;
        String editingNameBefore = "";
        /** Indices into {@link SignalTrack#signals()} that are selected. */
        final Set<Integer> selectedSignals = new HashSet<>();
        boolean explicitSelection;
        // marquee
        boolean marquee, marqueeAdditive;
        float mX0, mY0, mX1, mY1;
        // group drag (time only)
        boolean dragging;
        int dragAnchor = -1;
        double grabOffsetTicks;
        final Map<Integer, Double> dragOrigins = new HashMap<>();
        @Nullable List<Signal> dragSnapshot;
    }

    @Override
    public SignalTrackUIState createState() {
        return new SignalTrackUIState();
    }

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.PURPLE;
    }

    private ColorPattern markerColor() {
        return ColorPattern.PURPLE;
    }

    // ------------------------------------------------------------------ header (renamable channel)

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (SignalTrackUIState) state;
        if (st.editingName) {
            var field = new TextField().setText(track.displayName(), false).setTextResponder(track::displayName);
            field.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
            field.addEventListener(UIEvents.FOCUS_OUT, e -> commitRename(ctx, track, st));
            var focused = new boolean[]{false};
            field.addEventListener(UIEvents.TICK, e -> {
                if (!focused[0]) { focused[0] = true; field.focus(); }
            });
            return field;
        }
        var label = new Label().setText(track.displayName().isEmpty() ? "Signal" : track.displayName());
        TimelineContext.styleLabel(label);
        label.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
        label.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
            st.editingNameBefore = track.displayName();
            st.editingName = true;
            ctx.requestRebuild();
        });
        return label;
    }

    private void commitRename(TimelineContext ctx, Track track, SignalTrackUIState st) {
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

    // ------------------------------------------------------------------ selection bookkeeping

    @Override
    public double contentMaxTick(Track track) {
        double max = 0;
        for (var signal : ((SignalTrack) track).signals()) {
            max = Math.max(max, signal.time());
        }
        return max;
    }

    @Override
    public boolean hasSubSelection(TrackUIState state) {
        return ((SignalTrackUIState) state).explicitSelection;
    }

    @Override
    public boolean clearSubSelection(TrackUIState state) {
        var st = (SignalTrackUIState) state;
        st.selectedSignals.clear();
        st.explicitSelection = false;
        return false; // signals are drawn each frame, not built elements → no rebuild needed
    }

    @Override
    public boolean deleteSelection(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (SignalTrackUIState) state;
        if (!st.selectedSignals.isEmpty()) {
            if (!track.lock()) removeSelectedSignals(ctx, (SignalTrack) track, st);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ lane

    @Override
    public UIElement buildLane(TimelineContext ctx, Track track, TrackUIState state) {
        var signalTrack = (SignalTrack) track;
        var st = (SignalTrackUIState) state;
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
                    drawSignals(ctx, graphics, signalTrack, st, x, y, w, h);
                    ctx.drawPlayhead(graphics, x, y, w, h, pt);
                    drawSignalTooltip(ctx, graphics, signalTrack, mx, my, x, y, w, h);
                    if (st.marquee) drawMarquee(graphics, st);
                }));
        lane.addEventListener(UIEvents.MOUSE_WHEEL, ctx::zoom);
        lane.addEventListener(UIEvents.MOUSE_DOWN, e -> onLaneMouseDown(ctx, signalTrack, st, e));
        lane.addEventListener(UIEvents.DOUBLE_CLICK, e -> onLaneDoubleClick(ctx, signalTrack, st, e));
        lane.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            if (st.marquee) { st.mX1 = e.x; st.mY1 = e.y; }
            else if (st.dragging) updateDrag(ctx, signalTrack, st, e);
        });
        lane.addEventListener(UIEvents.DRAG_END, e -> {
            if (st.marquee) finishMarquee(ctx, signalTrack, st, e.currentElement);
            else if (st.dragging) endDrag(ctx, signalTrack, st);
        });
        return lane;
    }

    private float signalX(TimelineContext ctx, double time) {
        return ctx.originX() + (float) ((time - ctx.scrollTicks()) * ctx.scale());
    }

    private void drawSignals(TimelineContext ctx, GuiGraphics graphics, SignalTrack track, SignalTrackUIState st,
                             float x, float y, float width, float height) {
        var ctrl = Screen.hasControlDown();
        var cy = y + height / 2f;
        var signals = track.signals();
        for (int i = 0; i < signals.size(); i++) {
            var signal = signals.get(i);
            var sx = signalX(ctx, signal.time());
            if (sx < x - 4 || sx > x + width + 4) continue;
            var selected = st.selectedSignals.contains(i);
            var color = (selected ? ColorPattern.WHITE : markerColor()).color;
            // a small diamond: a thin vertical tick + a square marker at the center
            DrawerHelper.drawSolidRect(graphics, sx - 0.5f, y + 2, 1, height - 4, (selected ? ColorPattern.WHITE : ColorPattern.T_WHITE).color);
            DrawerHelper.drawSolidRect(graphics, sx - 2.5f, cy - 2.5f, 5, 5, color);
            if (ctrl) {
                DrawerHelper.drawText(graphics, signal.name(), sx + 4, cy - 4, 1f, ColorPattern.WHITE.color);
            }
        }
    }

    private void drawSignalTooltip(TimelineContext ctx, GuiGraphics graphics, SignalTrack track,
                                   float mx, float my, float x, float y, float width, float height) {
        var hit = hitSignal(ctx, track, mx);
        if (hit < 0) return;
        var name = track.signals().get(hit).name();
        var tw = Minecraft.getInstance().font.width(name);
        var tx = mx + 6 + tw > x + width ? mx - 6 - tw : mx + 6;
        var ty = Math.max(y, my - 10);
        DrawerHelper.drawSolidRect(graphics, tx - 1, ty - 1, tw + 2, 10, ColorPattern.BLACK.color);
        DrawerHelper.drawText(graphics, name, tx, ty, 1f, ColorPattern.WHITE.color);
    }

    private void drawMarquee(GuiGraphics graphics, SignalTrackUIState st) {
        var x = Math.min(st.mX0, st.mX1);
        var y = Math.min(st.mY0, st.mY1);
        var w = Math.abs(st.mX1 - st.mX0);
        var h = Math.abs(st.mY1 - st.mY0);
        DrawerHelper.drawSolidRect(graphics, x, y, w, h, ColorPattern.T_WHITE.color);
        DrawerHelper.drawBorder(graphics, x, y, w, h, ColorPattern.WHITE.color, 1);
    }

    /** Index of the signal whose marker is within {@link TimelineContext#KEY_HIT_PX} of {@code mouseX}. */
    private int hitSignal(TimelineContext ctx, SignalTrack track, float mouseX) {
        int best = -1;
        float bestDist = TimelineContext.KEY_HIT_PX + 1;
        var signals = track.signals();
        for (int i = 0; i < signals.size(); i++) {
            var d = Math.abs(mouseX - signalX(ctx, signals.get(i).time()));
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ interaction

    private void onLaneMouseDown(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, UIEvent e) {
        if (e.button == 1) {
            if (track.lock()) return;
            var hit = hitSignal(ctx, track, e.x);
            var menu = TreeBuilder.Menu.start();
            if (hit >= 0) {
                st.selectedSignals.clear();
                st.selectedSignals.add(hit);
                st.explicitSelection = true;
                ctx.setActiveTrack(track);
                inspectSignal(ctx, track, hit);
                menu.leaf("ldlib.gui.editor.menu.remove", () -> removeSignalAt(ctx, track, st, hit));
            } else {
                var tick = Math.max(0, Math.round(ctx.xToTick(e.x)));
                menu.leaf("photon.gui.editor.timeline.add_signal", () -> addSignal(ctx, track, st, tick));
            }
            ctx.openMenu(e.x, e.y, menu);
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        ctx.setActiveTrack(track);
        st.explicitSelection = true;
        var hit = hitSignal(ctx, track, e.x);
        if (hit >= 0) {
            if (e.isShiftDown() || e.isCtrlDown()) { // toggle membership, no drag
                if (!st.selectedSignals.remove(hit)) st.selectedSignals.add(hit);
                e.stopPropagation();
                return;
            }
            if (!st.selectedSignals.contains(hit)) {
                st.selectedSignals.clear();
                st.selectedSignals.add(hit);
            }
            inspectSignal(ctx, track, hit);
            if (track.lock()) { e.stopPropagation(); return; }
            beginDrag(ctx, track, st, hit, e);
            e.currentElement.startDrag(null, null);
            e.stopPropagation();
        } else {
            // empty press → start a marquee
            st.marquee = true;
            st.marqueeAdditive = e.isShiftDown();
            st.mX0 = st.mX1 = e.x;
            st.mY0 = st.mY1 = e.y;
            e.currentElement.startDrag(null, null);
            e.stopPropagation();
        }
    }

    private void onLaneDoubleClick(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, UIEvent e) {
        if (track.lock()) return;
        if (hitSignal(ctx, track, e.x) >= 0) return; // double-clicked an existing marker
        addSignal(ctx, track, st, Math.max(0, Math.round(ctx.xToTick(e.x))));
        e.stopPropagation();
    }

    private void addSignal(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, double tick) {
        var signal = new Signal(tick, "signal");
        ctx.pushEdit("photon.gui.editor.timeline.add_signal",
                () -> {
                    track.signals().add(signal);
                    st.selectedSignals.clear();
                    st.selectedSignals.add(track.signals().indexOf(signal));
                    st.explicitSelection = true;
                    ctx.requestRebuild();
                },
                () -> { track.signals().remove(signal); st.selectedSignals.clear(); ctx.requestRebuild(); });
        ctx.setActiveTrack(track);
    }

    private void beginDrag(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, int anchor, UIEvent e) {
        st.dragging = true;
        st.dragAnchor = anchor;
        st.grabOffsetTicks = ctx.xToTick(e.x) - track.signals().get(anchor).time();
        st.dragOrigins.clear();
        for (var i : st.selectedSignals) {
            st.dragOrigins.put(i, track.signals().get(i).time());
        }
        st.dragSnapshot = snapshotSignals(track);
    }

    private void updateDrag(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, UIEvent e) {
        var anchorOrig = st.dragOrigins.get(st.dragAnchor);
        if (anchorOrig == null) return;
        var newAnchor = ctx.snapKeyTick(ctx.xToTick(e.x) - st.grabOffsetTicks, e.isCtrlDown());
        double dTick = Math.round(newAnchor - anchorOrig);
        // keep the earliest selected signal at tick >= 0
        double minOrig = Double.MAX_VALUE;
        for (var v : st.dragOrigins.values()) minOrig = Math.min(minOrig, v);
        if (minOrig + dTick < 0) dTick = -minOrig;
        for (var entry : st.dragOrigins.entrySet()) {
            track.signals().get(entry.getKey()).time(Math.max(0, entry.getValue() + dTick));
        }
        e.stopPropagation();
    }

    private void endDrag(TimelineContext ctx, SignalTrack track, SignalTrackUIState st) {
        st.dragging = false;
        var before = st.dragSnapshot;
        st.dragSnapshot = null;
        st.dragOrigins.clear();
        if (before == null) return;
        var after = snapshotSignals(track);
        boolean moved = false;
        for (int i = 0; i < after.size() && i < before.size(); i++) {
            if (after.get(i).time() != before.get(i).time()) { moved = true; break; }
        }
        if (!moved) return;
        ctx.pushApplied("photon.gui.editor.timeline.edit_signal",
                () -> { restoreSignals(track, after); ctx.requestRebuild(); },
                () -> { restoreSignals(track, before); ctx.requestRebuild(); });
    }

    private void finishMarquee(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, UIElement box) {
        st.marquee = false;
        var x0 = Math.min(st.mX0, st.mX1);
        var x1 = Math.max(st.mX0, st.mX1);
        var y0 = Math.min(st.mY0, st.mY1);
        var y1 = Math.max(st.mY0, st.mY1);
        if (x1 - x0 < 3 && y1 - y0 < 3) { // a click → clear + select the track
            if (!st.marqueeAdditive) {
                st.selectedSignals.clear();
                st.explicitSelection = false;
                ctx.selectTrack(track);
            }
            return;
        }
        if (!st.marqueeAdditive) st.selectedSignals.clear();
        var signals = track.signals();
        for (int i = 0; i < signals.size(); i++) {
            var sx = signalX(ctx, signals.get(i).time());
            if (sx >= x0 && sx <= x1) st.selectedSignals.add(i);
        }
    }

    // ------------------------------------------------------------------ mutations

    private void removeSignalAt(TimelineContext ctx, SignalTrack track, SignalTrackUIState st, int index) {
        if (index < 0 || index >= track.signals().size()) return;
        var before = snapshotSignals(track);
        track.signals().remove(index);
        st.selectedSignals.clear();
        var after = snapshotSignals(track);
        ctx.pushApplied("photon.gui.editor.timeline.edit_signal",
                () -> { restoreSignals(track, after); ctx.requestRebuild(); },
                () -> { restoreSignals(track, before); ctx.requestRebuild(); });
        ctx.requestRebuild();
    }

    private void removeSelectedSignals(TimelineContext ctx, SignalTrack track, SignalTrackUIState st) {
        var before = snapshotSignals(track);
        var indices = new ArrayList<>(st.selectedSignals);
        indices.sort(Comparator.reverseOrder());
        for (var i : indices) {
            if (i >= 0 && i < track.signals().size()) track.signals().remove((int) i);
        }
        st.selectedSignals.clear();
        var after = snapshotSignals(track);
        ctx.pushApplied("photon.gui.editor.timeline.edit_signal",
                () -> { restoreSignals(track, after); ctx.requestRebuild(); },
                () -> { restoreSignals(track, before); ctx.requestRebuild(); });
        ctx.requestRebuild();
    }

    private static List<Signal> snapshotSignals(SignalTrack track) {
        var copy = new ArrayList<Signal>();
        for (var s : track.signals()) copy.add(s.copy());
        return copy;
    }

    private static void restoreSignals(SignalTrack track, List<Signal> snapshot) {
        track.signals().clear();
        for (var s : snapshot) track.signals().add(s.copy());
    }

    // ------------------------------------------------------------------ inspector

    /** Inspect a single signal's name + data without selecting the whole track. */
    private void inspectSignal(TimelineContext ctx, SignalTrack track, int index) {
        if (index < 0 || index >= track.signals().size()) return;
        var signal = track.signals().get(index);
        var cfg = IConfigurable.create(group -> {
            group.addConfigurator(new StringConfigurator("photon.gui.editor.timeline.signal_name",
                    signal::name, signal::name, signal.name(), true));
            group.addConfigurator(new TagConfigurator("photon.gui.editor.timeline.signal_data",
                    signal::data, tag -> { if (tag instanceof CompoundTag c) signal.data(c); },
                    signal.data(), true));
        });
        ctx.inspectProperty(track, cfg);
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
