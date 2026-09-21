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
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * The editor's own scene drawing an animated model, with GPU instancing off and then on.
 *
 * <p>⚠️ Why this exists: {@code animated_gltf_render} draws through a {@code BlockEffectExecutor} in the
 * world, where the two paths are pixel-identical. The editor's preview is a different scene renderer, and
 * a regression that only showed up there — one model at the wrong scale — went unnoticed because nothing
 * automated ever opened the editor with instancing on.</p>
 *
 * <p>The assertions compare captures rather than eyeball them: the two paths must agree, and a swarm must
 * not collapse to a single silhouette.</p>
 */
@LDLRegisterClient(name = "editor_model_instance", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EditorModelInstanceScenario implements UIScenario {

    private static final ResourceLocation MODEL = Photon.id("models/uitest_editor_fox.glb");
    /** Author units; the fox is ~140 tall, so this lands it near a block. */
    private static final double SIZE = 0.018;
    private static final int PARTICLES = 9;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("stage the fox fixture", ctx -> {
            var bytes = AnimatedGltfRenderScenario.readFixture();
            ctx.require("fox.glb was found", bytes != null);
            var target = new File(LDLib2.getAssetsDir(), "photon/models/uitest_editor_fox.glb");
            try {
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), bytes);
            } catch (IOException e) {
                throw new IllegalStateException("could not stage the fox fixture", e);
            }
            ctx.put("fixture", target);
            ctx.check("the fixture is in place", target.isFile(), "a file", "missing");
        })

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())

        .step("pin the clock so only the settings can change the picture",
                ctx -> AnimatedGltfModelSource.pinClock(0.4f))

        // ⚠️ A warm-up load first. The glb parses on the first getMesh() and the scene settles over a
        // frame or two, so without this the FIRST measured capture is darker than the other three and the
        // comparison blames whichever setting happened to go first.
        .step("warm up: load and discard", ctx -> loadProject(ctx, false, false))
        .frames(12);

        // The four combinations, reached by TOGGLING a live emitter rather than loading a fresh project —
        // that is what the user does in the inspector, and it is the path where a stale instance layout
        // would survive the change.
        for (boolean perParticle : List.of(false, true)) {
            for (boolean instanced : List.of(false, true)) {
                s.step("toggle to per-particle %s, instancing %s"
                                .formatted(perParticle ? "on" : "off", instanced ? "on" : "off"),
                                ctx -> toggle(ctx, perParticle, instanced))
                        .frames(12)
                        .screenshot(name(perParticle, instanced));
            }
        }

        s.step("compare what the four settings drew", EditorModelInstanceScenario::compareCaptures)

        // ⚠️ This scenario holds the glTF source INLINE. An imported glb does not — it becomes a
        // ResourceMeshSource pointing into the library, and a capability that reference forgets to forward
        // is invisible here. ModelSourceDelegationTest is what covers that.

        .teardown("unpin the clock", ctx -> AnimatedGltfModelSource.pinClock(null))
        .teardown("remove the fixture", ctx -> {
            var file = ctx.<File>get("fixture");
            if (file != null && file.exists() && !file.delete()) {
                Photon.LOGGER.warn("could not delete {}", file);
            }
        });
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static String name(boolean perParticle, boolean instanced) {
        return (perParticle ? "perparticle" : "lockstep") + (instanced ? "_inst" : "_cpu");
    }

    private static void loadProject(TestContext ctx, boolean perParticle, boolean instanced) {
        var project = new FXProject();
        project.getFx().getFxData().objects().add(emitter(perParticle, instanced));
        editor(ctx).loadProject(project, null);
        ctx.put("project", project);
    }

    /** Flip both switches on the emitter that is already playing, the way the inspector does. */
    private static void toggle(TestContext ctx, boolean perParticle, boolean instanced) {
        var project = ctx.<FXProject>get("project");
        ctx.require("a project is loaded", project != null);
        var found = false;
        for (var object : project.getFx().getFxData().objects()) {
            if (!(object instanceof ParticleEmitter emitter)) continue;
            found = true;
            var renderer = emitter.config.renderer;
            renderer.setUseGPUInstance(instanced);
            if (renderer.getModel().getSource() instanceof AnimatedGltfModelSource source) {
                source.setPerParticlePhase(perParticle);
            } else {
                ctx.require("the emitter still holds the animated source", false);
            }
        }
        ctx.require("the project still holds the emitter", found);
        editor(ctx).reloadEffect();
    }

    /**
     * A burst of {@link #PARTICLES} models spread along x so each is its own silhouette, with the clock
     * pinned — so the only thing that can differ between two captures is the setting under test.
     */
    private static ParticleEmitter emitter(boolean perParticle, boolean instanced) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(PARTICLES);
        config.shape.setScale(new NumberFunction3(6, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(PARTICLES));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);

        var source = new AnimatedGltfModelSource(MODEL);
        source.setPerParticlePhase(perParticle);
        source.invalidate();
        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        renderer.setModel(new MeshData(source));
        renderer.setUseGPUInstance(instanced);
        renderer.getMaterials().add(new MaterialSetting(new TextureMaterial(
                ResourceLocation.parse("textures/block/white_concrete.png")))
                .setDepthMask(true).setCull(true));
        return emitter;
    }

    /**
     * The captures are on disk by now, so read them back rather than trusting the eye.
     *
     * <p>Two independent claims. <b>Instancing must not change the picture</b> — it is an upload strategy,
     * not a look, and a path that drew one model at the wrong scale fails here loudly. And
     * <b>per-particle phase must change it</b>: with the clock pinned, lockstep puts all nine models in
     * one pose while per-particle spreads them across the baked table, so two captures that match mean the
     * per-particle random never reached the shader.</p>
     */
    private static void compareCaptures(TestContext ctx) {
        var lockstepCpu = ScreenshotCompare.load(ctx, name(false, false));
        var lockstepInst = ScreenshotCompare.load(ctx, name(false, true));
        var perParticleCpu = ScreenshotCompare.load(ctx, name(true, false));
        var perParticleInst = ScreenshotCompare.load(ctx, name(true, true));
        if (lockstepCpu == null || lockstepInst == null
                || perParticleCpu == null || perParticleInst == null) {
            ctx.check("all four captures were written", false, "four images",
                    "%s %s %s %s".formatted(lockstepCpu, lockstepInst, perParticleCpu, perParticleInst));
            return;
        }

        agree(ctx, "instancing does not change the lockstep picture", lockstepCpu, lockstepInst);
        agree(ctx, "instancing does not change the per-particle picture",
                perParticleCpu, perParticleInst);
        differ(ctx, "per-particle phase spreads the CPU path", lockstepCpu, perParticleCpu);
        differ(ctx, "per-particle phase spreads the instanced path", lockstepInst, perParticleInst);
    }

    /** Aliasing on a model edge moves a few hundred pixels; a wrong scale or a missing draw moves a sea. */
    private static void agree(TestContext ctx, String what,
                              ScreenshotCompare a, ScreenshotCompare b) {
        var diff = a.diff(b, 24);
        ctx.log("%s: %d px (%.3f%%) %s".formatted(what, diff.count(), 100 * diff.fraction(), diff.box()));
        ctx.check(what, diff.fraction() < 0.002, "< 0.2% of the picture",
                "%.3f%% (%s)".formatted(100 * diff.fraction(), diff.box()));
    }

    private static void differ(TestContext ctx, String what,
                               ScreenshotCompare a, ScreenshotCompare b) {
        var diff = a.diff(b, 24);
        ctx.log("%s: %d px (%.3f%%) %s".formatted(what, diff.count(), 100 * diff.fraction(), diff.box()));
        ctx.check(what, diff.count() > 200, "> 200 px different",
                "%d px — the same pose for every particle".formatted(diff.count()));
    }

}
