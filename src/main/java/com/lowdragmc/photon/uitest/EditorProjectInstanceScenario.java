package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;

import java.io.File;

/**
 * Loads a real {@code .fxproj} off disk and flips <b>only</b> Use GPU Instance on it.
 *
 * <p>⚠️ Why a file rather than a config built here: an emitter assembled in code passed on every path,
 * while the same feature in a hand-authored project rendered at a wildly wrong scale. Whatever differs is
 * in the project — a layer, a material, a channel — and the honest way to cover that is to stop guessing
 * at the configuration and load the one that actually fails.</p>
 *
 * <p>Skips cleanly when the project is absent, so it does not fail a machine that has no fixture.</p>
 */
@LDLRegisterClient(name = "editor_project_instance", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EditorProjectInstanceScenario implements UIScenario {

    private static final String PROJECT = "test.fxproj";
    /** The scene view, minus the live stats box in its bottom-right; see {@link ScreenshotCompare#diff}. */
    private static final ScreenshotCompare.Region SCENE =
            new ScreenshotCompare.Region(900, 100, 3000, 1150);
    /**
     * How far the two geometry paths may drift. Generous, because the effect keeps simulating between the
     * two captures; far below the 33 000 px a wrong transform produced.
     */
    private static final int MAX_PATH_DIFFERENCE = 12_000;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("find the project", ctx -> {
            var file = new File(LDLib2.getAssetsDir(), PROJECT);
            ctx.log("looking for " + file.getAbsolutePath());
            ctx.put("projectFile", file.isFile() ? file : null);
            ctx.check("the project fixture is present", file.isFile(), PROJECT, "missing");
        })

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())

        .step("open it", ctx -> {
            var file = ctx.<File>get("projectFile");
            ctx.require("the project fixture is present", file != null);
            try {
                var project = FXProject.TYPE.loadProjectFromFile(file);
                ctx.require("it deserialized into an FXProject", project instanceof FXProject);
                editor(ctx).loadProject(project, file);
                ctx.put("project", (FXProject) project);
            } catch (Exception e) {
                throw new IllegalStateException("could not open " + file, e);
            }
        })
        .frames(30)

        .step("report what it holds", ctx -> {
            var emitters = 0;
            for (var object : ctx.<FXProject>get("project").getFx().getFxData().objects()) {
                if (!(object instanceof ParticleEmitter emitter)) continue;
                emitters++;
                var renderer = emitter.config.renderer;
                ctx.log("emitter '%s': mode=%s instanced=%s shade=%s blockUV=%s tangent=%s materials=%d source=%s"
                        .formatted(emitter.getName(), renderer.getRenderMode(),
                                renderer.isUseGPUInstance(), renderer.isShade(), renderer.isUseBlockUV(),
                                renderer.isTangent(), renderer.getMaterials().size(),
                                renderer.getModel().getSource().getClass().getSimpleName()));
            }
            ctx.check("the project holds at least one emitter", emitters > 0, "> 0", emitters);
        })

        .step("instancing OFF", ctx -> setInstanced(ctx, false))
        .frames(20)
        .screenshot("project_cpu")

        .step("instancing ON", ctx -> setInstanced(ctx, true))
        .frames(20)
        .screenshot("project_instanced")

        .step("the two paths drew the same thing", EditorProjectInstanceScenario::compare)

        .teardown("put instancing back the way the file had it",
                ctx -> {
                    if (ctx.get("project") != null) setInstanced(ctx, false);
                });
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static void setInstanced(TestContext ctx, boolean instanced) {
        var project = ctx.<FXProject>get("project");
        ctx.require("a project is loaded", project != null);
        for (var object : project.getFx().getFxData().objects()) {
            if (object instanceof ParticleEmitter emitter) {
                emitter.config.renderer.setUseGPUInstance(instanced);
            }
        }
        editor(ctx).reloadEffect();
    }

    private static void compare(TestContext ctx) {
        var cpu = ScreenshotCompare.load(ctx, "project_cpu");
        var instanced = ScreenshotCompare.load(ctx, "project_instanced");
        if (cpu == null || instanced == null) {
            ctx.check("both captures were written", false, "two images", "missing one");
            return;
        }
        var between = cpu.diff(instanced, 24, SCENE);
        ctx.log("cpu vs instanced: %d px (%.3f%%) %s".formatted(
                between.count(), 100 * between.fraction(), between.box()));
        // ⚠️ A live swarm moves between the two captures, so this is a band rather than an exact match.
        // The failure it exists for was not subtle: models drawn untransformed at the origin moved 1.5%
        // of the scene and spanned 1132x507 px, an order of magnitude past anything animation jitter does.
        ctx.check("GPU instancing draws roughly what the CPU path draws",
                between.count() < MAX_PATH_DIFFERENCE, "< %d px".formatted(MAX_PATH_DIFFERENCE),
                "%d px (%s)".formatted(between.count(), between.box()));
    }
}
