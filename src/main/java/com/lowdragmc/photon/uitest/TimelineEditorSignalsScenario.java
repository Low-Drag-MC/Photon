package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.timeline.PhotonSignals;
import com.lowdragmc.photon.client.fx.timeline.Signal;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The editor fires a signal only when live playback passes it: a seek replays silently whether the preview plays or
 * is paused, and resuming after a seek does not fire what the seek passed.
 */
@LDLRegisterClient(name = "timeline_editor_signals", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TimelineEditorSignalsScenario implements UIScenario {

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("load signals at 5, 15, 25 and 80, and listen to them", TimelineEditorSignalsScenario::load)
                // the default layout docks the timeline behind Resources (1.21 passed on a developer's saved layout, which
                // the emptied runs/uiTest never has): its tab has to be in front for it to lay out and tick
                .step("bring the timeline to the front", ctx -> {
                    var timeline = editor(ctx).timelineView;
                    var container = timeline.getViewContainer();
                    if (container != null) container.selectView(timeline);
                    ctx.check("the timeline is in front", container != null && container.isViewSelected(timeline), "front", "behind");
                })
                .frames(5)
                .step("rewind and play", ctx -> {
                    editor(ctx).timelineView.stop();
                    // loading auto-plays, so a slow frame may have passed 5 live already: count from the rewind
                    fired(ctx).clear();
                    editor(ctx).timelineView.togglePlay();
                })
                .timeoutMs(20_000)
                .waitUntil("the playhead passes 30", ctx -> time(ctx) >= 30)
                .step("live playback fired each signal it passed once", ctx -> ctx.check("5, 15 and 25 once each",
                        fired(ctx, "e5") == 1 && fired(ctx, "e15") == 1 && fired(ctx, "e25") == 1, "{e5=1, e15=1, e25=1}",
                        fired(ctx)))
                .step("seek back to 10 while playing", ctx -> editor(ctx).sceneView.simulateTo(10))
                .step("the replay fired nothing", ctx ->
                        ctx.check("5 still once", fired(ctx, "e5") == 1, 1, fired(ctx, "e5")))
                .waitUntil("the playhead passes 20 again", ctx -> time(ctx) >= 20)
                .step("playing on fires what it passes again", ctx -> {
                    ctx.check("5 not again", fired(ctx, "e5") == 1, 1, fired(ctx, "e5"));
                    ctx.check("15 again", fired(ctx, "e15") == 2, 2, fired(ctx, "e15"));
                })
                .step("pause and seek back to 2", ctx -> {
                    editor(ctx).sceneView.particleManager.pause();
                    editor(ctx).sceneView.simulateTo(2);
                })
                .frames(3)
                .step("then forward to 22, still paused", ctx -> editor(ctx).sceneView.simulateTo(22))
                .frames(3)
                .step("play again", ctx -> editor(ctx).timelineView.togglePlay())
                .waitUntil("the playhead passes 24", ctx -> time(ctx) >= 24)
                .step("what the seeks passed did not fire on resume", ctx -> {
                    ctx.check("5 not again", fired(ctx, "e5") == 1, 1, fired(ctx, "e5"));
                    ctx.check("15 not again", fired(ctx, "e15") == 2, 2, fired(ctx, "e15"));
                })
                .waitUntil("the playhead passes 26", ctx -> time(ctx) >= 26)
                .step("and the next one fires", ctx ->
                        ctx.check("25 a second time", fired(ctx, "e25") == 2, 2, fired(ctx, "e25")));

        s.teardown("close the project", ctx -> {
            PhotonSignals.Listener listener = ctx.get("listener");
            if (listener != null) PhotonSignals.unregister(listener);
            FXEditor editor = ctx.get("editor");
            if (editor != null) editor.closeCurrentProject(false, null);
        });
    }

    private static void load(TestContext ctx) {
        var signals = (SignalTrack) PhotonTrackTypes.SIGNAL.create();
        signals.displayName("editor");
        for (var time : new int[]{5, 15, 25, 80}) {
            signals.signals().add(new Signal(time, "e" + time));
        }
        var project = new FXProject();
        project.getFx().getFxData().timeline().tracks().add(signals);
        var editor = editor(ctx);
        editor.loadProject(project, null);
        ctx.put("editor", editor);

        var fired = new ConcurrentHashMap<String, Integer>();
        ctx.put("fired", fired);
        PhotonSignals.Listener listener = (effect, channel, name, data, time) -> {
            if (effect == editor.sceneView.effect && "editor".equals(channel)) fired.merge(name, 1, Integer::sum);
        };
        PhotonSignals.register(listener);
        ctx.put("listener", listener);
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static long time(TestContext ctx) {
        return editor(ctx).sceneView.particleManager.getTime();
    }

    private static Map<String, Integer> fired(TestContext ctx) {
        return ctx.get("fired");
    }

    private static int fired(TestContext ctx, String name) {
        return fired(ctx).getOrDefault(name, 0);
    }
}
