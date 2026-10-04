package com.lowdragmc.photon.uitest;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightEmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ValueSpace;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.SpriteMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.light.LightConfig;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.client.light.ShadowMode;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The artist-facing half of the dynamic lights: the Light object in the add menu, the new inspector rows,
 * their lang keys, a save/load round trip, and the light's gizmo in the editor scene.
 */
@LDLRegisterClient(name = "dynamic_light_editor", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightEditorScenario implements UIScenario {
    private static final String LIGHT_SECTION = "dynamic_lights";
    private static final List<String> LANG_KEYS = List.of(
            "light",
            "LightConfig.type", "LightConfig.lifetime", "LightConfig.looping", "LightConfig.color",
            "LightConfig.intensity", "LightConfig.range", "LightConfig.innerAngle", "LightConfig.outerAngle",
            "LightConfig.sourceRadius", "LightConfig.castShadows", "LightConfig.flicker", "LightConfig.flickerSpeed",
            "ParticleConfig.lightEmission", "photon.emitter.config.lightEmission",
            "LightEmissionSetting.ratio", "LightEmissionSetting.maxLights", "LightEmissionSetting.useParticleColor",
            "LightEmissionSetting.color", "LightEmissionSetting.intensity", "LightEmissionSetting.range",
            "LightEmissionSetting.alphaAffectsIntensity", "LightEmissionSetting.sizeAffectsRange",
            "LightEmissionSetting.castShadows", "LightEmissionSetting.sourceRadius",
            "LightEmissionSetting.spot", "photon.emitter.config.lightEmission.spot",
            "LightEmissionSetting.spot.innerAngle", "LightEmissionSetting.spot.outerAngle",
            "LightEmissionSetting.spot.alignToVelocity", "LightEmissionSetting.spot.direction",
            "LightEmissionSetting.spot.space",
            "TextureMaterial.litParticles", "TextureMaterial.litParticles.intensity");

    @Override
    public void define(ScenarioBuilder s) {
        s.step("the add menu offers a Light", ctx -> {
            boolean listed = false;
            for (var type : PhotonRegistries.FX_OBJECTS) listed |= type == LightObject.TYPE;
            ctx.check("FX_OBJECTS lists the light type", listed, "listed", "missing");
            ctx.check("its registry name", "light".equals(LightObject.TYPE.name()), "light", LightObject.TYPE.name());
            ctx.check("the type creates a LightObject", LightObject.TYPE.create() instanceof LightObject,
                    "LightObject", LightObject.TYPE.create().getClass().getSimpleName());
        })
        .step("every new lang key resolves", ctx -> {
            for (var key : LANG_KEYS) {
                var text = I18n.get(key);
                ctx.check("lang: " + key, !text.equals(key), "a translation", text);
            }
        })
        .step("the inspectors build and carry the new rows", ctx -> {
            rows(ctx, "light", new LightObject(), List.of("LightConfig.type", "LightConfig.intensity",
                    "LightConfig.outerAngle", "LightConfig.sourceRadius", "LightConfig.flicker"));
            rows(ctx, "particle emitter", new ParticleEmitter(), List.of("ParticleConfig.lightEmission"));
            rows(ctx, "light emission", new LightEmissionSetting(), List.of("LightEmissionSetting.spot",
                    "LightEmissionSetting.spot.outerAngle", "LightEmissionSetting.spot.direction",
                    "LightEmissionSetting.spot.space"));
            rows(ctx, "texture material", new TextureMaterial(), List.of("TextureMaterial.litParticles"));
            rows(ctx, "sprite material", new SpriteMaterial(), List.of("TextureMaterial.litParticles"));
        })
        .step("light, emission and lit settings survive save and load", DynamicLightEditorScenario::roundTrip)

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
        .step("load a spot light over an emitter with light emission", DynamicLightEditorScenario::loadProject)
        .frames(20)
        .step("select the light", ctx -> select(ctx, ctx.get("light")))
        .frames(10)
        .screenshot("editor_spot_light")
        .step("make it a point light", ctx -> ctx.<LightObject>get("light").config.setType(LightConfig.Type.Point))
        .frames(5)
        .screenshot("editor_point_light")
        .step("select the emitter", ctx -> select(ctx, ctx.get("emitter")))
        .frames(10)
        .screenshot("editor_emitter")
        .step("inspect its light emission module", ctx -> editor(ctx).inspectorView
                .inspect(ctx.<ParticleEmitter>get("emitter").config.lightEmission))
        .frames(10)
        .screenshot("editor_light_emission")
        .step("inspect its lit material", ctx -> editor(ctx).inspectorView.inspect(ctx.<TextureMaterial>get("material")))
        .frames(10)
        .screenshot("editor_lit_material")
        .step("every light config option has a name and a tooltip", DynamicLightEditorScenario::configLang)
        .step("open the dynamic lights config", ctx -> ctx.mc().setScreen(lightConfigScreen()))
        .frames(10)
        .screenshot("config_dynamic_lights");

        // ⚠️ The editor window outlives the scenario: a project left open turns the next scenario's
        // loadProject into a modal "open in a new window?" dialog, and that load silently never happens.
        s.teardown("close the project", ctx -> {
            FXEditor editor = ctx.get("editor");
            if (editor != null) editor.closeCurrentProject(false, null);
        });
    }

    /** Walks the spec, so an option added later without lang fails here. */
    private static void configLang(TestContext ctx) {
        var section = (UnmodifiableConfig) PhotonConfig.CONFIG_SPEC.getSpec().get(LIGHT_SECTION);
        ctx.check("section name", I18n.exists("photon.configuration." + LIGHT_SECTION), "a translation", LIGHT_SECTION);
        for (var entry : section.entrySet()) {
            if (!(entry.getValue() instanceof ModConfigSpec.ValueSpec spec)) continue;
            var key = spec.getTranslationKey();
            ctx.check("name of " + entry.getKey(), key != null && I18n.exists(key), "a translation", key);
            ctx.check("tooltip of " + entry.getKey(), key != null && I18n.exists(key + ".tooltip"), "a translation", key + ".tooltip");
        }
        for (var mode : ShadowMode.values()) {
            var name = mode.getTranslatedName().getString();
            ctx.check("shadow mode " + mode, !name.startsWith("photon."), "a translation", name);
        }
    }

    private static Screen lightConfigScreen() {
        var modConfig = ModConfigs.getModConfigs(Photon.MOD_ID).stream()
                .filter(config -> config.getType() == ModConfig.Type.CLIENT).findFirst().orElseThrow();
        var root = new ConfigurationScreen.ConfigurationSectionScreen(null, ModConfig.Type.CLIENT, modConfig,
                Component.translatable("photon.configuration.title"));
        var context = ConfigurationScreen.ConfigurationSectionScreen.Context.top(Photon.MOD_ID, root, modConfig, (c, k, e) -> e);
        var specs = (UnmodifiableConfig) PhotonConfig.CONFIG_SPEC.getSpec().get(LIGHT_SECTION);
        var values = (UnmodifiableConfig) PhotonConfig.CONFIG_SPEC.getValues().get(LIGHT_SECTION);
        return new ConfigurationScreen.ConfigurationSectionScreen(context, root, specs.valueMap(), LIGHT_SECTION,
                values.entrySet(), Component.translatable("photon.configuration." + LIGHT_SECTION));
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static void rows(TestContext ctx, String what, IConfigurable configurable, List<String> keys) {
        var group = new ConfiguratorGroup("test", false);
        Throwable thrown = null;
        try {
            configurable.buildConfigurator(group);
        } catch (Throwable e) {
            thrown = e;
        }
        ctx.check(what + ": buildConfigurator did not throw", thrown == null, "built", String.valueOf(thrown));
        var labels = new ArrayList<String>();
        collectLabels(group, labels);
        for (var key : keys) {
            var text = I18n.get(key);
            ctx.check(what + " shows " + key, labels.contains(text), text, labels.size() + " rows");
        }
    }

    private static void collectLabels(UIElement element, List<String> out) {
        if (element instanceof Configurator configurator) out.add(configurator.label.getText().getString());
        for (var child : element.getChildren()) collectLabels(child, out);
    }

    private static void roundTrip(TestContext ctx) {
        var light = new LightObject();
        light.config.setType(LightConfig.Type.Spot);
        light.config.setOuterAngle(50);
        light.config.setFlicker(0.4f);
        light.config.setCastShadows(false);
        var loadedLight = IFXObject.deserializeWrapper(light.serializeWrapper());
        ctx.check("the light reloads as a LightObject", loadedLight instanceof LightObject, "LightObject",
                String.valueOf(loadedLight));
        if (loadedLight instanceof LightObject l) {
            ctx.check("type", l.config.getType() == LightConfig.Type.Spot, "Spot", l.config.getType());
            ctx.check("outer angle", l.config.getOuterAngle() == 50, 50, l.config.getOuterAngle());
            ctx.check("flicker", l.config.getFlicker() == 0.4f, 0.4f, l.config.getFlicker());
            ctx.check("cast shadows", !l.config.isCastShadows(), false, l.config.isCastShadows());
        }

        var emitter = new ParticleEmitter();
        emitter.config.lightEmission.setEnable(true);
        emitter.config.lightEmission.setMaxLights(7);
        var spot = emitter.config.lightEmission.getSpot();
        spot.setEnable(true);
        spot.setOuterAngle(50);
        spot.setAlignToVelocity(false);
        spot.setDirection(new Vector3f(1, 0, 0));
        spot.setSpace(ValueSpace.World);
        var material = new TextureMaterial();
        material.getLitParticles().setEnable(true);
        material.getLitParticles().intensity = 3;
        emitter.config.renderer.getMaterials().clear();
        emitter.config.renderer.getMaterials().add(new MaterialSetting(material));
        var loadedEmitter = IFXObject.deserializeWrapper(emitter.serializeWrapper());
        ctx.check("the emitter reloads", loadedEmitter instanceof ParticleEmitter, "ParticleEmitter",
                String.valueOf(loadedEmitter));
        if (loadedEmitter instanceof ParticleEmitter e) {
            var emission = e.config.lightEmission;
            ctx.check("light emission on", emission.isEnable(), true, emission.isEnable());
            ctx.check("max lights", emission.getMaxLights() == 7, 7, emission.getMaxLights());
            var loadedSpot = emission.getSpot();
            ctx.check("spot on", loadedSpot.isEnable(), true, loadedSpot.isEnable());
            ctx.check("spot outer angle", loadedSpot.getOuterAngle() == 50, 50, loadedSpot.getOuterAngle());
            ctx.check("spot ignores velocity", !loadedSpot.isAlignToVelocity(), false, loadedSpot.isAlignToVelocity());
            ctx.check("spot direction", loadedSpot.getDirection().equals(1, 0, 0), "(1, 0, 0)", loadedSpot.getDirection());
            ctx.check("spot space", loadedSpot.getSpace() == ValueSpace.World, ValueSpace.World, loadedSpot.getSpace());
            var loadedMaterial = e.config.renderer.getMaterials().getFirst().getMaterial();
            ctx.check("lit material on", loadedMaterial instanceof TextureMaterial t && t.getLitParticles().isEnable(),
                    true, loadedMaterial);
            ctx.check("lit intensity", loadedMaterial instanceof TextureMaterial t && t.getLitParticles().intensity == 3,
                    3, loadedMaterial instanceof TextureMaterial t ? t.getLitParticles().intensity : "n/a");
        }
    }

    private static void loadProject(TestContext ctx) {
        var light = new LightObject();
        light.setName("Spot");
        light.config.setType(LightConfig.Type.Spot);
        light.config.setLooping(true);
        light.config.setRange(NumberFunction.constant(5));
        light.config.setOuterAngle(35);
        light.transform().localPosition(new Vector3f(0, 3, 0));

        var emitter = new ParticleEmitter();
        emitter.setName("Embers");
        emitter.config.lightEmission.setEnable(true);
        emitter.config.lightEmission.getSpot().setEnable(true);
        var material = new TextureMaterial();
        material.getLitParticles().setEnable(true);
        emitter.config.renderer.getMaterials().clear();
        emitter.config.renderer.getMaterials().add(new MaterialSetting(material));

        var project = new FXProject();
        project.getFx().getFxData().objects().add(light);
        project.getFx().getFxData().objects().add(emitter);
        editor(ctx).loadProject(project, null);
        ctx.put("editor", editor(ctx));
        ctx.put("light", light);
        ctx.put("emitter", emitter);
        ctx.put("material", material);
        var renderer = editor(ctx).sceneView.sceneEditor.scene.getRenderer();
        if (renderer != null) renderer.setCameraLookAt(new Vector3f(0.5f, 3f, 0.5f), 11, 0.6, 0.45);
    }

    /** What a click on the hierarchy row does: inspector, gizmo target, and the scene's per-object overlay. */
    private static void select(TestContext ctx, IFXObject object) {
        var editor = editor(ctx);
        editor.inspectorView.inspect(object, null, null);
        editor.sceneView.fxObjectInfoView.inspect(object);
        editor.sceneView.sceneEditor.setTransformGizmoTarget(object.transform());
    }
}
