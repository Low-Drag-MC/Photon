package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.settings.AppearanceSettings;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;
import com.lowdragmc.lowdraglib2.gui.ui.window.WindowBounds;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.KilaGraphExport;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Scene colour sampled by an FX in the editor, docked and popped out into its own OS window. A popped-out editor
 * draws into an off-screen target sized to that window, while vanilla binds {@code ScreenSize} from the game window,
 * so {@code gl_FragCoord / ScreenSize} read the wrong texel there. An undistorted haze shows exactly what is behind
 * it, so it must vanish in both hosts — as KilaMaterial (sampling by {@code textureSize}) and as its exported
 * shader graph (sampling by {@code ScreenSize}).
 */
@LDLRegisterClient(name = "editor_os_window_scene", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EditorOsWindowSceneScenario implements UIScenario {
    /** Low over the checkered platform, seen from above, so what the haze shows has detail to get wrong. */
    private static final Vector3f CENTRE = new Vector3f(0.5f, 0.6f, 0.5f);
    /** Not the game window's 16:9, so a window-sized ScreenSize cannot line up by accident. */
    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 900;
    private static final int MIN_DRAWN_PIXELS = 3_000;
    private static final String GRAPH_FILE = "uitest_os_window_haze";

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("photon editor", ctx -> {
                    var window = EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new);
                    ctx.put("editorWindow", window);
                    return new ModularUI(UI.of(window.setId("fx_editor")))
                            .shouldCloseOnEsc(false)
                            .shouldCloseOnKeyInventory(false);
                })
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("remember the editor's window settings", ctx -> {
                    var editor = ctx.query().type(FXEditor.class).one().as(FXEditor.class);
                    ctx.put("editor", editor);
                    AppearanceSettings.of(editor).ifPresent(appearance -> {
                        ctx.put("preferOsWindow", appearance.isPreferOsWindow());
                        var bounds = appearance.getEditorWindowBounds();
                        ctx.put("bounds", bounds == null
                                ? new WindowBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0) : bounds);
                    });
                });

        capture(s, "docked", null);

        s.step("pop the editor out into its own window", ctx -> {
                    EditorWindow window = ctx.get("editorWindow");
                    ctx.require("the editor opened in an OS window", window.popOutToOsWindow());
                    var host = EditorWindow.osWindowOf(FXEditor.WINDOW_ID);
                    ctx.require("the OS window is registered", host != null && host.window() != null);
                    host.window().setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
                })
                .frames(30)
                .step("the window draws at its own size", ctx -> {
                    var surface = windowSurface();
                    ctx.require("the OS window has a surface", surface != null);
                    var target = surface.target();
                    var game = ctx.mc().getWindow();
                    ctx.check("its target is not the game window's size",
                            target.width != game.getWidth() || target.height != game.getHeight(),
                            "!= " + game.getWidth() + "x" + game.getHeight(), target.width + "x" + target.height);
                });

        capture(s, "window", ctx -> windowSurface());

        s.step("compare the captures", ctx -> {
            compare(ctx, "docked");
            compare(ctx, "window");
        });

        s.teardown("dock back and restore the settings", ctx -> {
            EditorWindow window = ctx.get("editorWindow");
            if (window != null && window.isInOsWindow()) window.dockIntoGameWindow();
            FXEditor editor = ctx.get("editor");
            if (editor != null) {
                editor.closeCurrentProject(false, null);
                AppearanceSettings.of(editor).ifPresent(appearance -> {
                    Boolean prefer = ctx.get("preferOsWindow");
                    WindowBounds bounds = ctx.get("bounds");
                    if (prefer != null) appearance.setPreferOsWindow(prefer);
                    if (bounds != null) appearance.setEditorWindowBounds(bounds);
                });
            }
            var file = graphFile();
            ShaderGraphRuntime.invalidate(new FilePath(file));
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        });
    }

    private static void capture(ScenarioBuilder s, String host, @Nullable Function<TestContext, UISurface> source) {
        round(s, host, "base", null, source);
        round(s, host, "solid", EditorOsWindowSceneScenario::solid, source);
        round(s, host, "haze", EditorOsWindowSceneScenario::haze, source);
        round(s, host, "graph", EditorOsWindowSceneScenario::hazeGraph, source);
    }

    private static void round(ScenarioBuilder s, String host, String name,
                              @Nullable Supplier<IMaterial> material, @Nullable Function<TestContext, UISurface> source) {
        var label = "osw_" + host + "_" + name;
        s.step("load " + label, ctx -> load(ctx, material))
                .frames(20)
                .step("pause " + label, ctx -> {
                    var scene = editor(ctx).sceneView;
                    scene.particleManager.pause();
                    scene.simulateTo(10);
                    // in pixels now: popping out changes the game's gui scale before the comparison runs
                    var element = scene.sceneEditor;
                    double scale = UISurface.of(element.getModularUI()).guiScale();
                    ctx.put("scene_" + host, new ScreenshotCompare.Region(
                            (int) (element.getPositionX() * scale), (int) (element.getPositionY() * scale),
                            (int) ((element.getPositionX() + element.getSizeWidth()) * scale),
                            (int) ((element.getPositionY() + element.getSizeHeight()) * scale)));
                })
                .frames(10);
        if (source == null) {
            s.screenshot(label);
        } else {
            s.screenshotSurface(label, source);
        }
    }

    private static void load(TestContext ctx, @Nullable Supplier<IMaterial> material) {
        var project = new FXProject();
        if (material != null) {
            var emitter = new ParticleEmitter();
            var config = emitter.config;
            config.setLooping(true);
            config.setDuration(200);
            config.setStartLifetime(NumberFunction.constant(200));
            config.setStartSpeed(NumberFunction.constant(0));
            config.setStartSize(new NumberFunction3(3, 3, 3));
            config.setMaxParticles(1);
            config.shape.setScale(new NumberFunction3(0, 0, 0));
            config.emission.setEmissionRate(NumberFunction.constant(0));
            var burst = new EmissionSetting.Burst();
            burst.time = 0;
            burst.setCount(NumberFunction.constant(1));
            burst.cycles = 1;
            config.emission.getBursts().add(burst);
            config.renderer.getMaterials().clear();
            config.renderer.getMaterials().add(new MaterialSetting(material.get()));
            emitter.transform().localPosition(new Vector3f(CENTRE));
            project.getFx().getFxData().objects().add(emitter);
        }
        var editor = editor(ctx);
        // with a project open, loading asks whether to open a new window instead
        editor.closeCurrentProject(false, null);
        editor.loadProject(project, null);
        var renderer = editor.sceneView.sceneEditor.scene.<WorldSceneRenderer>getRenderer();
        if (renderer != null) renderer.setCameraLookAt(new Vector3f(CENTRE), 9, 1.2, 1.0);
    }

    private static IMaterial solid() {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(1f, 0.4f, 0.05f, 1f, 1f);
        return material;
    }

    /** Opaque, and at strength 0 nothing but the scene behind it. */
    private static IMaterial haze() {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        material.screenDistortion.setEnable(true);
        material.screenDistortion.texture.noise(KilaTexture.Noise.GRADIENT, 3);
        material.screenDistortion.strength.value = 0;
        return material;
    }

    private static IMaterial hazeGraph() {
        var result = KilaGraphExport.export((KilaMaterial) haze());
        var file = graphFile();
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        var tag = new CompoundTag();
        tag.put("data", ShaderGraphResource.INSTANCE.serializeGraph(result.graph()));
        tag.putString("type", ShaderGraphResource.INSTANCE.getName());
        try {
            NbtIo.write(tag, file.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
        ShaderGraphRuntime.invalidate(new FilePath(file));
        var material = new ShaderGraphMaterial(new FilePath(file));
        material.setRenderState(result.renderState());
        return material;
    }

    private static File graphFile() {
        return new File(LDLib2.getAssetsDir(), "ldlib2/resources/global/" + GRAPH_FILE
                + ShaderGraphResource.INSTANCE.getFileExtension());
    }

    private static KilaMaterial unlit() {
        var material = new KilaMaterial();
        material.worldLight = false;
        material.fog = false;
        return material;
    }

    private static void compare(TestContext ctx, String host) {
        var base = ScreenshotCompare.load(ctx, "osw_" + host + "_base");
        var solid = ScreenshotCompare.load(ctx, "osw_" + host + "_solid");
        ctx.require("the " + host + " captures exist", base != null && solid != null);
        ScreenshotCompare.Region scene = ctx.get("scene_" + host);
        var located = solid.diff(base, 60, scene);
        ctx.check("the solid quad is on screen (" + host + ")",
                located.changed() != null && located.count() > MIN_DRAWN_PIXELS,
                "> " + MIN_DRAWN_PIXELS + " px", located.count() + " px at " + located.box());
        if (located.changed() == null) return;
        // the top-left of the quad: the scene's stats box, whose timings tick, sits bottom right
        var quad = located.changed();
        int width = quad.right() - quad.left();
        int height = quad.bottom() - quad.top();
        var inside = new ScreenshotCompare.Region(quad.left() + 4, quad.top() + 4,
                quad.left() + width * 2 / 5, quad.top() + height * 2 / 5);
        int area = Math.max(1, (inside.right() - inside.left()) * (inside.bottom() - inside.top()));
        for (var name : new String[]{"haze", "graph"}) {
            var capture = ScreenshotCompare.load(ctx, "osw_" + host + "_" + name);
            ctx.require("the " + host + " " + name + " capture exists", capture != null);
            var shown = capture.diff(base, 12, inside);
            ctx.check("an undistorted " + name + " haze cannot be told from no haze (" + host + ")",
                    shown.count() < area / 100, "< " + area / 100 + " px", shown.count() + " px at " + shown.box());
        }
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.get("editor");
    }

    @Nullable
    private static UISurface windowSurface() {
        var host = EditorWindow.osWindowOf(FXEditor.WINDOW_ID);
        return host == null ? null : host.surface();
    }
}
