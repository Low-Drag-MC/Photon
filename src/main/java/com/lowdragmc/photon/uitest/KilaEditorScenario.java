package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.resource.FileResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceInstance;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static com.lowdragmc.photon.uitest.KilaUiTestKit.*;

/** KilaMaterial in the real editor: module list, presets, slot render state, requirements row, library conversion. */
@LDLRegisterClient(name = "kila_editor", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaEditorScenario implements UIScenario {

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "editor").requiresWorld(true).scenarioTimeoutMs(180_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        // before the editor opens: the library only picks up a global folder that exists when it loads
        s.step("write a texture material into the library", ctx -> writeFixture())
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("open an empty project", ctx -> editor(ctx).loadProject(FXProject.TYPE.newEmptyProject(), null))
                .frames(5)

                .step("inspect a Kila material", ctx -> {
                    var material = ctx.put("material", new KilaMaterial());
                    editor(ctx).inspectorView.inspect(material);
                })
                .frames(5)
                .screenshot("inspector")
                .step("only the enabled module is listed", ctx -> {
                    ctx.check("the main texture group is shown", label(ctx, "Main Texture") != null, "shown", "hidden");
                    ctx.check("the dissolve group is not", label(ctx, "Dissolve") == null, "hidden", "shown");
                    ctx.check("the status counts the samplers", labelStarting(ctx, "Samplers ") != null, "shown", "missing");
                });

        clickOn(s, "the add module button", ctx -> label(ctx, "+ Add Module..."));
        s.waitUntil("the module list offers Dissolve", ctx -> dialogLabel(ctx, "Dissolve") != null);
        clickOn(s, "Add beside Dissolve", ctx -> dialogRowButton(ctx, "Dissolve"));
        s.frames(5)
                .screenshot("dissolve_added")
                .step("the dissolve module is on, listed, expanded, its toggle on", ctx -> {
                    var material = ctx.<KilaMaterial>get("material");
                    ctx.check("the module is enabled", material.dissolve.isEnable(), true, false);
                    var group = groupOf(label(ctx, "Dissolve"));
                    ctx.require("its group is shown", group != null);
                    ctx.check("the group is expanded", !group.isCollapse(), "expanded", "collapsed");
                    ctx.check("the header toggle says on", headerToggle(group) != null && headerToggle(group).getValue(),
                            true, headerToggle(group) == null ? "no toggle" : headerToggle(group).getValue());
                    ctx.check("its rows are there", label(ctx, "Progress") != null, "a Progress row", "missing");
                });
        clickOn(s, "the dissolve header toggle", ctx -> headerToggle(groupOf(label(ctx, "Dissolve"))));
        s.frames(5)
                .step("switching it off hides the group again", ctx -> {
                    ctx.check("the module is disabled", !ctx.<KilaMaterial>get("material").dissolve.isEnable(), false, true);
                    ctx.check("the group is gone", label(ctx, "Dissolve") == null, "hidden", "shown");
                });

        clickOn(s, "the preset button", ctx -> label(ctx, "Browse Presets..."));
        s.waitUntil("the preset gallery is open", ctx -> dialogLabel(ctx, "Fire") != null);
        clickOn(s, "Fire in the gallery", ctx -> dialogLabel(ctx, "Fire"));
        s.frames(5)
                .screenshot("fire_preset")
                .step("the preset applied, and its modules are listed and switched on", ctx -> {
                    var material = ctx.<KilaMaterial>get("material");
                    ctx.check("blend is additive", material.blend == BlendMode.Preset.ADDITIVE, BlendMode.Preset.ADDITIVE, material.blend);
                    for (var name : new String[]{"Dissolve", "UV Distortion", "Color Ramp"}) {
                        var group = groupOf(label(ctx, name));
                        ctx.check(name + " is listed", group != null, "shown", "hidden");
                        if (group == null) continue;
                        var toggle = headerToggle(group);
                        ctx.check(name + "'s toggle says on", toggle != null && toggle.getValue(), true,
                                toggle == null ? "no toggle" : toggle.getValue());
                    }
                })

                .step("inspect an emitter whose slot holds the material", ctx -> {
                    var emitter = new ParticleEmitter();
                    emitter.config.renderer.getMaterials().clear();
                    emitter.config.renderer.getMaterials().add(new MaterialSetting(ctx.<KilaMaterial>get("material")));
                    ctx.put("emitter", emitter);
                    editor(ctx).inspectorView.inspect(emitter);
                })
                .frames(5)
                .step("unfold the renderer", ctx -> {
                    var renderer = groupOf(label(ctx, "Renderer"));
                    ctx.require("the emitter has a renderer group", renderer != null);
                    renderer.setCollapse(false);
                })
                .frames(5)
                .screenshot("slot_follows")
                .step("the slot shows the state it follows instead of its own rows", ctx -> {
                    ctx.check("the followed state is shown", labelStarting(ctx, "Render state from the material: Additive") != null,
                            "shown", "missing");
                    ctx.check("the slot's own blend rows are hidden", label(ctx, "Blend Mode") == null, "hidden", "shown");
                })
                .step("set the slot to custom", ctx -> ctx.<ParticleEmitter>get("emitter").config.renderer.getMaterials()
                        .getFirst().setRenderStateSource(MaterialSetting.RenderStateSource.CUSTOM))
                .frames(5)
                .step("custom brings the slot's rows back", ctx -> {
                    ctx.check("the slot's blend rows are shown", label(ctx, "Blend Mode") != null, "shown", "hidden");
                    ctx.check("the followed state is not", labelStarting(ctx, "Render state from the material") == null,
                            "hidden", "shown");
                    ctx.check("the blend preset row is shown", label(ctx, "Blend") != null, "shown", "hidden");
                });

        clickOn(s, "the slot's blend preset", ctx -> {
            var row = label(ctx, "Blend");
            var selector = row == null ? null : row.getFirstAncestorOfType(SelectorConfigurator.class);
            return selector == null ? null : selector.selector;
        });
        s.waitUntil("the dropdown offers Additive", ctx -> selectorEntry(ctx, "Additive") != null);
        clickOn(s, "Additive in the dropdown", ctx -> selectorEntry(ctx, "Additive"));
        s.frames(5)
                .screenshot("slot_blend_preset")
                .step("the slot blends additively now", ctx -> {
                    var slot = ctx.<ParticleEmitter>get("emitter").config.renderer.getMaterials().getFirst();
                    ctx.check("the slot's blend is the additive preset",
                            BlendMode.Preset.of(slot.getBlendMode()) == BlendMode.Preset.ADDITIVE,
                            BlendMode.Preset.ADDITIVE, BlendMode.Preset.of(slot.getBlendMode()));
                    ctx.check("and it is what the slot draws with", BlendMode.Preset.of(slot.getEffectiveBlendMode())
                            == BlendMode.Preset.ADDITIVE, BlendMode.Preset.ADDITIVE, BlendMode.Preset.of(slot.getEffectiveBlendMode()));
                })

                .step("inspect a beam whose material reads a stream it does not define", ctx -> {
                    var config = new BeamConfig();
                    var material = new KilaMaterial();
                    material.dissolve.setEnable(true);
                    material.dissolve.progress.bindCustom(0, 1, 1);
                    config.renderer.getMaterials().clear();
                    config.renderer.getMaterials().add(new MaterialSetting(material));
                    ctx.put("beam", config);
                    editor(ctx).inspectorView.inspect(new BeamEmitter(config));
                })
                .frames(5)
                .screenshot("requirements")
                .step("the requirement is reported", ctx -> ctx.check("the missing stream is named",
                        labelStarting(ctx, "A material reads Custom Data stream 0") != null, "shown", "missing"));
        clickOn(s, "the fix button", ctx -> label(ctx, "Fix"));
        s.frames(5)
                .step("fixed in one click", ctx -> {
                    var config = ctx.<BeamConfig>get("beam");
                    var streams = config.additionalGPUDataSetting.customDataStreams();
                    ctx.check("the stream exists with two channels", config.additionalGPUDataSetting.isEnable()
                            && streams.size() == 1 && streams.getFirst().getChannelCount() >= 2, "1 stream, >= 2 channels",
                            streams.size() + " streams");
                    ctx.check("instancing is on, so the beam carries it", config.renderer.isUseGPUInstance(), true, false);
                    ctx.check("and the row is gone", labelStarting(ctx, "A material reads") == null, "hidden", "shown");
                })

                .waitUntil("the library has a file-backed provider", ctx -> fixtureEntry() != null)
                .step("show the material panel", ctx -> {
                    editor(ctx).resourceView.selectResourceInstance(MaterialResource.INSTANCE);
                    var container = ctx.query().type(ResourceContainer.class).list().stream()
                            .map(ref -> (ResourceContainer<?>) ref.as(ResourceContainer.class))
                            .findFirst().orElseThrow(() -> new IllegalStateException("the material panel is not on screen"));
                    showFileProvider(container);
                })
                .waitUntil("the fixture is loaded", ctx -> fixturePath() != null && fixtureContainer(ctx) != null)
                .step("select it", ctx -> Objects.requireNonNull(fixtureContainer(ctx)).locateResource(fixturePath(), false))
                .frames(5)
                .step("right-click the selected tile", ctx -> {
                    var cell = selectedCell(ctx);
                    ctx.require("the selected tile is shown", cell != null);
                    var at = ctx.put("clickAt", centreOf(cell));
                    ctx.input().moveTo(at[0], at[1]);
                    ctx.input().mouseDown(at[0], at[1], Keys.MOUSE_RIGHT);
                })
                .step("release the right button", ctx -> {
                    var at = ctx.<float[]>get("clickAt");
                    ctx.input().mouseUp(at[0], at[1], Keys.MOUSE_RIGHT);
                })
                .waitUntil("the menu offers the conversion", ctx -> menuEntry(ctx, "Convert to Kila Material") != null);
        clickOn(s, "Convert to Kila Material", ctx -> menuEntry(ctx, "Convert to Kila Material"));
        s.waitUntil("it asks first", ctx -> confirmButton(ctx) != null)
                .step("nothing is converted before the answer", ctx -> ctx.check("still a texture material",
                        MaterialResource.INSTANCE.getResourceInstance().getResource(fixturePath()) instanceof TextureMaterial,
                        "TextureMaterial", "converted already"));
        clickOn(s, "the confirm button", KilaUiTestKit::confirmButton);
        s.frames(5)
                .screenshot("converted")
                .step("the library holds a KilaMaterial that keeps the slots' render state", ctx -> {
                    var material = MaterialResource.INSTANCE.getResourceInstance().getResource(fixturePath());
                    ctx.require("it is a KilaMaterial now", material instanceof KilaMaterial);
                    var kila = (KilaMaterial) material;
                    ctx.check("its main texture is the old texture", Photon.id("textures/particle/smoke.png")
                            .equals(kila.main.texture.getTexture()), "smoke", kila.main.texture.getTexture());
                    ctx.check("blend, cull and depth stay with each slot", kila.slotRenderState
                            && kila.getPreferredRenderState() == null, "the slot's", kila.getPreferredRenderState());
                    ctx.check("the inspector shows it", label(ctx, "Main Texture") != null, "shown", "missing");
                    var saved = readFixture();
                    ctx.check("and it is what the file holds", saved instanceof KilaMaterial, "KilaMaterial",
                            saved == null ? "nothing" : saved.getClass().getSimpleName());
                })
                .teardown("close the editor", ctx -> ctx.mc().setScreen(null))
                .teardown("delete the fixture", ctx -> {
                    //noinspection ResultOfMethodCallIgnored
                    fixtureFile().delete();
                });
    }

    private static File fixtureFile() {
        return new File(LDLib2.getAssetsDir(), "ldlib2/resources/global/uitest_kila_convert"
                + MaterialResource.INSTANCE.getFileExtension());
    }

    private static void writeFixture() {
        var material = new TextureMaterial(Photon.id("textures/particle/smoke.png"));
        var nbt = new CompoundTag();
        nbt.put("data", Objects.requireNonNull(MaterialResource.INSTANCE.serializeResource(material, Platform.getFrozenRegistry())));
        nbt.putString("type", MaterialResource.INSTANCE.getName());
        try {
            //noinspection ResultOfMethodCallIgnored
            fixtureFile().getParentFile().mkdirs();
            NbtIo.write(nbt, fixtureFile().toPath());
        } catch (IOException e) {
            throw new IllegalStateException("could not write the material fixture", e);
        }
    }

    @Nullable
    private static IMaterial readFixture() {
        try {
            var nbt = NbtIo.read(fixtureFile().toPath());
            return nbt == null ? null : MaterialResource.INSTANCE.deserializeResource(nbt.get("data"), Platform.getFrozenRegistry());
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static ResourceInstance.ResourceEntry<IMaterial> fixtureEntry() {
        var name = fixtureFile().getName();
        return MaterialResource.INSTANCE.getResourceInstance().listAllResourceEntries().stream()
                .filter(entry -> entry.path() instanceof FilePath file && file.file.getName().equals(name))
                .findFirst().orElse(null);
    }

    @Nullable
    private static IResourcePath fixturePath() {
        var entry = fixtureEntry();
        return entry == null ? null : entry.path();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void showFileProvider(ResourceContainer<?> container) {
        var provider = MaterialResource.INSTANCE.getResourceInstance().listAllResourceEntries().stream()
                .map(ResourceInstance.ResourceEntry::provider)
                .filter(FileResourceProvider.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no file-backed material provider is loaded"));
        ((ResourceContainer) container).selectProvider(provider);
    }

    @Nullable
    private static ResourceProviderContainer<?> fixtureContainer(TestContext ctx) {
        var entry = fixtureEntry();
        if (entry == null) return null;
        return ctx.query().type(ResourceProviderContainer.class).list().stream()
                .map(ref -> (ResourceProviderContainer<?>) ref.as(ResourceProviderContainer.class))
                .filter(container -> container.resourceProvider == entry.provider() && shown(container))
                .findFirst().orElse(null);
    }

    @Nullable
    private static UIElement selectedCell(TestContext ctx) {
        var container = fixtureContainer(ctx);
        if (container == null) return null;
        return container.selfAndAllChildren().filter(e -> e.hasClass("__selected__")).findFirst().orElse(null);
    }
}
