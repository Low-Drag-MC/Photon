package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.browser.AssetBrowser;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDriver;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextureField;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Objects;

import static com.lowdragmc.photon.uitest.KilaUiTestKit.*;

/**
 * The KilaMaterial inspector captured section by section, then driven by real clicks: the texture picker,
 * binding a value to particle data, the preset gallery and the module list.
 */
@LDLRegisterClient(name = "kila_inspector", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaInspectorScenario implements UIScenario {

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "editor").requiresWorld(true).scenarioTimeoutMs(180_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("open an empty project", ctx -> editor(ctx).loadProject(FXProject.TYPE.newEmptyProject(), null))
                .frames(5)
                .step("inspect a material with a few modules on", ctx -> {
                    var material = KilaPresets.FIRE.create();
                    material.mask1.setEnable(true);
                    material.uvEffects.setEnable(true);
                    material.dissolve.texture.noise(KilaTexture.Noise.GRADIENT, 5);
                    ctx.put("material", material);
                    editor(ctx).inspectorView.inspect(material);
                })
                .frames(8)
                .screenshot("insp_top")
                .step("no corner preview while the preview is in sight", ctx -> ctx.check("no corner preview",
                        !cornerShown(ctx), "hidden", "shown"));
        capture(s, "Main Texture", "insp_main");
        capture(s, "Dissolve", "insp_dissolve");
        capture(s, "Color Ramp", "insp_ramp");
        s.step("the corner preview follows the scroll", ctx -> ctx.check("a corner preview is shown",
                cornerShown(ctx), "shown", "hidden"));

        clickOn(s, "the main texture's preview", ctx -> mainField(ctx).preview);
        s.waitUntil("the picker is open", ctx -> dialogLabel(ctx, "Ring") != null)
                .frames(3)
                .screenshot("picker");
        clickOn(s, "the ring in the picker", ctx -> dialogLabel(ctx, "Ring"));
        s.frames(2)
                .step("the slot shows the ring at once", ctx -> ctx.check("the main texture is the ring",
                        KilaTextures.RING.equals(material(ctx).main.texture.getTexture()), KilaTextures.RING,
                        material(ctx).main.texture.getTexture()));
        clickOn(s, "confirm", ctx -> dialogButton(ctx, "__confirm-button__"));
        s.waitUntil("the picker closed", ctx -> dialogLabel(ctx, "Ring") == null)
                .step("a .png from the asset browser drops onto a slot", ctx -> {
                    var spark = new File("../../src/main/resources/assets/photon/textures/kila/spark.png");
                    var location = KilaTextureField.locationOf(new AssetBrowser.DraggedAssets(List.of(spark)));
                    ctx.check("the dropped file becomes its texture", KilaTextures.SPARK.equals(location),
                            KilaTextures.SPARK, location);
                });

        capture(s, "Dissolve", "insp_dissolve_again");
        clickOn(s, "the link beside Progress", ctx -> linkOf(label(ctx, "Progress")));
        s.waitUntil("the source menu is open", ctx -> menuEntry(ctx, "Particle Lifetime") != null);
        clickOn(s, "Particle Lifetime", ctx -> menuEntry(ctx, "Particle Lifetime"));
        s.frames(3)
                .screenshot("driver_bound")
                .step("progress follows the lifetime, and says so", ctx -> {
                    var progress = material(ctx).dissolve.progress;
                    ctx.check("bound to the lifetime", progress.source == KilaDriver.Source.LIFE,
                            KilaDriver.Source.LIFE, progress.source);
                    ctx.check("the binding rows are shown", label(ctx, "Driven By") != null, "shown", "missing");
                });

        clickOn(s, "the presets button", ctx -> label(ctx, "Browse Presets..."));
        s.waitUntil("the gallery is open", ctx -> dialogLabel(ctx, "Lightning") != null)
                .frames(5)
                .screenshot("presets");
        clickOn(s, "Lightning in the gallery", ctx -> dialogLabel(ctx, "Lightning"));
        s.frames(3)
                .step("the preset is applied", ctx -> {
                    var material = material(ctx);
                    ctx.check("the lightning texture", KilaTextures.NOISE_CELLS.equals(material.main.texture.getTexture()),
                            KilaTextures.NOISE_CELLS, material.main.texture.getTexture());
                    ctx.check("additive", material.blend == BlendMode.Preset.ADDITIVE, BlendMode.Preset.ADDITIVE,
                            material.blend);
                });

        clickOn(s, "the add module button", ctx -> label(ctx, "+ Add Module..."));
        s.waitUntil("the module list is open", ctx -> dialogLabel(ctx, "Fresnel") != null)
                .frames(3)
                .screenshot("modules");
        clickOn(s, "Add beside Fresnel", ctx -> dialogRowButton(ctx, "Fresnel"));
        s.frames(3)
                .step("fresnel is on and listed", ctx -> {
                    ctx.check("enabled", material(ctx).fresnel.isEnable(), true, false);
                    ctx.check("its group is shown", dialogLabel(ctx, "Fresnel") == null && label(ctx, "Fresnel") != null,
                            "shown", "hidden");
                });

        // a tour of every module, for a person to look over
        s.step("inspect a material with every module on", ctx -> {
            var material = new KilaMaterial();
            material.modules().forEach(module -> module.setEnable(true));
            ctx.put("tour", material);
            editor(ctx).inspectorView.inspect(material);
        }).frames(8);
        s.step("open every group", KilaUiTestKit::openEveryGroup)
                .frames(6)
                .step("no checkbox is pushed out of its row", ctx -> {
                    var hidden = hiddenCheckboxes(ctx);
                    ctx.check("every checkbox is inside its row", hidden.isEmpty(), "none", hidden.toString());
                });
        for (var module : new KilaMaterial().modules()) {
            var name = net.minecraft.network.chat.Component.translatable(module.langKey()).getString();
            capture(s, name, "tour_" + module.id());
        }
        s.teardown("close the editor", ctx -> ctx.mc().setScreen(null));
    }

    private static void capture(ScenarioBuilder s, String group, String shot) {
        s.step("scroll to " + group, ctx -> {
            var label = label(ctx, group);
            ctx.require(group + " is in the inspector", label != null);
            scrollToTop(Objects.requireNonNull(groupOf(label)));
        }).frames(4).screenshot(shot);
    }

    private static boolean cornerShown(TestContext ctx) {
        return ctx.requireUI().ui.rootElement.selfAndAllChildren()
                .anyMatch(e -> KilaMaterial.PREVIEW_CORNER_ID.equals(e.getId()) && shown(e));
    }

    private static KilaMaterial material(TestContext ctx) {
        return ctx.get("material");
    }

    private static KilaTextureField mainField(TestContext ctx) {
        var texture = material(ctx).main.texture;
        return ctx.query().type(KilaTextureField.class).list().stream()
                .map(ref -> ref.as(KilaTextureField.class))
                .filter(field -> field.texture() == texture)
                .findFirst().orElseThrow(() -> new IllegalStateException("no field shows the main texture"));
    }

    @Nullable
    private static UIElement dialogButton(TestContext ctx, String type) {
        return ctx.requireUI().ui.rootElement.selfAndAllChildren()
                .filter(e -> e instanceof Button && e.hasClass(type) && e.getFirstAncestorOfType(Dialog.class) != null
                        && shown(e))
                .findFirst().orElse(null);
    }

    /** The link button in a value row's line. */
    @Nullable
    private static UIElement linkOf(@Nullable UIElement label) {
        if (label == null) return null;
        var line = label.getParent();
        if (line == null) return null;
        return line.selfAndAllChildren().filter(e -> e instanceof Button).reduce((a, b) -> b).orElse(null);
    }
}
