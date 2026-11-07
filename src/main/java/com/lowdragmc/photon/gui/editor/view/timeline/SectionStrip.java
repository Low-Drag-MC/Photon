package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.fx.timeline.TimelineSection;
import com.lowdragmc.photon.gui.editor.view.FXTimelineView;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The timeline's section row: a band per section to select, drag by its left edge and edit from its menu; the section
 * the preview is in is highlighted. Bands are classed ({@code __timeline_section__} plus {@code __alt__},
 * {@code __current__}, {@code __selected__}, {@code __release__}, {@code __empty__}) with DEFAULT-origin looks.
 */
public class SectionStrip {
    public static final int HEIGHT = 15;
    private static final float EDGE_HIT = 4;
    private static final int BACKGROUND = 0xFF202020;
    private static final int FILL = 0x50707A88;
    private static final int FILL_ALT = 0x40505A66;
    private static final int FILL_CURRENT = 0x804A90C8;
    private static final int FILL_EMPTY = 0x80C04040;
    private static final int EDGE = 0xC0D0D0D0;
    private static final int RELEASE_EDGE = 0xFFE0A040;
    private static final int GUIDE = 0x30FFFFFF;

    private final FXTimelineView view;
    public final UIElement strip = new UIElement();
    public final UIElement header = new UIElement();
    private final List<Band> bands = new ArrayList<>();
    @Nullable
    private Timeline shownTimeline;
    @Nullable
    private TimelineSection selected;
    @Nullable
    private TimelineSection dragging;
    private double dragGrab;
    @Nullable
    private Snapshot dragBefore;
    @Nullable
    private String shownCurrent;
    private int shownPass = -1;
    private double shownDuration = -1;

    private record Band(TimelineSection section, UIElement element, UIElement edge, UIElement loop, Label badge, int index) {
    }

    private record SectionState(String name, double start, int repeat, TimelineSection.Then then, String target) {
        static SectionState of(TimelineSection section) {
            return new SectionState(section.name(), section.start(), section.repeat(), section.then(), section.target());
        }

        TimelineSection create() {
            return new TimelineSection(name, start).repeat(repeat).then(then).target(target);
        }
    }

    /** Everything a section edit can change, for undo. */
    private record Snapshot(List<SectionState> sections, @Nullable String release, boolean releaseNow) {
    }

    public SectionStrip(FXTimelineView view) {
        this.view = view;
        strip.setId("timeline.sections").addClass("__timeline_sections__")
                .layout(layout -> layout.widthPercent(100).height(HEIGHT))
                .setOverflowVisible(false)
                .style(style -> Style.defaultPipeline(style, s -> s.backgroundTexture(new ColorRectTexture(BACKGROUND))));
        strip.addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown);
        strip.addEventListener(UIEvents.DOUBLE_CLICK, this::onDoubleClick);
        strip.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::onDrag);
        strip.addEventListener(UIEvents.DRAG_END, e -> endDrag());
        strip.addEventListener(UIEvents.MOUSE_WHEEL, view::zoom);
        var title = new Label();
        title.setText("photon.gui.editor.timeline.sections");
        TimelineContext.styleLabel(title);
        title.layout(layout -> layout.flex(1).heightPercent(100));
        header.setId("timeline.sectionsHeader").addClass("__timeline_sections_header__")
                .layout(layout -> layout.widthPercent(100).height(HEIGHT).paddingLeft(4))
                .addChild(title);
        setShown(false);
    }

    @Nullable
    private Timeline timeline() {
        var runtime = view.runtime();
        return runtime == null ? null : runtime.fxData.timeline();
    }

    // ---- bands ---------------------------------------------------------------------------------------------------

    public void rebuild() {
        strip.clearAllChildren();
        bands.clear();
        shownCurrent = null;
        shownPass = -1;
        shownDuration = -1;
        var timeline = timeline();
        shownTimeline = timeline;
        if (timeline == null || timeline.sections().isEmpty()) {
            selected = null;
            setShown(false);
            return;
        }
        setShown(true);
        if (selected != null && !timeline.sections().contains(selected)) selected = null;
        var sorted = sorted(timeline);
        for (int i = 0; i < sorted.size(); i++) {
            var section = sorted.get(i);
            var element = new UIElement().addClass("__timeline_section__").layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.top(1);
                layout.height(HEIGHT - 2);
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.paddingRight(3);
                layout.gapAll(2);
            }).setOverflowVisible(false);
            var edge = new UIElement().addClass("__timeline_section_edge__").layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(0);
                layout.top(0);
                layout.heightPercent(100);
            });
            var name = new Label();
            name.setText(section.name(), false);
            TimelineContext.styleLabel(name);
            // a margin, not the band's padding: the band clips to its content box, which would hide the edge line
            name.layout(layout -> layout.flex(1).heightPercent(100).marginLeft(4));
            var loop = new UIElement().addClass("__timeline_section_loop__")
                    .layout(layout -> layout.width(9).height(9).flexShrink(0))
                    .style(style -> Style.defaultPipeline(style, s -> s.backgroundTexture(PhotonIcons.LOOP)));
            var badge = new Label();
            TimelineContext.styleLabel(badge);
            badge.textStyle(style -> style.adaptiveWidth(true));
            badge.layout(layout -> layout.heightPercent(100).flexShrink(0));
            element.addChildren(edge, name, loop, badge);
            bands.add(new Band(section, element, edge, loop, badge, i));
            strip.addChild(element);
        }
        reposition();
        tick();
    }

    private void setShown(boolean shown) {
        strip.setDisplay(shown);
        header.setDisplay(shown);
    }

    /** Lay the bands out for the current zoom and scroll (and a content end that may have moved). */
    public void reposition() {
        var timeline = timeline();
        if (timeline == null || bands.isEmpty()) return;
        var duration = timeline.getDuration();
        // ticks map onto the lanes while the bands sit in the strip: line the two up
        var offset = view.originX() - strip.getContentX();
        for (var band : bands) {
            var start = band.section().start();
            var end = endOf(timeline, band.section(), duration);
            band.element().layout(layout -> {
                layout.left(offset + view.tickToLocalX(start));
                layout.width((float) Math.max(4, (end - start) * view.scale()));
            });
        }
    }

    /** The tick a section ends at: the next one's start, the content end after the last. */
    private static double endOf(Timeline timeline, TimelineSection section, double duration) {
        var end = duration;
        for (var other : timeline.sections()) {
            if (other.start() > section.start() && other.start() < end) end = other.start();
        }
        return end;
    }

    private static List<TimelineSection> sorted(Timeline timeline) {
        var list = new ArrayList<>(timeline.sections());
        list.sort(Comparator.comparingDouble(TimelineSection::start));
        return list;
    }

    private void restyle(Band band, Timeline timeline, double duration) {
        var section = band.section();
        var end = endOf(timeline, section, duration);
        var empty = end <= section.start();
        var current = section.name().equals(shownCurrent);
        var release = section.name().equals(timeline.releaseSection());
        var isSelected = section == selected;
        var fill = empty ? FILL_EMPTY : current ? FILL_CURRENT : band.index() % 2 == 0 ? FILL : FILL_ALT;
        IGuiTexture overlay = isSelected ? ColorPattern.WHITE.borderTexture(1) : IGuiTexture.EMPTY;
        var element = band.element();
        element.style(style -> Style.defaultPipeline(style, s -> s.backgroundTexture(new ColorRectTexture(fill))
                .overlayTexture(overlay)));
        setClass(element, "__alt__", band.index() % 2 == 1);
        setClass(element, "__current__", current);
        setClass(element, "__selected__", isSelected);
        setClass(element, "__release__", release);
        setClass(element, "__empty__", empty);
        band.edge().layout(layout -> layout.width(release ? 3 : 1));
        band.edge().style(style -> Style.defaultPipeline(style,
                s -> s.backgroundTexture(new ColorRectTexture(release ? RELEASE_EDGE : EDGE))));
        band.loop().setDisplay(section.repeat() == TimelineSection.FOREVER);
        var text = badge(section);
        if (current && section.repeat() != 1) text = (text.isEmpty() ? "#" : text + " #") + (shownPass + 1);
        band.badge().setText(text, false);
        band.badge().setDisplay(!text.isEmpty());
    }

    private static void setClass(UIElement element, String name, boolean on) {
        if (on) element.addClass(name);
        else element.removeClass(name);
    }

    /** Where a section goes after its passes, in a few characters (a forever loop is the loop icon instead). */
    private static String badge(TimelineSection section) {
        var out = new StringBuilder();
        if (section.repeat() > 1) out.append("×").append(section.repeat());
        var then = switch (section.then()) {
            case END -> "■";
            case GOTO -> "→" + section.target();
            case LINEAR -> "";
        };
        if (!then.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(then);
        return out.toString();
    }

    /** Per UI tick: follow model changes made elsewhere, and show the section the preview is in. */
    public void tick() {
        var timeline = timeline();
        var count = timeline == null ? 0 : timeline.sections().size();
        if (timeline != shownTimeline || count != bands.size()) {
            rebuild();
            return;
        }
        if (timeline == null || bands.isEmpty()) return;
        var runtime = view.runtime();
        var current = runtime == null ? null : runtime.sections().current();
        var pass = runtime == null ? 0 : runtime.sections().pass();
        var duration = timeline.getDuration();
        if (Objects.equals(current, shownCurrent) && pass == shownPass && duration == shownDuration) return;
        shownCurrent = current;
        shownPass = pass;
        shownDuration = duration;
        for (var band : bands) restyle(band, timeline, duration);
    }

    private void restyleAll() {
        var timeline = timeline();
        if (timeline == null) return;
        var duration = timeline.getDuration();
        for (var band : bands) restyle(band, timeline, duration);
    }

    /** Faint lines down the lanes at each section start, to line clips up with sections. */
    public void drawGuides(GUIContext graphics, float x, float y, float width, float height) {
        var timeline = timeline();
        if (timeline == null) return;
        for (var section : timeline.sections()) {
            if (section.start() <= 0) continue;
            var gx = view.originX() + view.tickToLocalX(section.start());
            if (gx < x || gx > x + width) continue;
            DrawerHelperClient.drawSolidRect(graphics, gx, y, 1, height, GUIDE);
        }
    }

    // ---- selection -----------------------------------------------------------------------------------------------

    @Nullable
    public TimelineSection selected() {
        return selected;
    }

    @Nullable
    public UIElement band(String name) {
        for (var band : bands) {
            if (band.section().name().equals(name)) return band.element();
        }
        return null;
    }

    public void select(TimelineSection section) {
        view.clearSelectionForSection();
        selected = section;
        view.editor().inspectorView.inspect(configurator(section), null, () -> {});
        restyleAll();
    }

    /** Another kind of selection took over (a clip, a track, a marker). */
    public void deselect() {
        if (selected == null) return;
        selected = null;
        restyleAll();
    }

    /** Delete pressed: remove the selected section, if any. */
    public boolean deleteSelected() {
        if (selected == null) return false;
        remove(selected);
        return true;
    }

    @Nullable
    private Band bandAt(float x) {
        for (var band : bands) {
            var left = band.element().getPositionX();
            if (x >= left && x < left + band.element().getSizeWidth()) return band;
        }
        return null;
    }

    /** The band whose left edge is under {@code x}, to drag. */
    @Nullable
    private Band edgeAt(float x) {
        Band best = null;
        var bestDistance = EDGE_HIT;
        for (var band : bands) {
            var distance = Math.abs(x - band.element().getPositionX());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = band;
            }
        }
        return best;
    }

    // ---- mouse ---------------------------------------------------------------------------------------------------

    private void onMouseDown(UIEvent e) {
        if (e.button == 1) {
            var band = bandAt(e.x);
            openMenu(e.x, e.y, band == null ? null : band.section());
            e.stopPropagation();
            return;
        }
        if (e.button != 0) return;
        e.stopPropagation();
        var edge = edgeAt(e.x);
        var band = edge != null ? edge : bandAt(e.x);
        if (band == null) {
            deselect();
            return;
        }
        if (band.section() != selected) select(band.section());
        if (edge != null) {
            dragging = edge.section();
            dragGrab = view.xToTick(e.x) - edge.section().start();
            dragBefore = snapshot();
            view.setSnapExcludedSection(edge.section());
            strip.startDrag(null, null);
        }
    }

    private void onDrag(UIEvent e) {
        var section = dragging;
        var timeline = timeline();
        if (section == null || timeline == null) return;
        // stay between the neighbours (and short of the content end): a drag never reorders sections
        var lower = 0.0;
        var upper = Math.max(0, Math.ceil(timeline.getDuration()) - 1);
        for (var other : timeline.sections()) {
            if (other == section) continue;
            if (other.start() < section.start()) lower = Math.max(lower, other.start() + 1);
            else upper = Math.min(upper, other.start() - 1);
        }
        var tick = Math.round(view.snapKeyTick(view.xToTick(e.x) - dragGrab, e.isCtrlDown()));
        section.start(Math.max(lower, Math.min(Math.max(lower, upper), tick)));
        reposition();
    }

    private void endDrag() {
        var before = dragBefore;
        dragging = null;
        dragBefore = null;
        view.setSnapExcludedSection(null);
        if (before == null) return;
        var after = snapshot();
        if (before.equals(after)) return;
        view.pushApplied("photon.gui.editor.timeline.section.move", () -> restore(after), () -> restore(before));
        rebuild();
        view.refreshPreview();
    }

    private void onDoubleClick(UIEvent e) {
        e.stopPropagation();
        var band = bandAt(e.x);
        if (band != null) rename(band.section());
        else add(Math.max(0, Math.round(view.xToTick(e.x))));
    }

    // ---- menus ---------------------------------------------------------------------------------------------------

    private void openMenu(float x, float y, @Nullable TimelineSection section) {
        var menu = TreeBuilder.Menu.start();
        if (section == null) addMenuItems(menu, Math.max(0, Math.round(view.xToTick(x))));
        else sectionMenu(menu, section);
        view.openMenu(x, y, menu);
    }

    /** "Add a section here", and on a timeline without sections the start / loop / end template. Also on the ruler. */
    public void addMenuItems(TreeBuilder.Menu menu, long tick) {
        var timeline = timeline();
        if (timeline == null) return;
        menu.leaf("photon.gui.editor.timeline.section.add_here", () -> add(tick));
        if (timeline.sections().isEmpty()) {
            menu.leaf("photon.gui.editor.timeline.section.quick_create", this::quickCreate);
        }
    }

    private void sectionMenu(TreeBuilder.Menu menu, TimelineSection section) {
        var runtime = view.runtime();
        if (runtime != null && view.isPlaying() && view.isFollowingSections()) {
            menu.leaf("photon.gui.editor.timeline.section.jump_now", () -> runtime.sections().jumpTo(section.name()));
            menu.leaf("photon.gui.editor.timeline.section.queue", () -> runtime.sections().queue(section.name()));
            menu.crossLine();
        }
        menu.branch("photon.gui.editor.timeline.section.then", then -> {
            then.leaf("photon.gui.editor.timeline.section.then.linear",
                    () -> edit(() -> section.then(TimelineSection.Then.LINEAR).target("")));
            then.leaf("photon.gui.editor.timeline.section.then.end",
                    () -> edit(() -> section.then(TimelineSection.Then.END).target("")));
            var timeline = timeline();
            if (timeline == null) return;
            for (var other : sorted(timeline)) {
                if (other != section) {
                    then.leaf(Component.literal("→ " + other.name()),
                            () -> edit(() -> section.then(TimelineSection.Then.GOTO).target(other.name())));
                }
            }
        });
        menu.branch("photon.gui.editor.timeline.section.repeat", repeat -> {
            repeat.leaf("photon.gui.editor.timeline.section.repeat.once", () -> edit(() -> section.repeat(1)));
            repeat.leaf("photon.gui.editor.timeline.section.repeat.times", () -> askTimes(section));
            repeat.leaf("photon.gui.editor.timeline.section.repeat.forever", () -> edit(() -> section.repeat(TimelineSection.FOREVER)));
        });
        var isRelease = isRelease(section);
        menu.leaf(isRelease ? "photon.gui.editor.timeline.section.unset_release" : "photon.gui.editor.timeline.section.set_release",
                () -> edit(() -> {
                    var timeline = timeline();
                    if (timeline != null) timeline.releaseSection(isRelease ? null : section.name());
                }));
        menu.crossLine();
        menu.leaf("photon.gui.editor.timeline.section.copy_command", () -> Minecraft.getInstance().keyboardHandler
                .setClipboard("/photon fx section entity @s jump " + quoted(section.name())));
        menu.crossLine();
        menu.leaf("photon.gui.editor.timeline.section.rename", () -> rename(section));
        menu.leaf("ldlib.gui.editor.menu.remove", () -> remove(section));
    }

    /** A quoted command argument. */
    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void askTimes(TimelineSection section) {
        Dialog.stringEditorDialog("photon.gui.editor.timeline.section.times_dialog",
                Integer.toString(Math.max(2, section.repeat())),
                s -> s.matches("\\d{1,4}") && Integer.parseInt(s) >= 1,
                s -> edit(() -> section.repeat(Integer.parseInt(s)))).show(view.editor());
    }

    private void rename(TimelineSection section) {
        Dialog.stringEditorDialog("photon.gui.editor.timeline.section.rename", section.name(),
                s -> isFreeName(s, section),
                s -> edit(() -> renameTo(section, s.trim()))).show(view.editor());
    }

    private boolean isFreeName(@Nullable String name, @Nullable TimelineSection except) {
        var timeline = timeline();
        if (name == null || name.isBlank() || timeline == null) return false;
        for (var other : timeline.sections()) {
            if (other != except && other.name().equals(name.trim())) return false;
        }
        return true;
    }

    /** Rename, keeping what refers to the old name (jump targets, the release section) pointing at it. */
    private void renameTo(TimelineSection section, String name) {
        var timeline = timeline();
        if (timeline == null || !isFreeName(name, section)) return;
        var old = section.name();
        for (var other : timeline.sections()) {
            if (other.then() == TimelineSection.Then.GOTO && other.target().equals(old)) other.target(name);
        }
        if (old.equals(timeline.releaseSection())) timeline.releaseSection(name);
        section.name(name);
    }

    // ---- edits ---------------------------------------------------------------------------------------------------

    private String freeName(String base) {
        if (isFreeName(base, null)) return base;
        for (int i = 2; ; i++) {
            if (isFreeName(base + " " + i, null)) return base + " " + i;
        }
    }

    /** Add a section starting at {@code tick}; one already starting there is selected instead. */
    public void add(long tick) {
        var timeline = timeline();
        if (timeline == null) return;
        for (var other : timeline.sections()) {
            if (other.start() == tick) {
                select(other);
                return;
            }
        }
        var section = new TimelineSection(freeName("Section " + (timeline.sections().size() + 1)), tick);
        edit("photon.gui.editor.timeline.section.add", () -> timeline.sections().add(section));
        select(section);
    }

    /** The common shape in one go: a start, a loop that repeats until moved on, and an end that stopping the effect plays. */
    public void quickCreate() {
        var timeline = timeline();
        if (timeline == null || !timeline.sections().isEmpty()) return;
        var duration = timeline.getDuration() >= 3 ? timeline.getDuration() : 60;
        var loop = new TimelineSection("Loop", Math.round(duration / 3)).repeat(TimelineSection.FOREVER);
        edit("photon.gui.editor.timeline.section.add", () -> {
            timeline.sections().add(new TimelineSection("Start", 0));
            timeline.sections().add(loop);
            timeline.sections().add(new TimelineSection("End", Math.round(duration * 2 / 3)));
            timeline.releaseSection("End").releaseNow(true);
        });
        select(loop);
    }

    public void remove(TimelineSection section) {
        edit("photon.gui.editor.timeline.section.remove", () -> {
            var timeline = timeline();
            if (timeline == null) return;
            timeline.sections().remove(section);
            for (var other : timeline.sections()) {
                if (other.then() == TimelineSection.Then.GOTO && other.target().equals(section.name())) {
                    other.then(TimelineSection.Then.LINEAR).target("");
                }
            }
            if (section.name().equals(timeline.releaseSection())) timeline.releaseSection(null);
        });
    }

    private void edit(Runnable change) {
        edit("photon.gui.editor.timeline.section.edit", change);
    }

    /** Apply a change as one undoable step: the whole section state before and after it. */
    private void edit(String name, Runnable change) {
        var before = snapshot();
        change.run();
        var after = snapshot();
        if (before.equals(after)) return;
        view.pushApplied(name, () -> restore(after), () -> restore(before));
        var timeline = timeline();
        if (selected != null && (timeline == null || !timeline.sections().contains(selected))) {
            selected = null;
            view.editor().inspectorView.clear();
        }
        rebuild();
        view.refreshPreview();
    }

    private Snapshot snapshot() {
        var timeline = timeline();
        if (timeline == null) return new Snapshot(List.of(), null, true);
        return new Snapshot(timeline.sections().stream().map(SectionState::of).toList(),
                timeline.releaseSection(), timeline.releaseNow());
    }

    private void restore(Snapshot snapshot) {
        var timeline = timeline();
        if (timeline == null) return;
        var sections = timeline.sections();
        var index = selected == null ? -1 : sections.indexOf(selected);
        var name = selected == null ? null : selected.name();
        var sameCount = sections.size() == snapshot.sections().size();
        sections.clear();
        for (var state : snapshot.sections()) sections.add(state.create());
        timeline.releaseSection(snapshot.release()).releaseNow(snapshot.releaseNow());
        TimelineSection reselect = null;
        if (index >= 0 && sameCount) {
            reselect = sections.get(index);
        } else if (name != null) {
            for (var section : sections) {
                if (section.name().equals(name)) reselect = section;
            }
        }
        selected = null;
        rebuild();
        // the inspector edits the replaced object: point it at the new one
        if (reselect != null) select(reselect);
        else if (index >= 0) view.editor().inspectorView.clear();
        view.refreshPreview();
    }

    // ---- inspector -----------------------------------------------------------------------------------------------

    private enum RepeatKind { ONCE, TIMES, FOREVER }

    private static RepeatKind repeatKind(TimelineSection section) {
        return section.repeat() == TimelineSection.FOREVER ? RepeatKind.FOREVER
                : section.repeat() > 1 ? RepeatKind.TIMES : RepeatKind.ONCE;
    }

    private static String thenKey(TimelineSection section) {
        return switch (section.then()) {
            case LINEAR -> "linear";
            case END -> "end";
            case GOTO -> "goto:" + section.target();
        };
    }

    private boolean isRelease(TimelineSection section) {
        var timeline = timeline();
        return timeline != null && section.name().equals(timeline.releaseSection());
    }

    private IConfigurable configurator(TimelineSection section) {
        return IConfigurable.create(group -> {
            group.addConfigurator(new StringConfigurator("photon.gui.editor.timeline.section.name", section::name, v -> {
                if (!v.trim().equals(section.name()) && isFreeName(v, section)) {
                    renameTo(section, v.trim());
                    changed();
                }
            }, section.name(), true));
            group.addConfigurator(new NumberConfigurator("photon.gui.editor.timeline.section.start",
                    () -> (int) Math.round(section.start()), v -> {
                var start = Math.max(0, v.intValue());
                var timeline = timeline();
                if (timeline == null || start == section.start()) return;
                for (var other : timeline.sections()) {
                    if (other != section && other.start() == start) return;
                }
                section.start(start);
                changed();
            }, (int) Math.round(section.start()), true).setRange(0, 1_000_000));
            group.addConfigurator(new ConfiguratorSelectorConfigurator<>("photon.gui.editor.timeline.section.repeat",
                    () -> repeatKind(section), kind -> {
                if (kind == repeatKind(section)) return;
                section.repeat(switch (kind) {
                    case ONCE -> 1;
                    case TIMES -> 2;
                    case FOREVER -> TimelineSection.FOREVER;
                });
                changed();
            }, repeatKind(section), true, List.of(RepeatKind.values()),
                    kind -> "photon.gui.editor.timeline.section.repeat." + kind.name().toLowerCase(Locale.ROOT),
                    (kind, sub) -> {
                        if (kind != RepeatKind.TIMES) return;
                        sub.addConfigurator(new NumberConfigurator("photon.gui.editor.timeline.section.repeat_count",
                                () -> Math.max(2, section.repeat()), v -> {
                            if (repeatKind(section) != RepeatKind.TIMES || v.intValue() == section.repeat()) return;
                            section.repeat(Math.max(2, v.intValue()));
                            changed();
                        }, Math.max(2, section.repeat()), true).setRange(2, 9999));
                    }));
            var thenOptions = new ArrayList<>(List.of("linear", "end"));
            var timeline = timeline();
            if (timeline != null) {
                for (var other : sorted(timeline)) {
                    if (other != section) thenOptions.add("goto:" + other.name());
                }
            }
            group.addConfigurator(new SelectorConfigurator<>("photon.gui.editor.timeline.section.then",
                    () -> thenKey(section), key -> {
                if (key.equals(thenKey(section))) return;
                if (key.startsWith("goto:")) section.then(TimelineSection.Then.GOTO).target(key.substring(5));
                else section.then("end".equals(key) ? TimelineSection.Then.END : TimelineSection.Then.LINEAR).target("");
                changed();
            }, thenKey(section), true, thenOptions,
                    key -> key.startsWith("goto:") ? "→ " + key.substring(5) : "photon.gui.editor.timeline.section.then." + key));
            group.addConfigurator(new ConfiguratorSelectorConfigurator<>("photon.gui.editor.timeline.section.release",
                    () -> isRelease(section), on -> {
                var t = timeline();
                if (t == null || on == isRelease(section)) return;
                t.releaseSection(on ? section.name() : null);
                changed();
            }, isRelease(section), true, List.of(false, true),
                    on -> on ? "photon.gui.editor.timeline.section.release.yes" : "photon.gui.editor.timeline.section.release.no",
                    (on, sub) -> {
                        if (!on) return;
                        sub.addConfigurator(new SelectorConfigurator<>("photon.gui.editor.timeline.section.release_timing",
                                () -> {
                                    var t = timeline();
                                    return t == null || t.releaseNow();
                                }, now -> {
                            var t = timeline();
                            if (t == null || t.releaseNow() == now) return;
                            t.releaseNow(now);
                            changed();
                        }, timeline == null || timeline.releaseNow(), true, List.of(true, false),
                                now -> now ? "photon.gui.editor.timeline.section.timing.now"
                                        : "photon.gui.editor.timeline.section.timing.section_end"));
                    }));
        });
    }

    /** An inspector edit (not on the undo history, like the clip inspector's): redraw and replay. */
    private void changed() {
        rebuild();
        view.refreshPreview();
    }
}
