package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.browser.FileOps;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;

/**
 * A material in a folder no resource provider owns, opened from the asset browser and edited, keeps the edit.
 *
 * <p>Reported from the wild: every change to such a material snapped back at once. The browser's own provider
 * saves its copy, while the inspector was given the copy the {@code ResourceInstance} reads separately for an
 * unowned file, so each save wrote the untouched one back over the edit.
 */
@LDLRegisterClient(name = "material_browser_edit", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class MaterialBrowserEditScenario implements UIScenario {
    private static final ResourceLocation BEFORE = Photon.id("textures/particle/circle.png");
    private static final ResourceLocation AFTER = Photon.id("textures/particle/smoke.png");

    private static File fixtureDir() {
        return new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/uitest_material_edit");
    }

    private static File fixtureFile() {
        return new File(fixtureDir(), "edited" + MaterialResource.INSTANCE.getFileExtension());
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "editor", "assets").requiresWorld(true).scenarioTimeoutMs(180_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("write a material into a folder of its own", ctx -> {
            FileOps.deleteRecursively(fixtureDir());
            ctx.require("the fixture directory was created", fixtureDir().mkdirs());
            var nbt = new CompoundTag();
            nbt.put("data", MaterialResource.INSTANCE.serializeResource(new TextureMaterial(BEFORE), Platform.getFrozenRegistry()));
            nbt.putString("type", MaterialResource.INSTANCE.getName());
            try {
                NbtIo.write(nbt, fixtureFile().toPath());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            // what an effect referencing it does: resolve it through the library
            ctx.require("the library resolves the file", textureOf(MaterialResource.INSTANCE.getResourceInstance()
                    .getResource(new FilePath(fixtureFile()))) != null);
        })

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
        .step("open an empty project", ctx -> editor(ctx).loadProject(FXProject.TYPE.newEmptyProject(), null))
        .waitUntil("the project's resource types are loaded", ctx ->
                editor(ctx).resourceView.getResources().containsKey(MaterialResource.INSTANCE))

        .step("browse the fixture folder", ctx ->
                editor(ctx).resourceView.getAssetBrowser().openDirectory(fixtureDir()))
        .waitUntil("the browser moved", ctx ->
                fixtureDir().equals(editor(ctx).resourceView.getAssetBrowser().getCurrentDirectory()))
        .frames(10)

        .step("double click the material", ctx -> editor(ctx).resourceView.getAssetBrowser().activate(fixtureFile()))
        .waitUntil("the inspector shows it", ctx ->
                editor(ctx).inspectorView.inspector.getInspectedConfigurable() instanceof TextureMaterial)
        .step("change its texture", ctx -> {
            var material = (TextureMaterial) editor(ctx).inspectorView.inspector.getInspectedConfigurable();
            material.setTexture(AFTER);
            var row = editor(ctx).inspectorView.inspector.selfAndAllChildren()
                    .filter(Configurator.class::isInstance).map(Configurator.class::cast).findFirst().orElse(null);
            ctx.require("the inspector has a row to report the change", row != null);
            row.notifyChanges();
        })
        // the browser flushes its dirty resources on its tick
        .frames(20)

        .step("the edit stuck", ctx -> {
            var inspected = editor(ctx).inspectorView.inspector.getInspectedConfigurable();
            ctx.check("the inspector still shows the edit", AFTER.equals(textureOf(inspected)),
                    AFTER, textureOf(inspected));
            var saved = readFixture();
            ctx.check("the file holds the edit", AFTER.equals(saved), AFTER, saved);
            var resolved = textureOf(MaterialResource.INSTANCE.getResourceInstance()
                    .getResource(new FilePath(fixtureFile())));
            ctx.check("an effect referencing the file draws the edit", AFTER.equals(resolved), AFTER, resolved);
        })

        .teardown("close the editor", ctx -> ctx.mc().setScreen(null))
        .teardown("delete the fixture folder",
                ctx -> ctx.check("the fixture was removed", FileOps.deleteRecursively(fixtureDir())));
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static ResourceLocation textureOf(Object material) {
        return material instanceof TextureMaterial texture ? texture.getTexture() : null;
    }

    private static ResourceLocation readFixture() {
        try {
            var nbt = NbtIo.read(fixtureFile().toPath());
            if (nbt == null) return null;
            return textureOf(MaterialResource.INSTANCE.deserializeResource(nbt.get("data"), Platform.getFrozenRegistry()));
        } catch (IOException e) {
            return null;
        }
    }
}
