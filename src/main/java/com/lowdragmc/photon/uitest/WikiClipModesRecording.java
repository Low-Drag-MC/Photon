package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import org.joml.Vector3f;

import static com.lowdragmc.photon.uitest.WikiEditor.*;

/** Wiki videos of a control clip's On Exit and On Section Loop settings. For a 3840x2160 window. */
@LDLRegisterClient(name = "wiki_photon_clip_modes", group = "photon_wiki", registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WikiClipModesRecording implements UIRecording {
    private static final Vector3f LOOK = new Vector3f(0, 2.6f, 0);
    private static final double RADIUS = 5.4;
    private static final double PITCH = 0.3;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("wiki", "photon").guiScale(4).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiEditor.open(s);
        show(s, "open the two jets", ctx -> load(ctx, WikiDemos.exitModes(), false, LOOK, RADIUS, PITCH, 12), 70);
        holdAtTheEnd(s)
                .take("clip-exit-mode", o -> o.size(1920, 1080).fps(30).poster(9_000), t -> {
                    say(t, "A control clip's On Exit decides what leaving the clip does to its object.",
                            "控制片段的 On Exit 决定离开片段时怎么处理它绑定的对象。");
                    t.click("the Drain clip", ctx -> clip(ctx, "Drain"), LEFT)
                            .hold(700)
                            .zoomTo("the clip's settings", ctx -> inspectorRows(ctx, 130), 700)
                            .hold(2800)
                            .zoomOut(600);
                    say(t, "Cut, on the left, hides it at once. Drain, on the right, stops emitting and lets its particles live out their lives.",
                            "左边是 Cut：立即隐藏。右边是 Drain：停止发射，已有的粒子自然消散。");
                    t.click("Play", ctx -> byId(ctx, "timeline.play"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("the Drain jet has emptied", ctx -> !view(ctx).isPlaying())
                            .hold(900);
                    say(t, "", "");
                    t.hold(400);
                });
        show(s, "open Auto and Restart", ctx -> load(ctx, WikiDemos.seamModes(), true, LOOK, RADIUS, PITCH, 0), 66)
                .take("clip-seam-mode", o -> o.size(1920, 1080).fps(30).poster(9_000), t -> {
                    say(t, "With sections, On Section Loop decides what a clip that stays on across a loop does.",
                            "有区段时，On Section Loop 决定跨过循环接缝、一直开着的片段怎么处理。");
                    t.click("the Restart clip", ctx -> clip(ctx, "Restart"), LEFT)
                            .hold(700)
                            .zoomTo("the clip's settings", ctx -> inspectorRows(ctx, 130), 700)
                            .hold(2800)
                            .zoomOut(600);
                    say(t, "Auto, on the left, keeps a running object going and restarts it only once it has finished. Restart, on the right, starts it over on every pass.",
                            "左边是 Auto：对象还在播就继续，播完了才重启。右边是 Restart：每一遍都从头开始。");
                    t.click("Play", ctx -> byId(ctx, "timeline.play"), LEFT)
                            .timeoutMs(30_000)
                            .waitUntil("Loop has looped three times", ctx -> runtime(ctx).sections().pass() >= 3)
                            .hold(800)
                            .click("Pause", ctx -> byId(ctx, "timeline.play"), LEFT);
                    say(t, "", "");
                    t.hold(400);
                });
        WikiEditor.close(s);
    }
}
