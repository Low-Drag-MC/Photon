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
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import org.joml.Vector3f;

/** A raycasting beam stops where it hits, in the world and in an editor scene (whose camera has no entity). */
@LDLRegisterClient(name = "photon_beam_raycast", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BeamRaycastScenario implements UIScenario {
    private static final float LENGTH = 10;
    private static final float HEIGHT = 3;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("in the world, a beam into the ground stops at it", BeamRaycastScenario::world)
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("a raycasting beam in the editor scene", BeamRaycastScenario::loadProject)
                .frames(20)
                .screenshot("editor_beam")
                .step("it is drawn, and it raycasts in the scene's level", ctx -> {
                    var emitter = ctx.<BeamEmitter>get("emitter");
                    ctx.check("the beam runs in the scene's level", emitter.getLevel() != null, "a level", "null");
                    var end = new BeamParticle(emitter, emitter.getConfig()).getRealEnd(new Vector3f(0, HEIGHT, 0));
                    ctx.check("and stops short of its full length there", end.y > HEIGHT - LENGTH,
                            "> " + (HEIGHT - LENGTH), end.y);
                })
                .teardown("close the editor", ctx -> ctx.mc().setScreen(null));
    }

    private static BeamEmitter beam(BeamConfig.RaycastMode mode) {
        var config = new BeamConfig();
        config.setRaycast(mode);
        config.getEnd().set(0, -LENGTH, 0);
        return new BeamEmitter(config);
    }

    private static void world(TestContext ctx) {
        var player = ctx.requirePlayer();
        float ground = (float) player.getY();
        // not ENTITIES: as in 1.21 it sweeps a box at the world origin, not along the beam
        for (var mode : new BeamConfig.RaycastMode[]{BeamConfig.RaycastMode.NONE, BeamConfig.RaycastMode.BLOCKS,
                BeamConfig.RaycastMode.BLOCKS_AND_ENTITIES}) {
            var emitter = beam(mode);
            emitter.setLevel(player.level());
            var from = new Vector3f((float) player.getX(), ground + HEIGHT, (float) player.getZ());
            emitter.transform().position(from);
            var end = new BeamParticle(emitter, emitter.getConfig()).getRealEnd(new Vector3f(from));
            if (mode == BeamConfig.RaycastMode.NONE) {
                ctx.check("NONE: the full length", Math.abs(end.y - (from.y - LENGTH)) < 1e-3, from.y - LENGTH, end.y);
            } else {
                ctx.check(mode + ": stops at the ground", Math.abs(end.y - ground) < 1.01f, ground, end.y);
            }
        }
    }

    private static void loadProject(TestContext ctx) {
        var emitter = beam(BeamConfig.RaycastMode.BLOCKS_AND_ENTITIES);
        emitter.setName("Raycast beam");
        emitter.transform().localPosition(new Vector3f(0, HEIGHT, 0));
        var project = new FXProject();
        project.getFx().getFxData().objects().add(emitter);
        var editor = ctx.query().type(FXEditor.class).one().as(FXEditor.class);
        editor.loadProject(project, null);
        ctx.put("emitter", emitter);
        var renderer = editor.sceneView.sceneEditor.scene.<WorldSceneRenderer>getRenderer();
        if (renderer != null) renderer.setCameraLookAt(new Vector3f(0.5f, 1.5f, 0.5f), 9, 0.6, 0.45);
    }
}
