package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import org.joml.Vector3f;

import static com.lowdragmc.photon.uitest.WikiEditor.*;

/** Wiki video of a particle emitter's Lifetime Mode: Once, Loop and PingPong side by side. For a 3840x2160 window. */
@LDLRegisterClient(name = "wiki_photon_lifetime_modes", group = "photon_wiki", registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WikiLifetimeModesRecording implements UIRecording {

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("wiki", "photon").guiScale(4).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiEditor.open(s);
        show(s, "open the three balls", ctx -> load(ctx, WikiDemos.lifetimeModes(), false, new Vector3f(0, 2, 0), 4.2, 0.25, 0), 175)
                .step("show the emitters in the hierarchy", ctx -> {
                    var hierarchy = editor(ctx).hierarchyView;
                    var root = hierarchy.getRootNode();
                    if (root != null) hierarchy.treeList.expandNode(root);
                })
                .frames(10);
        holdAtTheEnd(s)
                .take("lifetime-modes", o -> o.size(1920, 1080).fps(30).poster(5_500), t -> {
                    say(t, "Lifetime Mode, in a particle emitter's settings, sets how each particle's age runs.",
                            "粒子发射器设置里的 Lifetime Mode 决定每个粒子的年龄怎么推进。");
                    t.click("the Loop emitter", ctx -> hierarchyRow(ctx, "Loop"), LEFT)
                            .hold(700)
                            .zoomTo("Lifetime Mode", WikiLifetimeModesRecording::lifetimeModeRow, 700)
                            .hold(2800)
                            .zoomOut(600)
                            // the selected emitter's shape is drawn over its particles
                            .click("the root, to put the emitter's shape away", ctx -> hierarchyRow(ctx, "root"), LEFT);
                    say(t, "Blue is a particle's birth, red the end of its life. Once dies at the end, Loop starts over, PingPong runs back and forth.",
                            "蓝色是粒子刚出生，红色是寿命终点。Once 到头就消失，Loop 从头再来，PingPong 来回往返。");
                    t.click("Play", ctx -> byId(ctx, "timeline.play"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("two cycles", ctx -> time(ctx) >= 90);
                    say(t, "A looping particle lives on until its emitter is stopped, then finishes the cycle it is in. Here the clips end at 6 s.",
                            "循环的粒子会一直活着，直到发射器被停止，再播完手上这一轮。这里的片段在 6 秒处结束。");
                    t.timeoutMs(30_000)
                            .waitUntil("all of them ended", ctx -> !view(ctx).isPlaying())
                            .hold(900);
                    say(t, "", "");
                    t.hold(400);
                });
        WikiEditor.close(s);
    }

    /** The inspector's Lifetime Mode row, with a little of what is around it. */
    private static float[] lifetimeModeRow(TestContext ctx) {
        var inspector = editor(ctx).inspectorView;
        var label = inspector.selfAndAllChildren()
                .filter(c -> c instanceof TextElement text && text.getText().getString().equals("Lifetime Mode"))
                .findFirst().orElse(null);
        ctx.require("the inspector shows Lifetime Mode", label != null);
        return new float[]{inspector.getPositionX(), label.getPositionY() - 30, inspector.getSizeWidth(), 76};
    }
}
