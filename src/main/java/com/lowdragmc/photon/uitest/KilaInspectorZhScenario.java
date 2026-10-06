package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.network.chat.Component;

import java.util.Objects;

import static com.lowdragmc.photon.uitest.KilaUiTestKit.*;

/**
 * The KilaMaterial inspector in Chinese, captured module by module for a person to look over: the labels
 * there are a different length, and the inspector is narrow. Switches the game language and back.
 */
@LDLRegisterClient(name = "kila_inspector_zh", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaInspectorZhScenario implements UIScenario {

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "editor").requiresWorld(true).scenarioTimeoutMs(240_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("switch to Chinese", ctx -> language(ctx, "zh_cn"))
                .waitUntil("the resources reloaded", ctx -> ctx.mc().getOverlay() == null)
                .frames(5)
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("open an empty project", ctx -> editor(ctx).loadProject(FXProject.TYPE.newEmptyProject(), null))
                .frames(5)
                .step("inspect the fire preset", ctx -> editor(ctx).inspectorView.inspect(KilaPresets.FIRE.create()))
                .frames(8)
                .screenshot("zh_top")
                .step("inspect a material with every module on", ctx -> {
                    var material = new KilaMaterial();
                    material.modules().forEach(module -> module.setEnable(true));
                    editor(ctx).inspectorView.inspect(material);
                })
                .frames(8);
        s.step("open every group", KilaUiTestKit::openEveryGroup)
                .frames(6)
                .step("no checkbox is pushed out of its row", ctx -> {
                    var hidden = hiddenCheckboxes(ctx);
                    ctx.check("every checkbox is inside its row", hidden.isEmpty(), "none", hidden.toString());
                });
        for (var module : new KilaMaterial().modules()) {
            s.step("scroll to " + module.id(), ctx -> {
                var label = label(ctx, Component.translatable(module.langKey()).getString());
                ctx.require(module.id() + " is in the inspector", label != null);
                scrollToTop(Objects.requireNonNull(groupOf(label)));
            }).frames(4).screenshot("zh_" + module.id());
        }
        s.teardown("close the editor", ctx -> ctx.mc().setScreen(null))
                .teardown("back to English", ctx -> language(ctx, "en_us"));
    }

    private static void language(TestContext ctx, String code) {
        var mc = ctx.mc();
        mc.options.languageCode = code;
        mc.getLanguageManager().setSelected(code);
        mc.reloadResourcePacks();
    }
}
