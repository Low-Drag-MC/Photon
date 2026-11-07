package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import org.joml.Vector3f;

import static com.lowdragmc.photon.uitest.WikiEditor.*;

/** Wiki videos of timeline sections in the editor: making them, their settings, and the preview. For a 3840x2160 window. */
@LDLRegisterClient(name = "wiki_photon_sections_editor", group = "photon_wiki", registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WikiSectionsEditorRecording implements UIRecording {
    private static final Vector3f LOOK = new Vector3f(0, 1.3f, 0);
    private static final double RADIUS = 4.6;
    private static final double PITCH = 0.75;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("wiki", "photon").guiScale(4).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiEditor.open(s);
        create(s);
        settings(s);
        preview(s);
        WikiEditor.close(s);
    }

    private static void circle(TestContext ctx, boolean withSections, long tick) {
        load(ctx, WikiDemos.magicCircle(), withSections, LOOK, RADIUS, PITCH, tick);
    }

    private static void create(ScenarioBuilder s) {
        show(s, "open the magic circle without sections", ctx -> circle(ctx, false, 30), 96)
                .take("sections-create", o -> o.size(1920, 1080).fps(30).poster(12_000), t -> {
                    say(t, "A timeline without sections plays once, from start to end.",
                            "没有区段的时间轴从头到尾只播一遍。");
                    t.hold(2500);
                    say(t, "Right-click the ruler and pick Quick create: start / loop / end.",
                            "在标尺上右键，选择 Quick create: start / loop / end。");
                    t.clickAt("the ruler at tick 45", ctx -> new float[]{x(ctx, 45), rulerY(ctx)}, RIGHT)
                            .waitUntil("the ruler menu is open", WikiEditor::menuOpen)
                            .click("Quick create", ctx -> menuEntry(ctx, "Quick create: start / loop / end"), LEFT)
                            .waitUntil("three sections",
                                    ctx -> runtime(ctx).fxData.timeline().sections().size() == 3)
                            .zoomTo("the sections", ctx -> timelineRect(ctx, -4, 94, 70), 700);
                    say(t, "Loop repeats until something moves the effect on. End is the release section: stopping the effect plays it.",
                            "Loop 会一直循环，直到被推进；End 是 release 区段，停止特效时会播放它。");
                    t.hold(4500);
                    say(t, "Drag a band's left edge to move where its section starts. It snaps to clip edges.",
                            "拖动色带的左边界，调整区段从哪里开始；它会吸附到片段边缘。");
                    t.drag("Loop's left edge", ctx -> edge(ctx, "Loop"), ctx -> edgeDrop(ctx, "Loop", 20))
                            .hold(1200)
                            .check("Loop starts at 20", ctx -> section(ctx, "Loop").start() == 20)
                            .zoomOut(700);
                    say(t, "Double-click a band to rename it.", "双击色带可以重命名。");
                    t.step("tag the Loop band", ctx -> band(ctx, "Loop").setId("wiki_loop_band"))
                            .doubleClick("#wiki_loop_band")
                            .waitUntil("the rename dialog", ctx -> !ctx.query().type(Dialog.class).list().isEmpty())
                            .zoomTo("the rename dialog", WikiEditor::dialogRect, 500)
                            .step("tag the name field", ctx -> dialogField(ctx).setId("wiki_name_field"))
                            .typeInto("#wiki_name_field", "Idle")
                            .hold(400)
                            .click("confirm", WikiEditor::confirmButton, LEFT)
                            .waitUntil("renamed", ctx -> runtime(ctx).fxData.timeline().sections().stream()
                                    .anyMatch(section -> "Idle".equals(section.name())))
                            .zoomOut(500)
                            .hold(1800);
                    say(t, "", "");
                    t.hold(600);
                });
    }

    private static void settings(ScenarioBuilder s) {
        show(s, "open the magic circle with its sections", ctx -> circle(ctx, true, 30), 96)
                .take("sections-settings", o -> o.size(1920, 1080).fps(30).poster(4_000), t -> {
                    say(t, "Click a band to edit its section in the Inspector.", "点击色带，在 Inspector 里编辑这个区段。");
                    t.click("the Idle band", ctx -> band(ctx, "Idle"), LEFT)
                            .hold(700)
                            .zoomTo("the section's settings", ctx -> inspectorRows(ctx, 92), 700);
                    say(t, "Repeat: once, a number of times, or forever until something moves it on.",
                            "Repeat：一次、指定次数，或者一直循环，直到被推进。");
                    t.hold(3500);
                    say(t, "Then: where it goes after its passes. Carry on, end the effect, or jump to another section.",
                            "Then：播完之后去哪里——继续往后、结束整个特效，或者跳到另一个区段。");
                    t.hold(3500).zoomOut(600);
                    say(t, "The amber edge marks the release section.", "左边是琥珀色粗线的，就是 release 区段。");
                    t.click("the Vanish band", ctx -> band(ctx, "Vanish"), LEFT)
                            .hold(700)
                            .zoomTo("the section's settings", ctx -> inspectorRows(ctx, 92), 700);
                    say(t, "Stopping the effect plays it first: at once, or after the current pass.",
                            "停止特效时会先播放它：可以立即进入，也可以等当前这一遍播完。");
                    t.hold(4000).zoomOut(600);
                    say(t, "A band's right-click menu has the same settings, plus copy command, rename and remove.",
                            "色带的右键菜单里也有这些设置，另外还能复制命令、重命名和删除。");
                    t.click("the Idle band's menu", ctx -> band(ctx, "Idle"), RIGHT)
                            .waitUntil("its menu is open", WikiEditor::menuOpen)
                            .zoomTo("the menu", WikiEditor::menuRect, 600)
                            .hover("Repeat", ctx -> menuEntry(ctx, "Repeat"))
                            .hold(2400)
                            .key(Keys.ESCAPE)
                            .zoomOut(600)
                            .hold(600);
                    say(t, "", "");
                    t.hold(400);
                });
    }

    private static void preview(ScenarioBuilder s) {
        show(s, "open the magic circle at its start", ctx -> circle(ctx, true, 0), 96)
                .take("sections-preview", o -> o.size(1920, 1080).fps(30).poster(7_000), t -> {
                    say(t, "Press Play. The preview loops the forever section and counts its passes.",
                            "点击播放：预览会循环这个一直循环的区段，并显示当前是第几遍。");
                    t.click("Play", ctx -> byId(ctx, "timeline.play"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("Idle looped once", ctx -> runtime(ctx).sections().pass() >= 1)
                            .zoomTo("the sections and the playhead", ctx -> timelineRect(ctx, -4, 100, 40), 600)
                            .waitUntil("Idle looped twice", ctx -> runtime(ctx).sections().pass() >= 2)
                            .hold(1400)
                            .zoomOut(600);
                    say(t, "Advance finishes this pass, then moves on.", "Advance：播完这一遍，然后往后走。");
                    t.click("Advance", ctx -> byId(ctx, "timeline.advance"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("Vanish plays", ctx -> isIn(ctx, "Vanish"))
                            .hold(1800)
                            .timeoutMs(30_000)
                            .waitUntil("the preview starts over", ctx -> isIn(ctx, "Idle"))
                            .hold(1200);
                    say(t, "Release stops the effect the way the game does: the release section plays, then everything winds down.",
                            "Release：像游戏里停止特效那样——先播 release 区段，再整体收尾。");
                    t.click("Release", ctx -> byId(ctx, "timeline.release"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("Vanish plays", ctx -> isIn(ctx, "Vanish"))
                            .hold(2200)
                            .timeoutMs(30_000)
                            .waitUntil("the preview starts over", ctx -> isIn(ctx, "Idle"));
                    say(t, "Turn Follow off to play every section once, in time order.",
                            "关闭「跟随」后，每个区段按时间顺序各播一遍。");
                    t.click("Follow", ctx -> byId(ctx, "timeline.followSections"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("it runs on into Vanish", ctx -> time(ctx) >= 66)
                            .hold(1200)
                            .click("Follow", ctx -> byId(ctx, "timeline.followSections"), LEFT)
                            .click("Pause", ctx -> byId(ctx, "timeline.play"), LEFT);
                    say(t, "", "");
                    t.hold(600);
                });
    }
}
