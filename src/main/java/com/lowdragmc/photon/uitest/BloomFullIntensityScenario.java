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
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import org.joml.Vector3f;

/**
 * Bloom at full intensity leaves the frame as it was below the threshold. The sharp-highlight pass at intensity 1
 * once shared the chain start's pipeline, which overwrites instead of blending: everything not bright went black.
 */
@LDLRegisterClient(name = "photon_bloom_full_intensity", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BloomFullIntensityScenario implements UIScenario {
    /** The scene view at 1920x1080, clear of the stats box and the animation tab. */
    private static final ScreenshotCompare.Region SCENE = new ScreenshotCompare.Region(450, 130, 1200, 750);

    @Override
    public void define(ScenarioBuilder s) {
        s.step("bloom on, at its default intensity", ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.enableBloom, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.bloomIntensity, 0.7);
                })
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false).shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("a magenta particle filling the view", BloomFullIntensityScenario::load)
                .frames(20)
                .screenshot("bloom_default")
                .step("full intensity", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.bloomIntensity, 1.0))
                .frames(10)
                .screenshot("bloom_full")
                .step("the particle is still there", ctx -> {
                    var normal = ScreenshotCompare.load(ctx, "bloom_default");
                    var full = ScreenshotCompare.load(ctx, "bloom_full");
                    ctx.require("both captures exist", normal != null && full != null && normal.sameSizeAs(full));
                    ctx.check("the particle fills the scene at the default intensity", magenta(normal) > 0.9, "> 90%",
                            magenta(normal));
                    ctx.check("and at full intensity", magenta(full) > 0.9, "> 90%", magenta(full));
                    // nothing crosses the threshold: the intensity has nothing to scale
                    var diff = full.diff(normal, 40, SCENE);
                    ctx.check("full intensity changes nothing below the threshold", diff.count() < 500, "< 500 px",
                            diff.count() + " px, " + diff.box());
                })
                .teardown("close the editor", ctx -> {
                    var editor = ctx.<FXEditor>get("editor");
                    if (editor != null) editor.closeCurrentProject(false, null);
                    ctx.mc().setScreen(null);
                    LightTestConfig.restore();
                });
    }

    /** The share of the scene's sampled pixels that are the particle's magenta. */
    private static double magenta(ScreenshotCompare image) {
        int magenta = 0, total = 0;
        for (int y = SCENE.top(); y < SCENE.bottom(); y += 4) {
            for (int x = SCENE.left(); x < SCENE.right(); x += 4) {
                int c = image.rgb(x, y);
                if (((c >> 16) & 0xFF) > 200 && ((c >> 8) & 0xFF) < 60 && (c & 0xFF) > 200) magenta++;
                total++;
            }
        }
        return magenta / (double) total;
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static void load(TestContext ctx) {
        var centre = new Vector3f(0.5f, 1f, 0.5f);
        var screen = new ParticleEmitter();
        screen.setName("Screen");
        var config = screen.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(20, 20, 20));
        config.setStartColor(NumberFunction.color(0xFFFF00FF));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(new TextureMaterial(Photon.id("textures/particle/white.png"))));

        var project = new FXProject();
        project.getFx().getFxData().objects().add(screen);
        var editor = editor(ctx);
        editor.loadProject(project, null);
        ctx.put("editor", editor);
        var renderer = editor.sceneView.sceneEditor.scene.<WorldSceneRenderer>getRenderer();
        ctx.require("the scene has a renderer", renderer != null);
        renderer.setCameraLookAt(centre, 9, 1.0, 0.6);
        screen.transform().localPosition(new Vector3f(renderer.getEyePos()).add(centre).mul(0.5f));
    }
}
