package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.fx.timeline.TimelineSection;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.FXTimelineView;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;
import com.lowdragmc.photon.gui.editor.view.timeline.SectionStrip;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The section row of the timeline editor and the preview that follows sections. Edits are done with real mouse and
 * keyboard input where a user would use them: selecting a band, dragging its left edge, the band's menu, double-click
 * to add and rename, Delete; each is undone. The preview loops a forever section with the playhead staying on the
 * timeline's time, moves on with Advance, plays the release section with Release, plays in time order with follow off
 * and on seeks, and stops at an "end" section in timeline-end mode.
 */
@LDLRegisterClient(name = "timeline_sections_editor", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TimelineSectionsEditorScenario implements UIScenario {
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("load Appear [0,20), Idle [20,60) forever, Vanish [60,90) released at once", ctx -> load(ctx, true))
                .frames(5)
                .step("pause at 5", ctx -> seek(ctx, 5))
                .frames(5)

                .step("a band per section, the release one marked", ctx -> {
                    var strip = strip(ctx);
                    ctx.check("the strip shows", strip.strip.isDisplayed() && strip.header.isDisplayed(), "shown", "hidden");
                    ctx.check("three bands", ctx.query().withClass("__timeline_section__").list().size() == 3, 3,
                            ctx.query().withClass("__timeline_section__").list().size());
                    ctx.check("Vanish is the release band", band(ctx, "Vanish").hasClass("__release__"), "release", "not");
                    ctx.check("Idle is not", !band(ctx, "Idle").hasClass("__release__"), "not", "release");
                })
                .step("bands line up with the ticks", ctx -> {
                    var idle = band(ctx, "Idle");
                    ctx.check("Idle starts at tick 20", Math.abs(idle.getPositionX() - x(ctx, 20)) <= 1, x(ctx, 20), idle.getPositionX());
                    var width = 40 * view(ctx).scale();
                    ctx.check("and spans 40 ticks", Math.abs(idle.getSizeWidth() - width) <= 1, width, idle.getSizeWidth());
                })
                .step("timeline", ctx -> ctx.screenshotElement("strip", ctx.query().type(FXTimelineView.class).one()))

                .click("the Idle band", ctx -> band(ctx, "Idle"), LEFT)
                .frames(3)
                .step("clicking a band selects it and inspects it", ctx -> {
                    var selected = strip(ctx).selected();
                    ctx.check("Idle selected", selected != null && "Idle".equals(selected.name()), "Idle",
                            selected == null ? null : selected.name());
                    ctx.check("its band shows it", band(ctx, "Idle").hasClass("__selected__"), "selected", "not");
                    ctx.check("the inspector edits the section",
                            !ctx.query().withTextContaining("Release Section").list().isEmpty(), "shown", "missing");
                })
                .step("inspector", ctx -> ctx.screenshot("inspector"))
                .step("select a clip", ctx -> {
                    var track = timeline(ctx).tracks().getFirst();
                    view(ctx).selectClip(track, track.clips().getFirst());
                })
                .frames(2)
                .step("a clip takes the selection over", ctx -> {
                    ctx.check("no section selected", strip(ctx).selected() == null, null, strip(ctx).selected());
                    ctx.check("the band shows it", !band(ctx, "Idle").hasClass("__selected__"), "not", "selected");
                })
                .click("the Idle band again", ctx -> band(ctx, "Idle"), LEFT)
                .frames(2)
                .step("and a section takes it back", ctx ->
                        ctx.check("no clip selected", view(ctx).selectedClip() == null, null, view(ctx).selectedClip()))

                .drag("Vanish's left edge", ctx -> edge(ctx, "Vanish"), ctx -> new float[]{dropX(ctx, "Vanish", 70), y(ctx)})
                .frames(3)
                .step("dragging the left edge moves the start, to a whole tick", ctx ->
                        ctx.check("Vanish starts at 70", section(ctx, "Vanish").start() == 70, 70.0, section(ctx, "Vanish").start()))
                .step("and the band follows", ctx ->
                        ctx.check("at tick 70", Math.abs(band(ctx, "Vanish").getPositionX() - x(ctx, 70)) <= 1, x(ctx, 70),
                                band(ctx, "Vanish").getPositionX()))
                .step("undo", ctx -> editor(ctx).historyView.undo())
                .frames(2)
                .step("undone", ctx ->
                        ctx.check("back at 60", section(ctx, "Vanish").start() == 60, 60.0, section(ctx, "Vanish").start()))
                .step("redo", ctx -> editor(ctx).historyView.redo())
                .frames(2)
                .step("redone", ctx ->
                        ctx.check("at 70 again", section(ctx, "Vanish").start() == 70, 70.0, section(ctx, "Vanish").start()))
                .step("undo again", ctx -> editor(ctx).historyView.undo())
                .frames(2)
                .step("press Vanish's edge", ctx -> {
                    var edge = edge(ctx, "Vanish");
                    ctx.input().mouseDown(edge.getPositionX() + edge.getSizeWidth() / 2, y(ctx), LEFT);
                })
                .step("drag it to 66", ctx -> ctx.input().dragTo(dropX(ctx, "Vanish", 66), y(ctx), LEFT))
                .frames(2)
                .step("then a tick at a time: 67", ctx -> ctx.input().dragTo(dropX(ctx, "Vanish", 67), y(ctx), LEFT))
                .frames(2)
                .step("68", ctx -> ctx.input().dragTo(dropX(ctx, "Vanish", 68), y(ctx), LEFT))
                .frames(2)
                .step("a slow drag follows every tick: the edge never snaps back to where it just was", ctx ->
                        ctx.check("at 68", section(ctx, "Vanish").start() == 68, 68.0, section(ctx, "Vanish").start()))
                .step("let go", ctx -> ctx.input().mouseUp(dropX(ctx, "Vanish", 68), y(ctx), LEFT))
                .frames(2)
                .step("undo the slow drag", ctx -> editor(ctx).historyView.undo())
                .frames(2)
                .step("back again", ctx ->
                        ctx.check("at 60", section(ctx, "Vanish").start() == 60, 60.0, section(ctx, "Vanish").start()))
                .drag("Idle's edge past Vanish's", ctx -> edge(ctx, "Idle"), ctx -> new float[]{x(ctx, 75), y(ctx)})
                .frames(3)
                .step("a drag stops short of the next section", ctx ->
                        ctx.check("Idle stops at 59", section(ctx, "Idle").start() == 59, 59.0, section(ctx, "Idle").start()))
                .step("undo the clamped drag", ctx -> editor(ctx).historyView.undo())
                .frames(2)
                .step("the order is intact", ctx ->
                        ctx.check("Idle at 20", section(ctx, "Idle").start() == 20, 20.0, section(ctx, "Idle").start()))

                .click("the Idle band's menu", ctx -> band(ctx, "Idle"), RIGHT)
                .frames(3)
                .step("menu", ctx -> ctx.screenshot("menu"))
                .click("Copy Java call", ctx -> menuEntry(ctx, "Copy Java call"), LEFT)
                .frames(2)
                .step("copies the call that jumps there", ctx -> {
                    var clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
                    ctx.check("clipboard", "runtime.sections().jumpTo(\"Idle\");".equals(clipboard),
                            "runtime.sections().jumpTo(\"Idle\");", clipboard);
                })
                .click("the Vanish band's menu", ctx -> band(ctx, "Vanish"), RIGHT)
                .frames(3)
                .hover("Then", ctx -> menuEntry(ctx, "Then"))
                .frames(3)
                .click("→ Appear", ctx -> menuEntry(ctx, "→ Appear"), LEFT)
                .frames(2)
                .step("Then ▸ a section makes it jump there", ctx -> {
                    var vanish = section(ctx, "Vanish");
                    ctx.check("goto Appear", vanish.then() == TimelineSection.Then.GOTO && "Appear".equals(vanish.target()),
                            "GOTO Appear", vanish.then() + " " + vanish.target());
                })

                .step("zoom out over the strip", ctx -> {
                    ctx.put("scale", view(ctx).scale());
                    for (int i = 0; i < 6; i++) ctx.input().scroll(x(ctx, 40), y(ctx), -1);
                })
                .frames(3)
                .step("the wheel over the strip zooms, and the bands follow", ctx -> {
                    var scale = view(ctx).scale();
                    ctx.check("zoomed out", scale < ctx.<Float>get("scale"), "< " + ctx.get("scale"), scale);
                    var idle = band(ctx, "Idle");
                    ctx.check("Idle still starts at tick 20", Math.abs(idle.getPositionX() - x(ctx, 20)) <= 1, x(ctx, 20),
                            idle.getPositionX());
                    ctx.check("and spans 40 ticks", Math.abs(idle.getSizeWidth() - 40 * scale) <= 1, 40 * scale,
                            idle.getSizeWidth());
                })
                .step("double-click at 110, after the content", ctx -> doubleClick(ctx, x(ctx, 110)))
                .frames(3)
                .step("double-clicking empty space adds a section there", ctx -> {
                    var added = timeline(ctx).sections().stream().filter(s1 -> s1.start() == 110).findFirst().orElse(null);
                    ctx.check("one at 110", added != null, "added", "none");
                    ctx.check("selected", added != null && added == strip(ctx).selected(), "selected", strip(ctx).selected());
                })
                .step("undo the add", ctx -> editor(ctx).historyView.undo())
                .frames(2)
                .step("gone", ctx -> ctx.check("three sections", timeline(ctx).sections().size() == 3, 3,
                        timeline(ctx).sections().size()))
                .step("double-click the Vanish band", ctx -> doubleClick(ctx, center(band(ctx, "Vanish"))))
                .frames(3)
                .step("type a new name", ctx -> {
                    var dialogs = ctx.query().type(Dialog.class).list();
                    ctx.require("a rename dialog opens", !dialogs.isEmpty());
                    var field = dialogs.getLast().as(Dialog.class).selfAndAllChildren()
                            .filter(e -> e instanceof TextField).map(e -> (TextField) e)
                            .findFirst().orElse(null);
                    ctx.require("with the name in it", field != null && "Vanish".equals(field.getText()));
                    field.setText("Fade", true);
                })
                .click("confirm", ctx -> ctx.query().withClass("__confirm-button__").one().as(UIElement.class), LEFT)
                .frames(3)
                .step("renaming keeps what refers to the name", ctx -> {
                    var timeline = timeline(ctx);
                    ctx.check("renamed", timeline.sections().stream().anyMatch(s1 -> "Fade".equals(s1.name())), "Fade",
                            timeline.sections().stream().map(TimelineSection::name).toList());
                    ctx.check("still the release section", "Fade".equals(timeline.releaseSection()), "Fade",
                            timeline.releaseSection());
                })
                .click("the Fade band", ctx -> band(ctx, "Fade"), LEFT)
                .frames(2)
                .step("focus the timeline", ctx -> ctx.requireUI().requestFocus(view(ctx)))
                .key(GLFW.GLFW_KEY_DELETE)
                .frames(3)
                .step("Delete removes the selected section, and what referred to it", ctx -> {
                    var timeline = timeline(ctx);
                    ctx.check("two sections left", timeline.sections().size() == 2, 2, timeline.sections().size());
                    ctx.check("no release section", timeline.releaseSection() == null, null, timeline.releaseSection());
                })
                .step("undo the delete and the rename", ctx -> {
                    editor(ctx).historyView.undo();
                    editor(ctx).historyView.undo();
                })
                .frames(2)
                .step("all back", ctx -> {
                    var timeline = timeline(ctx);
                    ctx.check("Vanish again", timeline.sections().stream().anyMatch(s1 -> "Vanish".equals(s1.name())), "Vanish",
                            timeline.sections().stream().map(TimelineSection::name).toList());
                    ctx.check("the release section again", "Vanish".equals(timeline.releaseSection()), "Vanish",
                            timeline.releaseSection());
                })
                .step("reload, unchanged", ctx -> load(ctx, true))
                .frames(5)

                .step("rewind and play", ctx -> {
                    view(ctx).stop();
                    view(ctx).togglePlay();
                })
                .timeoutMs(20_000)
                .waitUntil("Idle has looped once", ctx -> runtime(ctx).sections().pass() >= 1)
                .frames(3)
                .step("while it loops the playhead stays on the timeline's time", ctx -> {
                    var time = pmTime(ctx);
                    ctx.check("in Idle", "Idle".equals(runtime(ctx).sections().current()), "Idle", runtime(ctx).sections().current());
                    ctx.check("the playhead inside Idle", time >= 20 && time < 60, "[20, 60)", time);
                    var local = runtime(ctx).timelinePlayer.localTime();
                    ctx.check("and on the timeline's clock", Math.abs(local - time) <= 1, local, time);
                    ctx.check("its band shows it", band(ctx, "Idle").hasClass("__current__"), "current", "not");
                })
                .step("timeline", ctx -> ctx.screenshotElement("preview_loop", ctx.query().type(FXTimelineView.class).one()))
                .click("Advance", ctx -> byId(ctx, "timeline.advance"), LEFT)
                .timeoutMs(20_000)
                .waitUntil("Vanish plays", ctx -> "Vanish".equals(runtime(ctx).sections().current()))
                .step("Advance moved on at the end of the pass", ctx ->
                        ctx.check("the playhead in Vanish", pmTime(ctx) >= 60, ">= 60", pmTime(ctx)))
                .timeoutMs(20_000)
                .waitUntil("it ends and the preview starts over", ctx -> pmTime(ctx) < 15
                        && "Appear".equals(runtime(ctx).sections().current()))
                .timeoutMs(20_000)
                .waitUntil("Idle again", ctx -> "Idle".equals(runtime(ctx).sections().current()) && pmTime(ctx) >= 25)
                .click("Release", ctx -> byId(ctx, "timeline.release"), LEFT)
                .ticks(3)
                .step("Release plays the release section at once", ctx -> {
                    ctx.check("releasing", runtime(ctx).isReleasing(), "releasing", "not");
                    ctx.check("in Vanish", "Vanish".equals(runtime(ctx).sections().current()), "Vanish",
                            runtime(ctx).sections().current());
                    ctx.check("the playhead too", pmTime(ctx) >= 60, ">= 60", pmTime(ctx));
                })
                .timeoutMs(20_000)
                .waitUntil("the preview starts over", ctx -> pmTime(ctx) < 15 && !runtime(ctx).isReleasing())

                .step("a seek to 70 while playing replays in time order: the forever Idle once", ctx -> {
                    editor(ctx).sceneView.simulateTo(70);
                    ctx.check("in Vanish", "Vanish".equals(runtime(ctx).sections().current()), "Vanish",
                            runtime(ctx).sections().current());
                    // the root starts ticking one engine tick after the emit: the clock trails the playhead by one
                    var local = runtime(ctx).timelinePlayer.localTime();
                    ctx.check("the clock at 70", local >= 69 && local <= 70, "69..70", local);
                })
                .click("the follow toggle, off", ctx -> byId(ctx, "timeline.followSections"), LEFT)
                .step("rewind and play", ctx -> {
                    view(ctx).stop();
                    view(ctx).togglePlay();
                })
                .timeoutMs(20_000)
                .waitUntil("the playhead passes 62", ctx -> pmTime(ctx) >= 62)
                .step("not following, the forever section played once", ctx -> {
                    ctx.check("follow off", !view(ctx).isFollowingSections(), "off", "on");
                    ctx.check("in Vanish", "Vanish".equals(runtime(ctx).sections().current()), "Vanish",
                            runtime(ctx).sections().current());
                })
                .click("the follow toggle, on", ctx -> byId(ctx, "timeline.followSections"), LEFT)

                .step("Appear ends the timeline; stop at the timeline's end", ctx -> {
                    section(ctx, "Appear").then(TimelineSection.Then.END);
                    view(ctx).stop();
                })
                .click("the end-mode toggle", ctx -> byId(ctx, "timeline.endMode"), LEFT)
                .step("play, watching the playhead", ctx -> {
                    ctx.put("max", 0L);
                    ctx.put("restarts", 0);
                    ctx.put("last", pmTime(ctx));
                    view(ctx).togglePlay();
                })
                .timeoutMs(20_000)
                .waitUntil("the preview starts over twice", ctx -> {
                    var time = pmTime(ctx);
                    if (time > ctx.<Long>get("max")) ctx.put("max", time);
                    if (time < ctx.<Long>get("last")) ctx.put("restarts", ctx.<Integer>get("restarts") + 1);
                    ctx.put("last", time);
                    return ctx.<Integer>get("restarts") >= 2;
                })
                .step("an \"end\" section ends the timeline there", ctx ->
                        ctx.check("never past Appear", ctx.<Long>get("max") <= 25, "<= 25", ctx.get("max")))
                .click("the end-mode toggle, back", ctx -> byId(ctx, "timeline.endMode"), LEFT)

                .step("load the same without sections", ctx -> load(ctx, false))
                .frames(5)
                .step("pause at 5", ctx -> seek(ctx, 5))
                .frames(3)
                .step("no sections, no row", ctx -> {
                    ctx.check("the strip hides", !strip(ctx).strip.isDisplayed(), "hidden", "shown");
                    ctx.check("so do the section buttons", !byId(ctx, "timeline.sectionControls").isDisplayed(), "hidden", "shown");
                })
                .click("the ruler's menu", ctx -> byId(ctx, "timeline.ruler"), RIGHT)
                .frames(3)
                .click("Quick create", ctx -> menuEntry(ctx, "Quick create: start / loop / end"), LEFT)
                .frames(3)
                .step("quick create makes start / loop / end, the end the release", ctx -> {
                    var timeline = timeline(ctx);
                    var sections = timeline.sections();
                    ctx.check("three", sections.size() == 3, 3, sections.size());
                    ctx.check("Start, Loop, End at 0, 30, 60",
                            sections.size() == 3 && sections.get(0).start() == 0 && sections.get(1).start() == 30
                                    && sections.get(2).start() == 60,
                            "0, 30, 60", sections.stream().map(TimelineSection::start).toList());
                    ctx.check("the loop forever", sections.size() == 3 && sections.get(1).repeat() == TimelineSection.FOREVER,
                            "forever", sections.size() == 3 ? sections.get(1).repeat() : null);
                    ctx.check("End released at once", "End".equals(timeline.releaseSection()) && timeline.releaseNow(),
                            "End, at once", timeline.releaseSection() + ", " + timeline.releaseNow());
                    ctx.check("the strip shows", strip(ctx).strip.isDisplayed(), "shown", "hidden");
                })
                .frames(3)
                .step("timeline", ctx -> ctx.screenshotElement("quick_create", ctx.query().type(FXTimelineView.class).one()))
                .step("undo", ctx -> editor(ctx).historyView.undo())
                .frames(2)
                .step("undone", ctx -> ctx.check("no sections", timeline(ctx).sections().isEmpty(), 0,
                        timeline(ctx).sections().size()));

        s.teardown("close the project", ctx -> {
            FXEditor editor = ctx.get("editor");
            if (editor != null) {
                editor.sceneView.particleManager.pause();
                editor.closeCurrentProject(false, null);
            }
        });
    }

    // ---- fixture -----------------------------------------------------------------------------------

    private static void load(TestContext ctx, boolean sections) {
        var appear = emitter("appear");
        var idle = emitter("idle");
        var vanish = emitter("vanish");
        var control = PhotonTrackTypes.CONTROL.create();
        control.displayName("States");
        control.clips().add(new Clip(0, 20, 1).targetId(appear.id()));
        control.clips().add(new Clip(20, 40, 1).targetId(idle.id()).exitMode(Clip.ExitMode.DRAIN));
        control.clips().add(new Clip(60, 30, 1).targetId(vanish.id()));
        var project = new FXProject();
        var data = project.getFx().getFxData();
        data.objects().addAll(List.of(appear, idle, vanish));
        data.timeline().tracks().add(control);
        if (sections) {
            data.timeline().sections().add(new TimelineSection("Appear", 0));
            data.timeline().sections().add(new TimelineSection("Idle", 20).repeat(TimelineSection.FOREVER));
            data.timeline().sections().add(new TimelineSection("Vanish", 60));
            data.timeline().releaseSection("Vanish").releaseNow(true);
        }
        var editor = editor(ctx);
        editor.sceneView.particleManager.pause();
        editor.closeCurrentProject(false, null);
        editor.loadProject(project, null);
        ctx.put("editor", editor);
    }

    private static ParticleEmitter emitter(String name) {
        var emitter = new ParticleEmitter();
        emitter.setName(name);
        emitter.config.setLooping(true);
        emitter.config.setDuration(40);
        emitter.config.setStartLifetime(NumberFunction.constant(20));
        emitter.config.emission.setEmissionRate(NumberFunction.constant(1));
        return emitter;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static FXTimelineView view(TestContext ctx) {
        return editor(ctx).timelineView;
    }

    private static SectionStrip strip(TestContext ctx) {
        return view(ctx).sectionStrip();
    }

    private static FXRuntime runtime(TestContext ctx) {
        var runtime = editor(ctx).runtime;
        ctx.require("a project is open", runtime != null);
        return runtime;
    }

    private static Timeline timeline(TestContext ctx) {
        return runtime(ctx).fxData.timeline();
    }

    private static TimelineSection section(TestContext ctx, String name) {
        var section = timeline(ctx).sections().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        ctx.require("a section " + name, section != null);
        return section;
    }

    private static UIElement band(TestContext ctx, String name) {
        var band = strip(ctx).band(name);
        ctx.require("a band for " + name, band != null);
        return band;
    }

    /** A band's left edge, as a press target: its first child is the edge line. */
    private static UIElement edge(TestContext ctx, String name) {
        return band(ctx, name).getChildren().getFirst();
    }

    /** Where to drop a band's edge for it to land on {@code tick}, allowing for the grab offset and the drop jitter. */
    private static float dropX(TestContext ctx, String name, double tick) {
        var edge = edge(ctx, name);
        var grab = edge.getPositionX() + edge.getSizeWidth() / 2 - band(ctx, name).getPositionX();
        return x(ctx, tick) + grab - 0.5f;
    }

    /**
     * A menu entry by its label. Not {@code withText}: an entry's label is two levels down and a menu's contents are
     * internal UI, which {@code withText} leaves out.
     */
    private static UIElement menuEntry(TestContext ctx, String label) {
        var found = ctx.query().where(e -> (e.hasClass("__menu_leaf-node__") || e.hasClass("__menu_branch-node__"))
                && e.selfAndAllChildren().anyMatch(c -> c instanceof TextElement text && text.getText().getString().equals(label)))
                .list();
        ctx.require("a menu entry \"" + label + "\"", !found.isEmpty());
        return found.getLast().as(UIElement.class);
    }

    private static UIElement byId(TestContext ctx, String id) {
        return ctx.query().withId(id).one().as(UIElement.class);
    }

    private static float x(TestContext ctx, double tick) {
        return view(ctx).originX() + view(ctx).tickToLocalX(tick);
    }

    private static float y(TestContext ctx) {
        var strip = strip(ctx).strip;
        return strip.getPositionY() + strip.getSizeHeight() / 2;
    }

    private static float center(UIElement element) {
        return element.getPositionX() + element.getSizeWidth() / 2;
    }

    private static void doubleClick(TestContext ctx, float x) {
        var y = y(ctx);
        ctx.input().mouseDown(x, y, LEFT);
        ctx.input().mouseUp(x, y, LEFT);
        ctx.input().mouseDown(x, y, LEFT);
        ctx.input().mouseUp(x, y, LEFT);
    }

    private static void seek(TestContext ctx, long tick) {
        editor(ctx).sceneView.particleManager.pause();
        editor(ctx).sceneView.simulateTo(tick);
    }

    private static long pmTime(TestContext ctx) {
        return editor(ctx).sceneView.particleManager.getTime();
    }
}
