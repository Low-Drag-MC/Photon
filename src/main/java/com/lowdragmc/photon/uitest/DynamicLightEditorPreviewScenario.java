package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.light.LightConfig;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Light objects lighting the FX editor's own scene, following the timeline: a lamp that lives 40 ticks and a
 * looping spot. Seeking to 10, past the lamp's end, and back to 10 must show the lamp, lose it, and get it
 * back; the scene's light toggle must take both away.
 */
@LDLRegisterClient(name = "dynamic_light_editor_preview", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightEditorPreviewScenario implements UIScenario {
    /** The scene view at 1920x1080, clear of the stats box (bottom right) and the animation tab (bottom left). */
    private static final ScreenshotCompare.Region SCENE = new ScreenshotCompare.Region(450, 130, 1200, 750);

    @Override
    public void define(ScenarioBuilder s) {
        s.step("dynamic lights on", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true))
        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
        .step("load a lamp and a spot light", DynamicLightEditorPreviewScenario::loadProject)
        .frames(20)
        .step("pause and seek to tick 10", ctx -> seek(ctx, 10))
        .frames(10)
        .screenshot("preview_t10")
        .step("turn the scene's lights off", ctx -> editor(ctx).sceneView.setDynamicLightsEnabled(false))
        .frames(10)
        .screenshot("preview_off")
        .step("lights back on, seek past the lamp's lifetime", ctx -> {
            editor(ctx).sceneView.setDynamicLightsEnabled(true);
            seek(ctx, 60);
        })
        .frames(10)
        .screenshot("preview_t60")
        .step("seek back to tick 10", ctx -> seek(ctx, 10))
        .frames(10)
        .screenshot("preview_t10_again")
        .step("compare the captures", DynamicLightEditorPreviewScenario::compare);

        s.teardown("close the project", ctx -> {
            FXEditor editor = ctx.get("editor");
            if (editor != null) {
                editor.sceneView.setDynamicLightsEnabled(true);
                editor.closeCurrentProject(false, null);
            }
            LightTestConfig.restore();
        });
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static void seek(TestContext ctx, long tick) {
        var scene = editor(ctx).sceneView;
        scene.particleManager.pause();
        scene.simulateTo(tick);
    }

    private static void loadProject(TestContext ctx) {
        var lamp = new LightObject();
        lamp.setName("Lamp");
        lamp.config.setLifetime(40);
        lamp.config.setColor(new HDRConstantColor(new HDRColor(1f, 0.4f, 0.15f, 1f, 1f)));
        lamp.config.setIntensity(NumberFunction.constant(14));
        lamp.config.setRange(NumberFunction.constant(9));
        lamp.config.setSourceRadius(0.2f);
        lamp.transform().localPosition(new Vector3f(-1.5f, 1.2f, 0));

        var spot = new LightObject();
        spot.setName("Spot");
        spot.config.setType(LightConfig.Type.Spot);
        spot.config.setLooping(true);
        spot.config.setColor(new HDRConstantColor(new HDRColor(0.25f, 0.5f, 1f, 1f, 1f)));
        spot.config.setIntensity(NumberFunction.constant(40));
        spot.config.setRange(NumberFunction.constant(10));
        spot.config.setOuterAngle(30);
        spot.transform().localPosition(new Vector3f(2.5f, 3f, 0));
        spot.transform().localRotation(new Quaternionf().rotationZ(-0.4f));

        var project = new FXProject();
        project.getFx().getFxData().objects().add(lamp);
        project.getFx().getFxData().objects().add(spot);
        var editor = editor(ctx);
        editor.loadProject(project, null);
        ctx.put("editor", editor);
        var renderer = editor.sceneView.sceneEditor.scene.getRenderer();
        if (renderer != null) renderer.setCameraLookAt(new Vector3f(0.5f, 1f, 0.5f), 11, 1.2, 0.75);
    }

    private static void compare(TestContext ctx) {
        var lit = ScreenshotCompare.load(ctx, "preview_t10");
        var off = ScreenshotCompare.load(ctx, "preview_off");
        var expired = ScreenshotCompare.load(ctx, "preview_t60");
        var again = ScreenshotCompare.load(ctx, "preview_t10_again");
        if (lit == null || off == null || expired == null || again == null) {
            ctx.check("all four captures were written", false, "four images", "missing some");
            return;
        }
        var lights = lit.diff(off, 12, SCENE);
        ctx.check("the lights light the editor scene", lights.count() > 20_000, "> 20000 px", lights.count() + " " + lights.box());
        var lamp = expired.diff(lit, 12, SCENE);
        ctx.check("the lamp is gone past its lifetime", lamp.count() > 5_000, "> 5000 px", lamp.count() + " " + lamp.box());
        var back = again.diff(lit, 12, SCENE);
        ctx.check("seeking back brings the same light back", back.count() < 500, "< 500 px", back.count() + " " + back.box());
    }
}
