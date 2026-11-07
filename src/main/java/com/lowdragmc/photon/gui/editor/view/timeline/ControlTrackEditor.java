package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.ui.*;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/** Editor for {@code control} tracks: a name header + per-clip object-bound clips with seeds. */
public class ControlTrackEditor extends ClipTrackEditor {

    public static class ControlTrackUIState extends ClipTrackUIState {
        boolean editingName;
        String editingNameBefore = "";
    }

    @Override
    public ControlTrackUIState createState() {
        return new ControlTrackUIState();
    }

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.CYAN;
    }

    @Override
    public ColorPattern clipFillColor() {
        return ColorPattern.T_CYAN;
    }

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (ControlTrackUIState) state;
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
        var label = new Label().setText(track.displayName().isEmpty() ? "Control" : track.displayName());
        TimelineContext.styleLabel(label);
        label.setId("timeline.trackHeader.name").layout(layout -> layout.flex(1).heightPercent(100));
        label.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
            st.editingNameBefore = track.displayName();
            st.editingName = true;
            ctx.requestRebuild();
        });
        return label;
    }

    private void commitRename(TimelineContext ctx, Track track, ControlTrackUIState st) {
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

    @Override
    public boolean clipsConflict(Clip a, Clip b) {
        // control clips only conflict when they drive the same target
        return Objects.equals(a.targetId(), b.targetId()) && super.clipsConflict(a, b);
    }

    @Override
    protected String clipLabel(TimelineContext ctx, Track track, Clip clip) {
        var runtime = ctx.runtime();
        if (clip.targetId() == null || runtime == null) return null;
        var bound = runtime.objects.get(clip.targetId());
        return bound == null ? "?" : bound.getName();
    }

    @Override
    protected void onLaneDrop(TimelineContext ctx, Track track, UIEvent event) {
        var runtime = ctx.runtime();
        if (runtime == null || track.lock()) return;
        if (event.dragHandler.getDraggingObject() instanceof FXHierarchyView.DraggingNode(var node)) {
            var id = node.getKey().transform().id();
            ctx.addClip(track, new Clip(Math.max(0, Math.round(ctx.xToTick(event.x))),
                    ctx.defaultClipDuration(id), 1.0f).targetId(id));
            event.stopPropagation();
        }
    }

    @Override
    protected void buildClipMenu(TreeBuilder.Menu menu, TimelineContext ctx, Track track, Clip clip) {
        menu.leaf(Component.translatable(clip.randomSeed()
                ? "photon.gui.editor.timeline.seed_random_on" : "photon.gui.editor.timeline.seed_random_off"), () -> {
            var old = clip.randomSeed();
            ctx.pushEdit("photon.gui.editor.timeline.edit_clip",
                    () -> { clip.randomSeed(!old); ctx.requestRebuild(); ctx.refreshPreview(); },
                    () -> { clip.randomSeed(old); ctx.requestRebuild(); ctx.refreshPreview(); });
        });
        menu.leaf(Component.translatable(clip.exitMode() == Clip.ExitMode.DRAIN
                ? "photon.gui.editor.timeline.exit_mode_drain" : "photon.gui.editor.timeline.exit_mode_cut"), () -> {
            var old = clip.exitMode();
            var flipped = old == Clip.ExitMode.DRAIN ? Clip.ExitMode.CUT : Clip.ExitMode.DRAIN;
            ctx.pushEdit("photon.gui.editor.timeline.edit_clip",
                    () -> { clip.exitMode(flipped); ctx.requestRebuild(); ctx.refreshPreview(); },
                    () -> { clip.exitMode(old); ctx.requestRebuild(); ctx.refreshPreview(); });
        });
        if (hasSections(ctx)) {
            menu.leaf(Component.translatable(clip.seamMode() == Clip.SeamMode.RESTART
                    ? "photon.gui.editor.timeline.seam_mode_restart" : "photon.gui.editor.timeline.seam_mode_auto"), () -> {
                var old = clip.seamMode();
                var flipped = old == Clip.SeamMode.RESTART ? Clip.SeamMode.AUTO : Clip.SeamMode.RESTART;
                ctx.pushEdit("photon.gui.editor.timeline.edit_clip",
                        () -> { clip.seamMode(flipped); ctx.requestRebuild(); ctx.refreshPreview(); },
                        () -> { clip.seamMode(old); ctx.requestRebuild(); ctx.refreshPreview(); });
            });
        }
        menu.leaf(Component.translatable("photon.gui.editor.timeline.reseed"), () -> {
            var oldSeed = clip.seed();
            var oldRandom = clip.randomSeed();
            var newSeed = new Random().nextLong();
            ctx.pushEdit("photon.gui.editor.timeline.edit_clip",
                    () -> { clip.seed(newSeed).randomSeed(false); ctx.refreshPreview(); },
                    () -> { clip.seed(oldSeed).randomSeed(oldRandom); ctx.refreshPreview(); });
        });
    }

    @Override
    protected void buildClipConfigurator(ConfiguratorGroup group, TimelineContext ctx, Track track, Clip clip) {
        group.addConfigurator(new ConfiguratorSelectorConfigurator<>("photon.gui.editor.timeline.edit_clip.random_seed", clip::randomSeed,
                v -> { clip.randomSeed(v); ctx.refreshPreview(); }, clip.randomSeed(), true,
                List.of(true, false),
                (randomSeed) -> randomSeed ?
                        "photon.gui.editor.timeline.edit_clip.random_seed.random" :
                        "photon.gui.editor.timeline.edit_clip.random_seed.fixed",
                (randomSeed, subGroup) -> {
                    if (!randomSeed) {
                        subGroup.addConfigurator(new NumberConfigurator("photon.gui.editor.timeline.edit_clip.random_seed.seed", () -> (double) clip.seed(),
                                v -> { clip.seed(v.longValue()); ctx.refreshPreview(); }, (double) clip.seed(), true));
                    }
                })
        );
        group.addConfigurator(new SelectorConfigurator<>("photon.gui.editor.timeline.edit_clip.exit_mode", clip::exitMode,
                v -> { clip.exitMode(v); ctx.refreshPreview(); }, clip.exitMode(), true,
                List.of(Clip.ExitMode.values()),
                mode -> "photon.gui.editor.timeline.edit_clip.exit_mode." + mode.name().toLowerCase(Locale.ROOT)));
        // without sections nothing loops, so there is no seam
        if (hasSections(ctx)) {
            group.addConfigurator(new SelectorConfigurator<>("photon.gui.editor.timeline.edit_clip.seam_mode", clip::seamMode,
                    v -> { clip.seamMode(v); ctx.refreshPreview(); }, clip.seamMode(), true,
                    List.of(Clip.SeamMode.values()),
                    mode -> "photon.gui.editor.timeline.edit_clip.seam_mode." + mode.name().toLowerCase(Locale.ROOT)));
        }
    }

    private static boolean hasSections(TimelineContext ctx) {
        var runtime = ctx.runtime();
        return runtime != null && !runtime.fxData.timeline().sections().isEmpty();
    }

    @Override
    protected void drawClipDecoration(GUIContext graphics, TimelineContext ctx, Track track, Clip clip,
                                      float x, float y, float w, float h, float pt) {
        if (clip.exitMode() == Clip.ExitMode.DRAIN) {
            var tail = Math.min(12, w / 3);
            DrawerHelperClient.drawGradientRect(graphics, x + w - tail, y + 1, tail, h - 2, 0x00000000, 0xA0000000, true);
        }
        if (clip.seamMode() == Clip.SeamMode.RESTART && w >= 14) {
            graphics.drawTexture(PhotonIcons.LOOP, x + w - 10, y + 1, 9, 9);
        }
    }

    @Override
    protected void buildHeaderConfigurator(ConfiguratorGroup group, TimelineContext ctx, Track track) {
        group.addConfigurator(new StringConfigurator("name", track::displayName,
                v -> { track.displayName(v); ctx.requestRebuild(); }, track.displayName(), true));
    }
}
