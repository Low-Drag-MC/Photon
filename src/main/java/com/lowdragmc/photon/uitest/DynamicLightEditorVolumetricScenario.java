package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.light.LightConfig;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import org.joml.Vector3f;

/**
 * A shadowed volumetric light in an editor scene only adds haze. Its visibility map leaves GL's scissor set, which
 * the light pass's frame copy used to obey: the scene went black but for a strip.
 */
@LDLRegisterClient(name = "dynamic_light_editor_volumetric", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightEditorVolumetricScenario implements UIScenario {
    private static ScreenshotCompare.Region SCENE;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("volumetric defaults", ctx -> LightTestConfig.volumetricDefaults())
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false).shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("a shadowed volumetric point light", DynamicLightEditorVolumetricScenario::load)
                .frames(30)
                .step("measure the scene view", ctx -> SCENE = ScreenshotCompare.sceneViewport(ctx))
                .screenshot("volumetric")
                .step("volumetric off", ctx -> ctx.<LightObject>get("light").config.getVolumetric().setEnable(false))
                .frames(20)
                .screenshot("plain")
                .step("the haze shows and only brightens the scene", ctx -> {
                    var volumetric = ScreenshotCompare.load(ctx, "volumetric");
                    var plain = ScreenshotCompare.load(ctx, "plain");
                    ctx.require("both captures exist", volumetric != null && plain != null);
                    var haze = volumetric.diff(plain, 6, SCENE);
                    ctx.check("the haze shows", haze.fraction() > 0.01, "> 1%", haze.count() + " " + haze.box());
                    int darker = volumetric.darkerThan(plain, 24, SCENE);
                    long limit = SCENE.area() / 1000;
                    ctx.check("nothing went dark", darker < limit, "< " + limit + " px", darker);
                })
                .teardown("close the editor", ctx -> {
                    FXEditor editor = ctx.get("editor");
                    if (editor != null) editor.closeCurrentProject(false, null);
                    ctx.mc().gui.setScreen(null);
                    LightTestConfig.restore();
                });
    }

    private static void load(TestContext ctx) {
        var light = new LightObject();
        light.setName("Point");
        light.config.setType(LightConfig.Type.Point);
        light.config.setLooping(true);
        light.config.setIntensity(NumberFunction.constant(20));
        light.config.setRange(NumberFunction.constant(8));
        light.config.setCastShadows(true);
        light.config.getVolumetric().setEnable(true);
        light.transform().localPosition(new Vector3f(0, 2.5f, 0));
        var project = new FXProject();
        project.getFx().getFxData().objects().add(light);
        var editor = ctx.query().type(FXEditor.class).one().as(FXEditor.class);
        editor.loadProject(project, null);
        ctx.put("editor", editor);
        ctx.put("light", light);
        var renderer = editor.sceneView.sceneEditor.scene.<WorldSceneRenderer>getRenderer();
        if (renderer != null) renderer.setCameraLookAt(new Vector3f(0.5f, 1f, 0.5f), 9, 1.0, 0.6);
    }
}
