package com.lowdragmc.photon.uitest;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.menu.MenuTab;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ElementBounds;
import com.lowdragmc.lowdraglib2.uitest.ElementRef;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.collision.CollisionDebug;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.fxpack.FXPackExporter;
import com.lowdragmc.photon.client.fx.fxpack.FXPacks;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.collider.ColliderConfig;
import com.lowdragmc.photon.client.gameobject.collider.ColliderObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.light.LightConfig;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXFileActions;
import com.lowdragmc.photon.gui.editor.FXInstanceInspector;
import com.lowdragmc.photon.gui.editor.FXInstanceOverridesDialog;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The editor pictures of the Photon wiki, named after the files they become under {@code docs/assets/photon2/}:
 * FX instances (the hierarchy and inspector, the Overrides dialog, the instance menu), the File menu, the FXPack
 * manager, the timeline transport, collision (the Physics inspector, colliders in the scene), an animated glTF
 * model source, the Light inspector, the KilaMaterial inspector and its dialogs, and the dynamic-light config.
 * Run with {@code -PldTestWindow=2560x1362}. Its fixtures live under {@code uitest_wiki} and are deleted after.
 */
@LDLRegisterClient(name = "wiki_photon_editor", group = "photon_wiki", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WikiEditorScenario implements UIScenario {
    private static final String DIR = "uitest_wiki";
    private static final String PACK = "fxdemo";
    private static final ResourceLocation ORB = Photon.id(DIR + "/orb");
    private static final ResourceLocation FOX = Photon.id("models/uitest_wiki_fox.glb");

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("wiki", "photon", "editor").requiresWorld(true).guiScale(2).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("write the fixtures", WikiEditorScenario::writeFixtures)
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty());

        s.step("place two instances of the orb", WikiEditorScenario::loadInstances)
                .frames(40)
                .step("select the overridden sparks", ctx -> {
                    FXInstanceObject green = ctx.get("green");
                    expandAll(ctx);
                    select(ctx, placed(ctx, green, "Sparks"));
                    editor(ctx).sceneView.fxObjectInfoView.setDisplay(false);
                })
                .frames(20)
                .screenshot("fx-instance-editor")
                .step("open the overrides of the green one", ctx -> FXInstanceOverridesDialog.show(editor(ctx), ctx.get("green")))
                .frames(6)
                .screenshot("fx-instance-overrides")
                .step("the dialog alone", ctx -> dialogShot(ctx, "fx-instance-overrides_dialog"))
                .step("close the dialog", WikiEditorScenario::closeDialogs);
        rightClickRow(s, "Orb (green)");
        s.frames(4)
                .step("open the FX Instance submenu", ctx -> {
                    var entry = ctx.query().type(TextElement.class).visible().list().stream()
                            .filter(ref -> "FX Instance".equals(ref.text()))
                            .map(ref -> ref.element())
                            .filter(element -> element.getFirstAncestorOfType(com.lowdragmc.lowdraglib2.gui.ui.elements.Menu.class) != null)
                            .findFirst().orElse(null);
                    ctx.require("an FX Instance entry", entry != null);
                    var bounds = ElementBounds.of(entry);
                    ctx.input().moveTo(bounds.centerX(), bounds.centerY());
                })
                .frames(6)
                .screenshot("fx-instance-menu");
        dismissMenu(s);

        s.step("open the file menu", ctx -> editor(ctx).openMenu(4, 15, fileMenuBuilder(editor(ctx))))
                .frames(4)
                .screenshot("editor-file-menu");
        dismissMenu(s);
        s.step("export two effects into a pack and manage it", ctx -> {
                    exportIntoPack(ctx);
                    showFxPackContents(editor(ctx), pack());
                })
                .frames(6)
                .step("the manager alone", ctx -> dialogShot(ctx, "fxpack-manager"))
                .step("close the manager", WikiEditorScenario::closeDialogs)

                .frames(2)
                .step("the transport bar", ctx -> ctx.screenshotElement("timeline-transport",
                        ctx.query().withId("timeline.transport").one()))
                .frames(2)

                .step("load sparks among colliders", WikiEditorScenario::loadColliders)
                .frames(60)
                .step("select the box", ctx -> select(ctx, ctx.get("box")))
                .frames(4)
                .step("the box", ctx -> ctx.screenshotElement("collision-colliders_box", new ElementRef(editor(ctx).sceneView, "scene")))
                .step("select the trigger sphere", ctx -> select(ctx, ctx.get("sphere")))
                .frames(4)
                .step("the sphere", ctx -> ctx.screenshotElement("collision-colliders_trigger", new ElementRef(editor(ctx).sceneView, "scene")))
                .step("inspect the physics", ctx -> {
                    ParticleEmitter sparks = ctx.get("sparks");
                    select(ctx, sparks);
                    editor(ctx).inspectorView.inspect(sparks.config.physics);
                    KilaUiTestKit.openEveryGroup(ctx);
                })
                .frames(10)
                .step("the contacts", ctx -> ctx.screenshotElement("collision-colliders_contacts", new ElementRef(editor(ctx).sceneView, "scene")))
                .screenshot("collision-colliders")
                .step("the physics inspector", ctx -> ctx.screenshotElement("collision-inspector",
                        new ElementRef(editor(ctx).inspectorView, "inspector")))

                .step("load an animated fox", WikiEditorScenario::loadFox)
                .frames(30)
                .step("inspect its model source", ctx -> {
                    ParticleEmitter fox = ctx.get("fox");
                    select(ctx, fox);
                    editor(ctx).inspectorView.inspect(fox.config.renderer.getModel());
                })
                .frames(4)
                .step("scroll down to the phase rows", ctx -> {
                    var row = KilaUiTestKit.label(ctx, "Interpolate Frames");
                    if (row != null) KilaUiTestKit.scrollIntoView(row);
                })
                .frames(6)
                .screenshot("gltf-animated-source")

                .step("load a spot light over embers", WikiEditorScenario::loadLight)
                .frames(40)
                .step("select the light", ctx -> select(ctx, ctx.get("light")))
                .frames(10)
                .screenshot("lights-light-inspector")

                .step("load a Kila fire", WikiEditorScenario::loadKila)
                .frames(40)
                .step("inspect its material", ctx -> {
                    editor(ctx).inspectorView.inspect(ctx.<KilaMaterial>get("kila"));
                    KilaUiTestKit.openEveryGroup(ctx);
                })
                .frames(10)
                .screenshot("kila-inspector");
        KilaUiTestKit.clickOn(s, "the presets button", ctx -> KilaUiTestKit.label(ctx, "Browse Presets..."));
        s.waitUntil("the gallery is open", ctx -> KilaUiTestKit.dialogLabel(ctx, "Lightning") != null)
                .step("move the pointer off the gallery", ctx -> ctx.input().moveTo(300, 300))
                .frames(6)
                .step("the gallery alone", ctx -> dialogShot(ctx, "kila-presets"))
                .step("close the gallery", WikiEditorScenario::closeDialogs)
                .frames(2);
        KilaUiTestKit.clickOn(s, "the add module button", ctx -> KilaUiTestKit.label(ctx, "+ Add Module..."));
        s.waitUntil("the module list is open", ctx -> KilaUiTestKit.dialogLabel(ctx, "Fresnel") != null)
                .step("move the pointer off the list", ctx -> ctx.input().moveTo(300, 300))
                .frames(4)
                .step("the module list alone", ctx -> dialogShot(ctx, "kila-modules"))
                .step("close the module list", WikiEditorScenario::closeDialogs)

                .step("close the project", ctx -> editor(ctx).closeCurrentProject(false, null))
                .step("open the dynamic lights config", ctx -> ctx.mc().setScreen(lightConfigScreen()))
                .frames(10)
                .screenshot("lights-config");

        s.teardown("clean up", ctx -> {
            CollisionDebug.enabled = false;
            // the config screen replaced the editor's, so there may be no UI left to query
            if (ctx.mc().screen instanceof ModularUIScreen) {
                var editors = ctx.query().type(FXEditor.class).list();
                if (!editors.isEmpty()) editors.getFirst().as(FXEditor.class).closeCurrentProject(false, null);
            }
            deleteTree(fixtureDir());
            var packFile = pack();
            if (packFile.exists() && !packFile.delete()) packFile.deleteOnExit();
            var fox = new File(LDLib2.getAssetsDir(), "photon/models/uitest_wiki_fox.glb");
            if (fox.exists() && !fox.delete()) fox.deleteOnExit();
            FXHelper.clearCache();
        });
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static void open(TestContext ctx, FXProject project, Vector3f lookAt, double radius, double yaw, double pitch) {
        var editor = editor(ctx);
        if (editor.getCurrentProject() != null) editor.closeCurrentProject(false, null);
        editor.loadProject(project, null);
        editor.sceneView.fxObjectInfoView.setDisplay(false);
        expandAll(ctx);
        var renderer = editor.sceneView.sceneEditor.scene.getRenderer();
        if (renderer != null) renderer.setCameraLookAt(lookAt, radius, yaw, pitch);
    }

    /** What a click on the hierarchy row does: inspector (with its FX-instance header), gizmo target, scene overlay. */
    private static void select(TestContext ctx, IFXObject object) {
        var editor = editor(ctx);
        var group = editor.inspectorView.inspect(object, null, null);
        FXInstanceInspector.decorate(editor, group, object);
        editor.sceneView.fxObjectInfoView.inspect(object);
        editor.sceneView.sceneEditor.setTransformGizmoTarget(object.transform());
    }

    private static void expandAll(TestContext ctx) {
        var tree = editor(ctx).hierarchyView.treeList;
        var root = editor(ctx).hierarchyView.getRootNode();
        if (root == null) return;
        tree.expandNode(root);
        for (var child : root.getChildren()) {
            tree.expandNode(child);
            for (var grandChild : child.getChildren()) tree.expandNode(grandChild);
        }
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("wiki_editor/" + name).getBytes());
    }

    private static <T extends IFXObject> T named(T object, String name) {
        object.setName(name);
        object.transform()._setInternalID(id(name));
        return object;
    }

    private static File fixtureDir() {
        return new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + DIR);
    }

    private static File pack() {
        return new File(FXPacks.getFxPacksDir(), PACK + FXPacks.SUFFIX);
    }

    /** The orb an instance places: a charging core, a ring, sparks and a light. */
    private static FX orb() {
        var fx = new FX();
        var core = named(KilaGalleryScenario.showcase(KilaPresets.CHARGE_ORB), "Core");
        core.config.setStartSize(new NumberFunction3(1.4, 1.4, 1.4));
        var ring = named(KilaGalleryScenario.showcase(KilaPresets.SHOCKWAVE), "Ring");
        ring.config.setStartSize(new NumberFunction3(2.2, 2.2, 2.2));
        var sparks = named(KilaGalleryScenario.showcase(KilaPresets.SPARKS), "Sparks");
        var light = named(new LightObject(), "Glow Light");
        light.config.setColor(new HDRConstantColor(new HDRColor(0.6f, 0.5f, 1f, 1f, 1f)));
        light.config.setIntensity(NumberFunction.constant(8));
        light.config.setRange(NumberFunction.constant(6));
        for (var object : List.<IFXObject>of(core, ring, sparks, light)) {
            object.transform().localPosition(new Vector3f(0, 1.2f, 0));
            fx.getFxData().objects().add(object);
        }
        return fx;
    }

    private static void writeFixtures(TestContext ctx) {
        try {
            var tag = orb().serializeNBT(Platform.getFrozenRegistry());
            tag.putInt("version", FXProject.VERSION);
            var file = new File(fixtureDir(), "orb" + FX.SUFFIX);
            Files.createDirectories(file.getParentFile().toPath());
            NbtIo.writeCompressed(tag, file.toPath());
            var bytes = AnimatedGltfRenderScenario.readFixture();
            ctx.require("fox.glb was found", bytes != null);
            var fox = new File(LDLib2.getAssetsDir(), "photon/models/uitest_wiki_fox.glb");
            Files.createDirectories(fox.getParentFile().toPath());
            Files.write(fox.toPath(), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("could not write the fixtures", e);
        }
        FXHelper.clearCache();
    }

    @Nullable
    private static IFXObject placed(TestContext ctx, FXInstanceObject instance, String name) {
        var found = new IFXObject[1];
        instance.executeAll(scene -> {
            if (scene != instance && scene instanceof IFXObject object && name.equals(object.getName()) && found[0] == null) {
                found[0] = object;
            }
        });
        return found[0];
    }

    private static void loadInstances(TestContext ctx) {
        open(ctx, new FXProject(), new Vector3f(0, 1.2f, 0), 9.5, 0.5, 0.32);
        var editor = editor(ctx);
        var root = editor.runtime.root.transform();
        var plain = editor.instances.create(ORB, root);
        ctx.require("the orb was placed", plain != null);
        plain.setName("Orb");
        plain.transform().localPosition(new Vector3f(-2.6f, 0, 0));
        var green = editor.instances.create(ORB, root);
        ctx.require("the second orb was placed", green != null);
        green.setName("Orb (green)");
        green.transform().localPosition(new Vector3f(2.6f, 0, 0));
        var sparks = (ParticleEmitter) placed(ctx, green, "Sparks");
        ctx.require("its sparks", sparks != null);
        sparks.config.setStartColor(NumberFunction.color(0xFF6CFF8A));
        sparks.config.setStartSpeed(NumberFunction.constant(3));
        var core = (ParticleEmitter) placed(ctx, green, "Core");
        ctx.require("its core", core != null);
        if (core.config.renderer.getMaterials().getFirst().getMaterial() instanceof KilaMaterial kila) {
            kila.color = new HDRColor(0.35f, 1f, 0.5f, 1f, 2f);
        }
        var ring = placed(ctx, green, "Ring");
        ctx.require("its ring", ring != null);
        editor.instances.setEnabled(ring, false);
        var light = placed(ctx, green, "Glow Light");
        ctx.require("its light", light != null);
        editor.hierarchyView.removeSceneObject(light);
        var glow = named(KilaGalleryScenario.showcase(KilaPresets.GLOW), "Extra Glow");
        glow.config.setStartColor(NumberFunction.color(0xFF6CFF8A));
        glow.config.setStartSize(new NumberFunction3(2.5, 2.5, 2.5));
        glow.transform().parent(green.transform(), false);
        glow.transform().localPosition(new Vector3f(0, 1.2f, 0));
        editor.hierarchyView.addSceneObject(glow);
        editor.instances.changed();
        ctx.put("green", green);
    }

    private static void exportIntoPack(TestContext ctx) {
        var packFile = pack();
        if (packFile.exists() && !packFile.delete()) ctx.require("the old pack is gone", false);
        try {
            FXPackExporter.exportInto(packFile, PACK, "orb", orb(), Platform.getFrozenRegistry());
            var sparkle = new FX();
            sparkle.getFxData().objects().add(named(KilaGalleryScenario.showcase(KilaPresets.SPARKS), "Sparkle"));
            FXPackExporter.exportInto(packFile, PACK, "sparkle", sparkle, Platform.getFrozenRegistry());
            var ring = new FX();
            ring.getFxData().objects().add(named(KilaGalleryScenario.showcase(KilaPresets.SHOCKWAVE), "Shockwave"));
            FXPackExporter.exportInto(packFile, PACK, "shockwave", ring, Platform.getFrozenRegistry());
        } catch (IOException e) {
            throw new IllegalStateException("exporting the pack failed", e);
        }
    }

    private static void loadColliders(TestContext ctx) {
        var project = new FXProject();
        var objects = project.getFx().getFxData().objects();
        var sparks = new ParticleEmitter();
        sparks.setName("Sparks");
        var config = sparks.config;
        config.setLooping(true);
        config.setDuration(100);
        config.setStartLifetime(new RandomConstant(60, 90));
        config.setStartSpeed(new RandomConstant(2, 4));
        config.setStartSize(new NumberFunction3(0.12, 0.12, 0.12));
        config.setStartColor(NumberFunction.color(0xFFFFB050));
        config.setMaxParticles(800);
        var cone = new Cone();
        cone.setAngle(25);
        config.shape.setShape(cone);
        config.emission.setEmissionRate(NumberFunction.constant(10));
        var physics = config.physics;
        physics.setEnable(true);
        physics.setHasCollision(true);
        physics.setGravity(NumberFunction.constant(0.6f));
        physics.setBounceChance(NumberFunction.constant(1));
        physics.setBounceRate(NumberFunction.constant(0.5f));
        physics.collision.setKillOnTriggerEnter(true);
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(KilaPresets.SPARKS.create()));
        sparks.transform().localPosition(new Vector3f(0, 1.6f, 0));
        objects.add(sparks);

        var box = new ColliderObject();
        box.setName("Box");
        box.config.setShape(ColliderConfig.Shape.Box);
        box.transform().localPosition(new Vector3f(0.9f, -0.5f, 0.2f));
        objects.add(box);
        var sphere = new ColliderObject();
        sphere.setName("Trigger Sphere");
        sphere.config.setShape(ColliderConfig.Shape.Sphere);
        sphere.config.setRadius(0.7f);
        sphere.config.setTrigger(true);
        sphere.transform().localPosition(new Vector3f(-0.9f, 0.4f, 0));
        objects.add(sphere);

        CollisionDebug.enabled = true;
        open(ctx, project, new Vector3f(0.5f, 2.6f, 0.5f), 5.4, 0.6, 0.28);
        ctx.put("sparks", sparks);
        ctx.put("box", box);
        ctx.put("sphere", sphere);
    }

    private static void loadFox(TestContext ctx) {
        var project = new FXProject();
        var fox = new ParticleEmitter();
        fox.setName("Fox");
        var config = fox.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(0.018, 0.018, 0.018));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        var source = new AnimatedGltfModelSource(FOX);
        // with the phase on, every row of the source shows
        source.setPerParticlePhase(true);
        renderer.setModel(new MeshData(source));
        renderer.getMaterials().clear();
        renderer.getMaterials().add(new MaterialSetting(new TextureMaterial(
                ResourceLocation.parse("textures/block/orange_terracotta.png"))).setDepthMask(true).setCull(true));
        project.getFx().getFxData().objects().add(fox);
        open(ctx, project, new Vector3f(0, 1.9f, 0), 4.2, 0.9, 0.25);
        ctx.put("fox", fox);
    }

    private static void loadLight(TestContext ctx) {
        var project = new FXProject();
        var light = new LightObject();
        light.setName("Spot");
        light.config.setType(LightConfig.Type.Spot);
        light.config.setRange(NumberFunction.constant(6));
        light.config.setOuterAngle(35);
        light.config.setColor(new HDRConstantColor(new HDRColor(1f, 0.75f, 0.45f, 1f, 1f)));
        light.config.getVolumetric().setEnable(true);
        light.transform().localPosition(new Vector3f(0, 3, 0));
        var embers = new ParticleEmitter();
        embers.setName("Embers");
        embers.config.lightEmission.setEnable(true);
        embers.config.renderer.getMaterials().clear();
        embers.config.renderer.getMaterials().add(new MaterialSetting(KilaPresets.SPARKS.create()));
        project.getFx().getFxData().objects().add(light);
        project.getFx().getFxData().objects().add(embers);
        open(ctx, project, new Vector3f(0.5f, 2.5f, 0.5f), 9, 0.6, 0.45);
        ctx.put("light", light);
    }

    private static void loadKila(TestContext ctx) {
        var project = new FXProject();
        var fire = KilaGalleryScenario.showcase(KilaPresets.FIRE);
        fire.setName("Fire");
        fire.config.setStartSize(new NumberFunction3(2, 2, 2));
        project.getFx().getFxData().objects().add(fire);
        open(ctx, project, new Vector3f(0, 1.2f, 0), 5, 0.5, 0.2);
        ctx.put("kila", fire.config.renderer.getMaterials().getFirst().getMaterial());
    }

    private static void rightClickRow(ScenarioBuilder s, String name) {
        s.step("hover the " + name + " row", ctx -> {
            var view = editor(ctx).hierarchyView;
            var row = ctx.query().type(TextElement.class).withText(name).list().stream()
                    .map(ref -> ref.element()).filter(element -> isInside(element, view)).findFirst().orElse(null);
            ctx.require("a row named " + name, row != null);
            var bounds = ElementBounds.of(row);
            var at = ctx.put("clickAt", new float[]{bounds.x() + 12, bounds.centerY()});
            ctx.input().moveTo(at[0], at[1]);
        });
        // the menu offers what applies to the selection, so the row is selected first
        s.step("select it", ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseDown(at[0], at[1], Keys.MOUSE_LEFT);
        });
        s.step("release the selection", ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseUp(at[0], at[1], Keys.MOUSE_LEFT);
        });
        s.frames(2);
        s.step("press", ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseDown(at[0], at[1], Keys.MOUSE_RIGHT);
        });
        s.step("release", ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseUp(at[0], at[1], Keys.MOUSE_RIGHT);
        });
    }

    /** A click on an empty spot of the scene: an open menu closes when it loses the focus. */
    private static void dismissMenu(ScenarioBuilder s) {
        Function<TestContext, float[]> spot = ctx -> {
            var bounds = ElementBounds.of(editor(ctx).sceneView);
            return new float[]{bounds.x() + bounds.width() * 0.5f, bounds.y() + bounds.height() - 20};
        };
        s.step("press away from the menu", ctx -> {
            var at = ctx.put("awayAt", spot.apply(ctx));
            ctx.input().mouseDown(at[0], at[1], Keys.MOUSE_LEFT);
        });
        s.step("release away from the menu", ctx -> {
            var at = ctx.<float[]>get("awayAt");
            ctx.input().mouseUp(at[0], at[1], Keys.MOUSE_LEFT);
        });
        s.frames(3);
    }

    private static boolean isInside(UIElement element, UIElement ancestor) {
        for (var e = element; e != null; e = e.getParent()) {
            if (e == ancestor) return true;
        }
        return false;
    }

    private static void dialogShot(TestContext ctx, String label) {
        var dialogs = ctx.query().type(Dialog.class).list();
        ctx.require("a dialog is open", !dialogs.isEmpty());
        ctx.screenshotElement(label, new ElementRef(dialogs.getLast().as(Dialog.class).overlay, "dialog"));
    }

    private static void closeDialogs(TestContext ctx) {
        for (var dialog : ctx.query().type(Dialog.class).list()) {
            ((Dialog) dialog.element()).close();
        }
    }

    /** The menu the File tab opens, built the way a click on it builds it. */
    private static TreeBuilder.Menu fileMenuBuilder(FXEditor editor) {
        try {
            var createMenu = MenuTab.class.getDeclaredMethod("createMenu");
            createMenu.setAccessible(true);
            return (TreeBuilder.Menu) createMenu.invoke(editor.fileMenu);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void showFxPackContents(FXEditor editor, File packFile) {
        try {
            var show = FXFileActions.class.getDeclaredMethod("showFxPackContents", Editor.class, File.class);
            show.setAccessible(true);
            show.invoke(null, editor, packFile);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Screen lightConfigScreen() {
        var modConfig = ModConfigs.getModConfigs(Photon.MOD_ID).stream()
                .filter(config -> config.getType() == ModConfig.Type.CLIENT).findFirst().orElseThrow();
        var root = new ConfigurationScreen.ConfigurationSectionScreen(null, ModConfig.Type.CLIENT, modConfig,
                Component.translatable("photon.configuration.title"));
        var context = ConfigurationScreen.ConfigurationSectionScreen.Context.top(Photon.MOD_ID, root, modConfig, (c, k, e) -> e);
        var specs = (UnmodifiableConfig) PhotonConfig.CONFIG_SPEC.getSpec().get("dynamic_lights");
        var values = (UnmodifiableConfig) PhotonConfig.CONFIG_SPEC.getValues().get("dynamic_lights");
        return new ConfigurationScreen.ConfigurationSectionScreen(context, root, specs.valueMap(), "dynamic_lights",
                values.entrySet(), Component.translatable("photon.configuration.dynamic_lights"));
    }

    private static void deleteTree(File root) {
        if (!root.exists()) return;
        var children = root.listFiles();
        if (children != null) {
            for (var child : children) deleteTree(child);
        }
        if (!root.delete()) root.deleteOnExit();
    }
}
