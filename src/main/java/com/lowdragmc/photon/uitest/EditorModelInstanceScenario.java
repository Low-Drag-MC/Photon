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
import com.lowdragmc.photon.client.gameobject.emitter.data.material.UIResourceMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ResourceMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

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

    /**
     * The user's own spider export rather than the fox: 3816 vertices, 43 joints, 21 clips, and the model
     * their report was against. The fox agreed on every path, so whatever differs has to be in a file.
     */
    private static final String FIXTURE = "spider.glb";
    private static final ResourceLocation MODEL = Photon.id("models/uitest_editor_model.glb");
    /** Author units; the spider is about a unit across, so this is near life size in the scene. */
    private static final double SIZE = 1.0;
    private static final int PARTICLES = 9;
    /** Floor for "this scenario is actually looking at the swarm" — see compareCaptures. */
    private static final int MIN_DRAWN_PIXELS = 20_000;
    private static final int MIN_DRAWN_WIDTH = 400;
    /** Close enough that PARTICLES models spread over the shape's x extent fill the scene view. */
    private static final double CAMERA_RADIUS = 9;
    /**
     * The part of the scene view the models occupy. ⚠️ Deliberately excludes the stats box in the scene's
     * bottom-right (playback time / CPU time / FPS): those digits change every frame, and a whole-window
     * diff measures them instead of the render.
     */
    private static final ScreenshotCompare.Region SCENE =
            new ScreenshotCompare.Region(900, 100, 3000, 1150);

    @Override
    public void define(ScenarioBuilder s) {
        s.step("stage the model fixture", ctx -> {
            var bytes = AnimatedGltfRenderScenario.readFixture(FIXTURE);
            ctx.require(FIXTURE + " was found", bytes != null);
            var target = new File(LDLib2.getAssetsDir(), "photon/models/uitest_editor_model.glb");
            try {
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), bytes);
            } catch (IOException e) {
                throw new IllegalStateException("could not stage " + FIXTURE, e);
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
        .step("load the one project this scenario gets", ctx -> loadProject(ctx, false, false))
        .frames(16)

        // An empty scene, for absolute scale. Reached by switching the emitter OFF rather than loading an
        // empty project, because there is only one load to spend. One model at the wrong scale covers a
        // wholly different number of pixels; two equally-wrong siblings agree and say nothing.
        .step("hide the emitter, for scale", ctx -> setRenderMode(ctx, ParticleRendererSetting.Mode.None))
        .frames(12)
        .screenshot("empty")
        .step("show it again", ctx -> setRenderMode(ctx, ParticleRendererSetting.Mode.Model))
        .frames(12);

        // ⚠️ ONE project load for the whole scenario. A second loadProject pops a modal "open in a new
        // window?" dialog, and every later load silently does nothing — which is how an earlier version
        // captured the warm-up project four times over and compared it with itself. Everything below is a
        // live mutation of the emitter that is already playing, which is also what the inspector does.
        for (boolean reference : List.of(false, true)) {
            String held = reference ? "reference" : "inline";
            if (reference) {
                s.step("point the library's mesh at the animated glTF source, and reference it",
                                EditorModelInstanceScenario::useLibraryReference)
                        .frames(12);
            }
            for (boolean perParticle : List.of(false, true)) {
                for (boolean instanced : List.of(false, true)) {
                    s.step("%s: per-particle %s, instancing %s".formatted(held,
                                    perParticle ? "on" : "off", instanced ? "on" : "off"),
                                    ctx -> toggle(ctx, perParticle, instanced))
                            .frames(12)
                            .screenshot(name(held, perParticle, instanced));
                }
            }
            s.step("compare what the four settings drew (" + held + ")",
                    ctx -> compareCaptures(ctx, held));
        }

        // ⚠️ A material whose library reference does not resolve falls back to IMaterial.MISSING, which is
        // not exotic — any project carrying a stale reference has one. It used to ignore the
        // MaterialContext and hand back a vanilla program with no per-instance attributes, so the CPU path
        // drew correctly and instancing drew every model untransformed at the origin.
        s.step("give the emitter a material reference that does not resolve",
                        EditorModelInstanceScenario::useUnresolvableMaterial)
                .frames(12)
                .step("missing material, instancing off", ctx -> setInstanced(ctx, false))
                .frames(12)
                .screenshot("missing_material_cpu")
                .step("missing material, instancing on", ctx -> setInstanced(ctx, true))
                .frames(12)
                .screenshot("missing_material_inst")
                .step("a missing material renders the same either way", ctx -> {
                    var cpu = ScreenshotCompare.load(ctx, "missing_material_cpu");
                    var instanced = ScreenshotCompare.load(ctx, "missing_material_inst");
                    if (cpu == null || instanced == null) {
                        ctx.check("both captures were written", false, "two images", "missing one");
                        return;
                    }
                    var drawn = cpu.diff(ScreenshotCompare.load(ctx, "empty"), 24, SCENE);
                    ctx.check("the missing material still draws something",
                            drawn.count() > MIN_DRAWN_PIXELS, "> " + MIN_DRAWN_PIXELS + " px",
                            drawn.count());
                    agree(ctx, "a missing material survives GPU instancing", cpu, instanced);
                });

        s.teardown("unpin the clock", ctx -> AnimatedGltfModelSource.pinClock(null))
                .teardown("give the library mesh its own source back", ctx -> {
                    var meshData = ctx.<MeshData>get("libraryMesh");
                    var original = ctx.<IModelSource>get("libraryOriginalSource");
                    if (meshData != null && original != null) meshData.setSource(original);
                })
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

    private static String name(String held, boolean perParticle, boolean instanced) {
        return held + "_" + (perParticle ? "perparticle" : "lockstep") + (instanced ? "_inst" : "_cpu");
    }

    /**
     * Repoint a builtin library mesh at the animated glTF source, so a {@link ResourceMeshSource} over it
     * resolves to the same thing an imported glb would. Cheaper and more direct than driving the import
     * dialog, and it is the reference indirection — not the import — that this covers.
     */
    private static void hijackLibraryMesh(TestContext ctx) {
        // ⚠️ Discovered, not hardcoded: a BuiltinPath built by hand does not resolve, and which meshes the
        // library holds is the resource providers' business, not this scenario's.
        var entries = MeshResource.INSTANCE.getResourceInstance().listAllResources();
        ctx.log("library meshes: " + entries.stream().map(e -> String.valueOf(e.getKey())).toList());
        var entry = entries.stream().filter(e -> e.getValue() != null).findFirst().orElse(null);
        ctx.require("the library holds at least one mesh", entry != null);
        ctx.put("libraryPath", entry.getKey());
        ctx.put("libraryOriginalSource", entry.getValue().getSource());
        entry.getValue().setSource(animatedSource(false));
        ctx.put("libraryMesh", entry.getValue());
    }

    private static void loadProject(TestContext ctx, boolean perParticle, boolean instanced) {
        loadProject(ctx, perParticle, instanced, false);
    }

    private static void loadProject(TestContext ctx, boolean perParticle, boolean instanced,
                                    boolean reference) {
        var project = new FXProject();
        var path = ctx.<IResourcePath>get("libraryPath");
        if (reference) ctx.require("the library mesh was hijacked first", path != null);
        project.getFx().getFxData().objects().add(
                emitter(perParticle, instanced, reference ? path : null));
        editor(ctx).loadProject(project, null);
        ctx.put("project", project);
        aimSceneCamera(ctx);
    }

    /**
     * Frame the swarm in the editor's own scene. ⚠️ Without this the models land as a ~160 px thumbnail in
     * a corner and every comparison below is vacuous — which is how an earlier version of this scenario
     * passed while testing nothing. {@code MIN_DRAWN_PIXELS} is the guard that keeps it honest.
     */
    private static void aimSceneCamera(TestContext ctx) {
        var renderer = editor(ctx).sceneView.sceneEditor.scene.getRenderer();
        if (renderer == null) return;
        // the FX root sits at (0.5, 2, 0.5); look at it from close enough that the spread fills the view
        renderer.setCameraLookAt(new Vector3f(0.5f, 2f, 0.5f), CAMERA_RADIUS, 0f, 0f);
    }

    private static void setRenderMode(TestContext ctx, ParticleRendererSetting.Mode mode) {
        forEachEmitter(ctx, emitter -> emitter.config.renderer.setRenderMode(mode));
        editor(ctx).reloadEffect();
    }

    /** Swap the emitter onto a library reference, the shape an IMPORTED glb has. */
    private static void useLibraryReference(TestContext ctx) {
        var entries = MeshResource.INSTANCE.getResourceInstance().listAllResources();
        var entry = entries.stream().filter(e -> e.getValue() != null).findFirst().orElse(null);
        ctx.require("the library holds at least one mesh", entry != null);
        ctx.put("libraryOriginalSource", entry.getValue().getSource());
        entry.getValue().setSource(animatedSource(false));
        ctx.put("libraryMesh", entry.getValue());
        var path = entry.getKey();
        forEachEmitter(ctx, emitter ->
                emitter.config.renderer.setModel(new MeshData(new ResourceMeshSource(path))));
        editor(ctx).reloadEffect();
    }

    private static void forEachEmitter(TestContext ctx,
                                       java.util.function.Consumer<ParticleEmitter> action) {
        var project = ctx.<FXProject>get("project");
        ctx.require("a project is loaded", project != null);
        var found = false;
        for (var object : project.getFx().getFxData().objects()) {
            if (object instanceof ParticleEmitter emitter) {
                found = true;
                action.accept(emitter);
            }
        }
        ctx.require("the project still holds the emitter", found);
    }

    private static void setInstanced(TestContext ctx, boolean instanced) {
        forEachEmitter(ctx, emitter -> emitter.config.renderer.setUseGPUInstance(instanced));
        editor(ctx).reloadEffect();
    }

    /** A library reference to a material that is not there, which resolves to {@code IMaterial.MISSING}. */
    private static void useUnresolvableMaterial(TestContext ctx) {
        forEachEmitter(ctx, emitter -> {
            var materials = emitter.config.renderer.getMaterials();
            materials.clear();
            materials.add(new MaterialSetting(new UIResourceMaterial(
                    new BuiltinPath("no-such-material")))
                    .setDepthMask(true).setCull(true));
        });
        editor(ctx).reloadEffect();
    }

    /** Flip both switches on the emitter that is already playing, the way the inspector does. */
    private static void toggle(TestContext ctx, boolean perParticle, boolean instanced) {
        forEachEmitter(ctx, emitter -> {
            var renderer = emitter.config.renderer;
            renderer.setUseGPUInstance(instanced);
            // through a reference the glTF source lives in the library, not on the emitter
            var source = renderer.getModel().getSource();
            if (source instanceof ResourceMeshSource) {
                var libraryMesh = ctx.<MeshData>get("libraryMesh");
                ctx.require("the library mesh is hijacked", libraryMesh != null);
                source = libraryMesh.getSource();
            }
            if (source instanceof AnimatedGltfModelSource animated) {
                animated.setPerParticlePhase(perParticle);
            } else {
                ctx.require("the animated source is reachable, got " + source, false);
            }
        });
        editor(ctx).reloadEffect();
    }

    /**
     * A burst of {@link #PARTICLES} models spread along x so each is its own silhouette, with the clock
     * pinned — so the only thing that can differ between two captures is the setting under test.
     */
    private static AnimatedGltfModelSource animatedSource(boolean perParticle) {
        var source = new AnimatedGltfModelSource(MODEL);
        source.setPerParticlePhase(perParticle);
        source.invalidate();
        return source;
    }

    private static ParticleEmitter emitter(boolean perParticle, boolean instanced,
                                           @Nullable IResourcePath reference) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(PARTICLES);
        config.shape.setScale(new NumberFunction3(9, 0, 2));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(PARTICLES));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);

        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        renderer.setModel(new MeshData(reference != null
                ? new ResourceMeshSource(reference)
                : animatedSource(perParticle)));
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
    private static void compareCaptures(TestContext ctx, String held) {
        var lockstepCpu = ScreenshotCompare.load(ctx, name(held, false, false));
        var lockstepInst = ScreenshotCompare.load(ctx, name(held, false, true));
        var perParticleCpu = ScreenshotCompare.load(ctx, name(held, true, false));
        var perParticleInst = ScreenshotCompare.load(ctx, name(held, true, true));
        if (lockstepCpu == null || lockstepInst == null
                || perParticleCpu == null || perParticleInst == null) {
            ctx.check(held + ": all four captures were written", false, "four images",
                    "%s %s %s %s".formatted(lockstepCpu, lockstepInst, perParticleCpu, perParticleInst));
            return;
        }

        agree(ctx, held + ": instancing does not change the lockstep picture",
                lockstepCpu, lockstepInst);
        agree(ctx, held + ": instancing does not change the per-particle picture",
                perParticleCpu, perParticleInst);
        differ(ctx, held + ": per-particle phase spreads the CPU path", lockstepCpu, perParticleCpu);
        differ(ctx, held + ": per-particle phase spreads the instanced path",
                lockstepInst, perParticleInst);

        // ⚠️ Absolute, not relative: two equally-wrong siblings agree with each other. Against an empty
        // scene, a model drawn at the wrong scale covers a wholly different number of pixels.
        var empty = ScreenshotCompare.load(ctx, "empty");
        if (empty == null) {
            ctx.check("the empty baseline was written", false, "an image", "missing");
            return;
        }
        // ⚠️ First: is this scenario even looking at the swarm? Every comparison below is vacuous if the
        // models are a thumbnail in a corner — which is exactly how an earlier version of this passed while
        // testing nothing. A spread burst of PARTICLES models has to be wide, and has to cover real area.
        var drawnByCpu = empty.diff(lockstepCpu, 24, SCENE);
        ctx.log(held + ": the CPU path covers %d px, %s".formatted(drawnByCpu.count(), drawnByCpu.box()));
        ctx.check(held + ": the swarm is on screen at a size worth comparing",
                drawnByCpu.count() > MIN_DRAWN_PIXELS && drawnByCpu.width() > MIN_DRAWN_WIDTH,
                "> %d px over > %d px wide".formatted(MIN_DRAWN_PIXELS, MIN_DRAWN_WIDTH),
                "%d px over %d px wide".formatted(drawnByCpu.count(), drawnByCpu.width()));
        int reference = drawnByCpu.count();
        for (var entry : new Object[][]{
                {"lockstep instanced", lockstepInst},
                {"per-particle CPU", perParticleCpu},
                {"per-particle instanced", perParticleInst}}) {
            var drawn = empty.diff((ScreenshotCompare) entry[1], 24, SCENE).count();
            ctx.log("%s / %s covers %d px, CPU lockstep covers %d".formatted(
                    held, entry[0], drawn, reference));
            ctx.check("%s: %s covers about as much as the CPU path".formatted(held, entry[0]),
                    drawn > reference / 2 && drawn < reference * 2,
                    "between %d and %d px".formatted(reference / 2, reference * 2), drawn);
        }
    }

    /** Aliasing on a model edge moves a few hundred pixels; a wrong scale or a missing draw moves a sea. */
    private static void agree(TestContext ctx, String what,
                              ScreenshotCompare a, ScreenshotCompare b) {
        var diff = a.diff(b, 24, SCENE);
        ctx.log("%s: %d px (%.3f%%) %s".formatted(what, diff.count(), 100 * diff.fraction(), diff.box()));
        ctx.check(what, diff.fraction() < 0.002, "< 0.2% of the picture",
                "%.3f%% (%s)".formatted(100 * diff.fraction(), diff.box()));
    }

    private static void differ(TestContext ctx, String what,
                               ScreenshotCompare a, ScreenshotCompare b) {
        var diff = a.diff(b, 24, SCENE);
        ctx.log("%s: %d px (%.3f%%) %s".formatted(what, diff.count(), 100 * diff.fraction(), diff.box()));
        ctx.check(what, diff.count() > 200, "> 200 px different",
                "%d px — the same pose for every particle".formatted(diff.count()));
    }

}
