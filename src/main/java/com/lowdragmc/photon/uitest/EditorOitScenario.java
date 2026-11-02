package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.PhotonOit;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;

/**
 * The FX editor's scene with improved transparency: a green effect inside a shell of red stained glass goes through the
 * scene's OIT phases and shows through the glass in front of it, tinted, where classic blending lets the glass hide it.
 * An HDR one is resolved by Photon, for its bloom.
 */
@LDLRegisterClient(name = "photon_editor_oit", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EditorOitScenario implements UIScenario {
    private static final BlockPos CENTRE = new BlockPos(0, 3, 0);

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("shaders off, Photon's OIT on", ctx -> {
                    // Iris turns improved transparency off while its shaders are enabled
                    if (IrisCompat.isModInstalled()) LightTestConfig.selectShaderPack(null);
                    LightTestConfig.set(PhotonConfig.INSTANCE.orderIndependentTransparency, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.enableBloom, true);
                })
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false).shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("a green effect in a shell of red glass", ctx -> load(ctx, false))
                .frames(20);
        for (boolean oit : new boolean[]{false, true}) {
            var tag = oit ? "oit" : "classic";
            s.step("improved transparency " + (oit ? "on" : "off"),
                            ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(), oit))
                    .frames(20)
                    .screenshot(tag)
                    .step("the scene is drawn " + (oit ? "with" : "without") + " the OIT phases", ctx -> {
                        ctx.check(tag + ": the scene's OIT phases", renderer(ctx).isDrawingWithOit() == oit);
                        ctx.check(tag + ": the effect " + (oit ? "joined" : "stayed out of") + " them",
                                (PhotonOit.drawsLastFrame() > 0) == oit, oit ? "> 0" : "0", PhotonOit.drawsLastFrame());
                    });
        }
        s.step("the effect shows through the glass only with the OIT phases", ctx -> {
                    var classic = ScreenshotCompare.load(ctx, "classic");
                    var oit = ScreenshotCompare.load(ctx, "oit");
                    ctx.require("both shots exist", classic != null && oit != null);
                    var region = centre(ctx);
                    var a = classic.mean(region);
                    var b = oit.mean(region);
                    var values = "classic %.0f %.0f %.0f, oit %.0f %.0f %.0f".formatted(a[0], a[1], a[2], b[0], b[1], b[2]);
                    ctx.check("classic: the glass hides it", a[1] < a[0], "red over green", values);
                    ctx.check("oit: it shows through the glass", b[1] > b[0] + 40, "green over red", values);
                    ctx.check("oit: the glass tints it", b[1] < 230 && b[0] > b[2] + 15, "dimmed, red over blue", values);
                })
                .step("an HDR effect", ctx -> load(ctx, true))
                .frames(20)
                .step("Photon resolves the scene's sum, for its bloom", ctx -> ctx.check(
                        "the composite is Photon's", PhotonOit.drawsLastFrame() > 0 && PhotonOit.compositedLastFrame()))
                .teardown("close the editor", ctx -> {
                    var editor = ctx.<FXEditor>get("editor");
                    if (editor != null) editor.closeCurrentProject(false, null);
                    ctx.mc().gui.setScreen(null);
                    LightTestConfig.restore();
                });
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static WorldSceneRenderer renderer(TestContext ctx) {
        return editor(ctx).sceneView.sceneEditor.scene.getRenderer();
    }

    /** Around the middle of the scene view, where the camera looks at the effect. */
    private static ScreenshotCompare.Region centre(TestContext ctx) {
        var scene = editor(ctx).sceneView.sceneEditor;
        double scale = ctx.mc().getWindow().getGuiScale();
        int cx = (int) ((scene.getPositionX() + scene.getSizeWidth() / 2) * scale);
        int cy = (int) ((scene.getPositionY() + scene.getSizeHeight() / 2) * scale);
        int r = (int) (scene.getSizeHeight() * scale * 0.03);
        return new ScreenshotCompare.Region(cx - r, cy - r, cx + r, cy + r);
    }

    private static void load(TestContext ctx, boolean hdr) {
        var emitter = new ParticleEmitter();
        emitter.setName("Green");
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(2, 2, 2));
        config.setStartColor(NumberFunction.color(0xFF00FF00));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        var material = new TextureMaterial(Photon.id("textures/particle/white.png"));
        if (hdr) {
            material.setHdr(new HDRColor(0f, 3f, 0f, 1f, 1f));
        }
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
        var centre = new Vector3f(CENTRE.getX() + 0.5f, CENTRE.getY() + 0.5f, CENTRE.getZ() + 0.5f);
        emitter.transform().localPosition(new Vector3f(centre));

        var project = new FXProject();
        project.getFx().getFxData().objects().add(emitter);
        var editor = editor(ctx);
        editor.loadProject(project, null);
        ctx.put("editor", editor);

        var view = editor.sceneView;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) == 2) {
                        view.level.setBlockAndUpdate(CENTRE.offset(x, y, z), Blocks.STAINED_GLASS.red().defaultBlockState());
                    }
                }
            }
        }
        view.sceneEditor.scene.setRenderedCore(view.level.getFilledBlocks().longStream().mapToObj(BlockPos::of).toList());
        var renderer = renderer(ctx);
        ctx.require("the scene has a renderer", renderer != null);
        renderer.setCameraLookAt(centre, 9, 1.0, 0.6);
    }
}
