package com.lowdragmc.photon.uitest;

import com.lowdragmc.kilagraph.rendertype.format.KGVertexFormat;
import com.lowdragmc.kilagraph.rendertype.nodes.channel.SplitNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentAlphaBlock;
import com.lowdragmc.kilagraph.rendertype.runtime.KGShaderResourceProvider;
import com.lowdragmc.kilagraph.test.gametest.KGGameTestHelpers;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomData;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialRequirements;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDissolve;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDriver;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaLayer;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaLayout;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaModule;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShaders;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.renderer.CpuParticleRecords;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.client.shadergraph.nodes.CustomDataNode;
import org.lwjgl.opengl.GL20;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KilaMaterial end to end: every variant compiles on every path with every declared uniform and sampler live,
 * custom data reaches the material on the CPU, instanced and beam paths, and a plain TextureMaterial pays nothing.
 */
@LDLRegisterClient(name = "kila_material", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaMaterialScenario implements UIScenario {

    private static final List<MaterialContext> CONTEXTS = List.of(MaterialContext.NORMAL,
            MaterialContext.PARTICLE_CPU_DATA, MaterialContext.PARTICLE_INSTANCE, MaterialContext.PARTICLE_MODEL_INSTANCE,
            MaterialContext.PARTICLE_MODEL_INSTANCE_TANGENT, MaterialContext.PARTICLE_MODEL_INSTANCE_VAT,
            MaterialContext.PARTICLE_MODEL_INSTANCE_VAT_TANGENT, MaterialContext.TRAIL_INSTANCE,
            MaterialContext.ARA_TRAIL_INSTANCE, MaterialContext.ARA_TRAIL_TUBE_INSTANCE, MaterialContext.BEAM_INSTANCE);

    private static final double SIZE = 2;
    private static final int MIN_DRAWN_PIXELS = 5_000;
    /** A dissolved quad may leave a few anti-aliased pixels, never a shape. */
    private static final int MAX_LEFT_PIXELS = 400;
    private static final Set<String> DREW = ConcurrentHashMap.newKeySet();

    private enum Path {CPU, INSTANCED, BEAM, TEXTURE_CPU}

    private record Round(String tag, Path path, float dissolve) {
    }

    private static final List<Round> ROUNDS = List.of(
            new Round("cpu_shown", Path.CPU, 0),
            new Round("cpu_gone", Path.CPU, 1),
            new Round("inst_shown", Path.INSTANCED, 0),
            new Round("inst_gone", Path.INSTANCED, 1),
            new Round("beam_shown", Path.BEAM, 0),
            new Round("beam_gone", Path.BEAM, 1),
            new Round("texture_cpu", Path.TEXTURE_CPU, 0));

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every module set compiles on every render path, with nothing declared unused", KilaMaterialScenario::compileMatrix)
                .step("a slot follows the material's render state unless set to custom", KilaMaterialScenario::renderState)
                .step("settings survive a save, disabled modules included", KilaMaterialScenario::roundTrip)
                .step("the inspectors build", KilaMaterialScenario::inspectors)
                .step("a shader graph reads custom data on the CPU path, trails and beams", KilaMaterialScenario::shaderGraphData)
                .step("missing data is reported and fixed in one click", KilaMaterialScenario::requirements)
                .step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                })
                .frames(4)
                .screenshot("empty");

        for (var round : ROUNDS) {
            s.step("start " + round.tag(), ctx -> start(ctx, round))
                    .frames(10)
                    .screenshot(round.tag())
                    .step("what drew " + round.tag(), ctx -> afterRound(ctx, round))
                    .step("stop " + round.tag(), KilaMaterialScenario::stop);
        }
        s.step("the pictures", KilaMaterialScenario::comparePictures);
        s.teardown("stop any effect", KilaMaterialScenario::stop);
    }

    // ---- compile matrix ----------------------------------------------------------------------------

    private static Map<String, KilaMaterial> configurations() {
        var configs = new LinkedHashMap<String, KilaMaterial>();
        var surface = new KilaMaterial();
        surface.main.setEnable(false);
        configs.put("surface only", surface);
        configs.put("main", new KilaMaterial());
        var flipbook = new KilaMaterial();
        flipbook.main.flipbook = true;
        flipbook.main.frameBlend = true;
        flipbook.main.frameSource = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMainTexture.FrameSource.DRIVER;
        flipbook.main.frame.bind(KilaDriver.Source.LIFE, 1);
        configs.put("flipbook + blend", flipbook);
        var probe = new KilaMaterial();
        for (int i = 1; i < probe.modules().size(); i++) {
            var material = new KilaMaterial();
            var module = material.modules().get(i);
            module.setEnable(true);
            configs.put("main + " + module.id(), material);
        }
        var dirTexture = new KilaMaterial();
        dirTexture.dissolve.setEnable(true);
        dirTexture.dissolve.direction = KilaDissolve.Direction.TEXTURE;
        configs.put("dissolve direction texture", dirTexture);
        var everything = new KilaMaterial();
        everything.modules().forEach(module -> module.setEnable(true));
        everything.main.flipbook = true;
        everything.main.randomRow = true;
        everything.cameraOffset = 0.2f;
        configs.put("everything", everything);
        var screen = new KilaMaterial();
        screen.main.texture.uvSource = KilaTexture.UvSource.SCREEN;
        screen.main.texture.polar = true;
        configs.put("screen space + polar", screen);
        var world = new KilaMaterial();
        world.main.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        world.mask1.setEnable(true);
        world.mask1.texture.uvSource = KilaTexture.UvSource.WORLD_XY;
        world.vertexOffset.setEnable(true);
        world.vertexOffset.texture.uvSource = KilaTexture.UvSource.WORLD_ZY;
        configs.put("world uv on fragment and vertex slots", world);
        var vertexWorldOnly = new KilaMaterial();
        vertexWorldOnly.vertexOffset.setEnable(true);
        vertexWorldOnly.vertexOffset.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        configs.put("world uv on the vertex slot only", vertexWorldOnly);
        var noise = new KilaMaterial();
        noise.dissolve.setEnable(true);
        noise.dissolve.texture.noise(KilaTexture.Noise.SIMPLE, 6);
        noise.mask1.setEnable(true);
        noise.mask1.texture.noise(KilaTexture.Noise.GRADIENT, 4);
        noise.mask2.setEnable(true);
        noise.mask2.texture.noise(KilaTexture.Noise.VORONOI, 5);
        noise.distortion.setEnable(true);
        noise.distortion.texture.noise(KilaTexture.Noise.GRADIENT, 3);
        noise.vertexOffset.setEnable(true);
        noise.vertexOffset.texture.noise(KilaTexture.Noise.SIMPLE, 2);
        noise.screenDistortion.setEnable(true);
        noise.screenDistortion.texture.noise(KilaTexture.Noise.VORONOI, 8);
        configs.put("procedural noise in every scalar slot", noise);
        var worldNoise = new KilaMaterial();
        worldNoise.dissolve.setEnable(true);
        worldNoise.dissolve.texture.noise(KilaTexture.Noise.GRADIENT, 2);
        worldNoise.dissolve.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        configs.put("procedural noise in world space", worldNoise);
        var flow = new KilaMaterial();
        flow.layer.setEnable(true);
        flow.mask1.setEnable(true);
        flow.mask1.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        flow.dissolve.setEnable(true);
        flow.distortion.setEnable(true);
        flow.distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        flow.distortion.mask1 = true;
        flow.distortion.texture.texture = KilaTextures.FLOW_SWIRL;
        configs.put("flow map on every target", flow);
        var dispersion = new KilaMaterial();
        dispersion.main.dispersion = true;
        dispersion.main.dispersionAmount.bind(KilaDriver.Source.LIFE, 0.05f);
        dispersion.main.flipbook = true;
        dispersion.main.frameBlend = true;
        dispersion.distortion.setEnable(true);
        dispersion.distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        configs.put("dispersion + flipbook + flow map", dispersion);
        var uv = new KilaMaterial();
        uv.uvEffects.setEnable(true);
        uv.uvEffects.waveAmplitude.bind(KilaDriver.Source.RANDOM, 0.1f);
        uv.pixelate.setEnable(true);
        configs.put("uv effects + pixelate", uv);
        var uvOnly = new KilaMaterial();
        uvOnly.main.setEnable(false);
        uvOnly.uvEffects.setEnable(true);
        configs.put("uv effects with no slot after them", uvOnly);
        var screenOnly = new KilaMaterial();
        screenOnly.main.setEnable(false);
        screenOnly.screenDistortion.setEnable(true);
        configs.put("screen distortion alone", screenOnly);
        var screenBare = new KilaMaterial();
        screenBare.screenDistortion.setEnable(true);
        screenBare.screenDistortion.keepForeground = false;
        configs.put("screen distortion without the foreground mask", screenBare);
        var contact = new KilaMaterial();
        contact.depth.setEnable(true);
        contact.depth.soft = false;
        contact.depth.contactWidth.bind(KilaDriver.Source.LIFE, 0.5f);
        contact.depth.contactStrength.bind(KilaDriver.Source.VERTEX_A, 1);
        contact.depth.measure = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDepth.Measure.SURFACE;
        contact.depth.contactGradient = true;
        contact.depth.contactPulses = 3;
        contact.depth.contactBreakup = 0.6f;
        configs.put("contact glow with every option", contact);
        var contactTexture = new KilaMaterial();
        contactTexture.depth.setEnable(true);
        contactTexture.depth.contactBreakup = 1;
        contactTexture.depth.contactNoise.noise = KilaTexture.Noise.TEXTURE;
        contactTexture.depth.contactNoise.uvSource = KilaTexture.UvSource.WORLD_XZ;
        contactTexture.screenDistortion.setEnable(true);
        configs.put("contact breakup from a world texture + screen distortion", contactTexture);
        var edges = new KilaMaterial();
        edges.dissolve.setEnable(true);
        edges.dissolve.edgeGradient = true;
        edges.cameraOffset = 0.5f;
        edges.vertexOffset.setEnable(true);
        configs.put("dissolve edge gradient + camera offset + vertex offset", edges);
        var randomSheet = new KilaMaterial();
        randomSheet.main.flipbook = true;
        randomSheet.main.frameBlend = true;
        randomSheet.main.randomRow = true;
        randomSheet.main.randomStart = true;
        configs.put("flipbook random row + start + blend", randomSheet);
        var randomDriven = new KilaMaterial();
        randomDriven.main.flipbook = true;
        randomDriven.main.randomRow = true;
        randomDriven.main.frameSource = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMainTexture.FrameSource.DRIVER;
        randomDriven.main.frame.bind(KilaDriver.Source.LIFE, 1);
        configs.put("flipbook random row driven by lifetime", randomDriven);
        var sphere = new KilaMaterial();
        sphere.shading.setEnable(true);
        sphere.fresnel.setEnable(true);
        sphere.matcap.setEnable(true);
        configs.put("shading sphere + fresnel + matcap", sphere);
        var bumped = new KilaMaterial();
        bumped.shading.setEnable(true);
        bumped.shading.bump = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading.Bump.HEIGHT;
        bumped.lit.setEnable(true);
        configs.put("shading height bump + lit", bumped);
        var heightTexture = new KilaMaterial();
        heightTexture.shading.setEnable(true);
        heightTexture.shading.normal = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading.Normal.MESH;
        heightTexture.shading.bump = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading.Bump.HEIGHT;
        heightTexture.shading.height.noise = KilaTexture.Noise.TEXTURE;
        heightTexture.shading.height.uvSource = KilaTexture.UvSource.WORLD_XZ;
        configs.put("shading mesh normal + height texture in world space", heightTexture);
        var sixWay = new KilaMaterial();
        sixWay.main.texture.texture = KilaTextures.SMOKE6_POS;
        sixWay.main.flipbook = true;
        sixWay.main.frameBlend = true;
        sixWay.shading.setEnable(true);
        sixWay.shading.bump = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading.Bump.NORMAL_MAP;
        sixWay.shading.model = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading.Model.SIX_WAY;
        sixWay.lit.setEnable(true);
        sixWay.distortion.setEnable(true);
        sixWay.distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        sixWay.main.dispersion = true;
        configs.put("six-way + normal map + lit + flow map + dispersion", sixWay);
        var sixWayAlone = new KilaMaterial();
        sixWayAlone.main.setEnable(false);
        sixWayAlone.shading.setEnable(true);
        sixWayAlone.shading.model = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading.Model.SIX_WAY;
        configs.put("six-way with no main texture", sixWayAlone);
        var motion = new KilaMaterial();
        motion.main.texture.texture = KilaTextures.SMOKE_FLIP;
        motion.main.flipbook = true;
        motion.main.frameBlend = true;
        motion.main.motionVectors = true;
        motion.main.randomStart = true;
        configs.put("flipbook motion vectors + random start", motion);
        var fog = new KilaMaterial();
        fog.volume.setEnable(true);
        fog.depth.setEnable(true);
        configs.put("volume clipped + depth", fog);
        var openFog = new KilaMaterial();
        openFog.volume.setEnable(true);
        openFog.volume.clip = false;
        configs.put("volume unclipped", openFog);
        var layers = new KilaMaterial();
        layers.layer.setEnable(true);
        layers.layer2.setEnable(true);
        layers.layer2.strength.bind(KilaDriver.Source.LIFE, 1);
        configs.put("two layers", layers);
        var glass = new KilaMaterial();
        glass.shading.setEnable(true);
        glass.screenDistortion.setEnable(true);
        glass.screenDistortion.source = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaScreenDistortion.Source.NORMAL;
        glass.screenDistortion.chromatic = 0.4f;
        configs.put("screen distortion by the normal + colour fringe", glass);
        var fringe = new KilaMaterial();
        fringe.screenDistortion.setEnable(true);
        fringe.screenDistortion.chromatic = 0.4f;
        fringe.screenDistortion.keepForeground = false;
        configs.put("screen distortion noise + colour fringe, no mask", fringe);
        var stack = new KilaMaterial();
        stack.screenDistortion.setEnable(true);
        stack.screenDistortion.stack = true;
        stack.screenDistortion.stacking = true;
        stack.dissolve.setEnable(true);
        configs.put("stacked screen distortion", stack);
        var stackNormal = new KilaMaterial();
        stackNormal.shading.setEnable(true);
        stackNormal.screenDistortion.setEnable(true);
        stackNormal.screenDistortion.source = com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaScreenDistortion.Source.NORMAL;
        stackNormal.screenDistortion.stacking = true;
        stackNormal.blend = BlendMode.Preset.PREMULTIPLIED;
        configs.put("stacked screen distortion by the normal, premultiplied", stackNormal);
        var drawn = new KilaMaterial();
        drawn.frameRate = 12;
        drawn.boil = 0.02f;
        drawn.uvEffects.setEnable(true);
        drawn.uvEffects.rippleAmplitude = 0.05f;
        drawn.vertexOffset.setEnable(true);
        configs.put("hand-drawn frame rate + boil + ripple + vertex offset", drawn);
        var drawnPlain = new KilaMaterial();
        drawnPlain.main.setEnable(false);
        drawnPlain.frameRate = 8;
        configs.put("hand-drawn frame rate with nothing animated", drawnPlain);
        for (var preset : KilaPresets.ALL) {
            configs.put("preset " + preset.id(), preset.create());
        }
        return configs;
    }

    private static void compileMatrix(TestContext ctx) {
        int before = KilaShaders.compiledCount();
        configurations().forEach((name, material) -> {
            var layout = material.layout();
            var problems = new ArrayList<String>();
            for (var context : CONTEXTS) {
                var path = context.getVariantKey().isEmpty() ? "(cpu)" : context.getVariantKey();
                var variant = KilaShaders.get(layout, context);
                if (variant == null) {
                    problems.add(path + ": did not compile");
                    continue;
                }
                var shader = variant.shader();
                for (var uniform : layout.uniforms) {
                    if (shader.getUniform(uniform.name()) == null) problems.add(path + ": uniform " + uniform.name() + " unused");
                }
                var live = shader.getShaderInstanceAccessor().getSamplerNames();
                for (var sampler : layout.samplers) {
                    if (!live.contains(sampler)) problems.add(path + ": sampler " + sampler + " unused");
                }
            }
            ctx.check("'%s' compiles on all %d paths, nothing unused".formatted(name, CONTEXTS.size()),
                    problems.isEmpty(), "no problems", problems.toString());
        });
        ctx.log("compiled " + (KilaShaders.compiledCount() - before) + " Kila variants");
        var all = configurations().get("everything");
        var everything = all.layout();
        // the scene colour and the lights' four buffers do not fit beside every texture
        ctx.check("with everything on, the default textures share samplers and only the last module is left out",
                everything.samplers.size() <= KilaLayout.SAMPLER_BUDGET && everything.dropped.equals(List.of(all.lit)),
                "<= " + KilaLayout.SAMPLER_BUDGET + ", lit dropped", everything.samplers + " dropped " + everything.dropped);
        var p1 = new KilaMaterial();
        p1.modules().forEach(module -> module.setEnable(true));
        p1.screenDistortion.setEnable(false);
        ctx.check("without the screen distortion only the third layer and the matcap give way",
                p1.layout().dropped.equals(List.of(p1.layer2, p1.matcap)), "layer2, matcap",
                p1.layout().dropped.stream().map(KilaModule::id).toList());
        var distinct = new KilaMaterial();
        distinct.modules().forEach(module -> module.setEnable(true));
        distinct.layer.texture.texture = KilaTextures.NOISE_PERLIN;
        distinct.mask2.texture.texture = KilaTextures.RING;
        distinct.dissolve.texture.texture = KilaTextures.NOISE_CELLS;
        distinct.vertexOffset.texture.texture = KilaTextures.GRADIENT_LINEAR;
        var over = distinct.layout();
        ctx.check("with distinct textures the budget still holds", over.samplers.size() <= KilaLayout.SAMPLER_BUDGET,
                "<= " + KilaLayout.SAMPLER_BUDGET, over.samplers.size());
        ctx.check("and the module that did not fit is reported", over.dropped.contains(distinct.lit),
                "lit dropped", over.dropped);
        ctx.check("which still compiles", KilaShaders.get(over, MaterialContext.NORMAL) != null, "compiled", "null");
    }

    // ---- framework checks --------------------------------------------------------------------------

    private static void renderState(TestContext ctx) {
        var kila = new KilaMaterial();
        kila.blend = BlendMode.Preset.ADDITIVE;
        var slot = new MaterialSetting(kila);
        ctx.check("a new slot follows the material", BlendMode.Preset.of(slot.getEffectiveBlendMode()) == BlendMode.Preset.ADDITIVE,
                BlendMode.Preset.ADDITIVE, BlendMode.Preset.of(slot.getEffectiveBlendMode()));
        kila.doubleSided = true;
        ctx.check("the material's culling is followed too", !slot.isEffectiveCull(), false, slot.isEffectiveCull());
        ctx.check("a plain slot draws once", slot.passes() == 1, 1, slot.passes());
        kila.backFacesFirst = true;
        ctx.check("back faces first draws twice, culled", slot.passes() == 2 && slot.isEffectiveCull(), "2, culled",
                slot.passes() + (slot.isEffectiveCull() ? ", culled" : ", not culled"));
        kila.doubleSided = false;
        ctx.check("only a double sided material does", slot.passes() == 1, 1, slot.passes());
        kila.doubleSided = true;
        kila.backFacesFirst = false;
        slot.setRenderStateSource(MaterialSetting.RenderStateSource.CUSTOM);
        ctx.check("a custom slot uses its own blend", BlendMode.Preset.of(slot.getEffectiveBlendMode()) == BlendMode.Preset.ALPHA,
                BlendMode.Preset.ALPHA, BlendMode.Preset.of(slot.getEffectiveBlendMode()));
        ctx.check("a custom slot uses its own culling", slot.isEffectiveCull(), true, slot.isEffectiveCull());

        var texture = new MaterialSetting(new TextureMaterial());
        texture.getBlendMode().setDstColorFactor(com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        ctx.check("a material without a render state keeps the slot's, as before",
                texture.getEffectiveBlendMode() == texture.getBlendMode(), "the slot's own", texture.getEffectiveBlendMode());
    }

    private static void roundTrip(TestContext ctx) {
        var material = KilaPresets.FIRE.create();
        material.main.texture.rotation = 33;
        material.dissolve.progress.bindCustom(1, 2, 0.5f);
        material.layer.blend = KilaLayer.Blend.SCREEN;
        material.layer.texture.texture = KilaTextures.SPARK;
        material.layer.setEnable(false);
        var tag = material.serializeWrapper();
        ctx.require("the material serialises", tag != null);
        var back = IMaterial.deserializeWrapper(tag);
        ctx.require("it comes back as a KilaMaterial", back instanceof KilaMaterial);
        var kila = (KilaMaterial) back;
        ctx.check("the variant is the same", kila.layout().key.equals(material.layout().key), material.layout().key, kila.layout().key);
        ctx.check("texture settings kept", kila.main.texture.rotation == 33, 33f, kila.main.texture.rotation);
        ctx.check("drivers kept", kila.dissolve.progress.code() == material.dissolve.progress.code()
                && kila.dissolve.progress.scale == 0.5f, material.dissolve.progress.code(), kila.dissolve.progress.code());
        ctx.check("a disabled module keeps its settings", !kila.layer.isEnable() && kila.layer.blend == KilaLayer.Blend.SCREEN
                && KilaTextures.SPARK.equals(kila.layer.texture.getTexture()), "disabled, SCREEN, spark", kila.layer.blend);
        ctx.check("blend preset kept", kila.blend == material.blend, material.blend, kila.blend);
        var copy = (KilaMaterial) material.copy();
        ctx.check("copy is deep", copy.main.texture != material.main.texture && copy.layout().key.equals(material.layout().key),
                "a separate texture object, same layout", copy.main.texture);
    }

    private static void inspectors(TestContext ctx) {
        Throwable failure = null;
        int rows = 0;
        try {
            var group = new ConfiguratorGroup();
            new KilaMaterial().buildConfigurator(group);
            rows = group.getConfigurators().size();
            new MaterialSetting(new KilaMaterial()).buildConfigurator(new ConfiguratorGroup());
            new MaterialSetting(new TextureMaterial()).buildConfigurator(new ConfiguratorGroup());
        } catch (Throwable e) {
            failure = e;
        }
        ctx.check("the KilaMaterial and slot inspectors build", failure == null, "no exception", String.valueOf(failure));
        ctx.check("one group per module, plus the fixed rows", rows >= new KilaMaterial().modules().size() + 4,
                ">= " + (new KilaMaterial().modules().size() + 4), rows);
    }

    /** The variants a graph reading custom data gets on each path must link the custom-data buffer in. */
    private static void shaderGraphData(TestContext ctx) {
        var graph = new ShaderGraph() {
            @Override
            protected void initializeDefaultEntityShader() {
            }
        };
        var custom = KGGameTestHelpers.addRegisteredNode(graph, CustomDataNode.class);
        var split = KGGameTestHelpers.addRegisteredNode(graph, SplitNode.class);
        KGGameTestHelpers.wire(graph, split.getInputsById().get("in"), custom.getOutputsById().get("out"));
        var alpha = KGGameTestHelpers.addBlock(graph, graph.getFragmentStageModel(), FragmentAlphaBlock.class);
        KGGameTestHelpers.wire(graph, alpha.getInputsById().get("alpha"), split.getOutputsById().get("r"));
        var compiled = new PhotonShaderCompiler(graph).compile();
        ctx.require("the graph compiles", compiled != null && !compiled.hasStageErrors());
        var format = KGVertexFormat.of(compiled.settings().vertexFormatElements());
        for (var context : List.of(MaterialContext.PARTICLE_CPU_DATA, MaterialContext.PARTICLE_INSTANCE,
                MaterialContext.TRAIL_INSTANCE, MaterialContext.ARA_TRAIL_INSTANCE, MaterialContext.BEAM_INSTANCE)) {
            var path = context.getVariantKey();
            var shader = KGShaderResourceProvider.createShaderInstance(compiled, format, context.getShaderDefines());
            ctx.check("the graph's " + path + " variant links", shader != null, "linked", "null");
            if (shader == null) continue;
            int location = GL20.glGetUniformLocation(shader.getId(), InstancedRenderBackendNames.CUSTOM);
            ctx.check(path + " reads the custom-data buffer", location >= 0, ">= 0", location);
            if (context == MaterialContext.PARTICLE_CPU_DATA) {
                int verts = GL20.glGetUniformLocation(shader.getId(), CpuParticleRecords.VERTS_UNIFORM);
                ctx.check("the CPU variant indexes its records by vertex", verts >= 0, ">= 0", verts);
            }
            shader.close();
        }
    }

    private static void requirements(TestContext ctx) {
        var beam = new BeamConfig();
        var material = new KilaMaterial();
        material.dissolve.setEnable(true);
        material.dissolve.progress.bindCustom(1, 2, 1);
        beam.renderer.getMaterials().clear();
        beam.renderer.getMaterials().add(new MaterialSetting(material));
        var materials = beam.renderer.getMaterials();
        var data = beam.additionalGPUDataSetting;
        var before = MaterialRequirements.describe(materials, data, false, beam.renderer::isUseGPUInstance);
        ctx.check("a beam missing stream 1 and instancing is reported", before != null, "a message", "nothing");
        MaterialRequirements.fix(materials, data, false, beam.renderer::isUseGPUInstance, beam.renderer::setUseGPUInstance);
        var streams = data.customDataStreams();
        ctx.check("fixing defines the stream with enough channels", data.isEnable() && streams.size() == 2
                && streams.get(1).getChannelCount() >= 3, "enabled, 2 streams, >= 3 channels",
                data.isEnable() + ", " + streams.size() + " streams");
        ctx.check("fixing turns instancing on for a beam", beam.renderer.isUseGPUInstance(), true, false);
        var after = MaterialRequirements.describe(materials, data, false, beam.renderer::isUseGPUInstance);
        ctx.check("and then nothing is missing", after == null, "nothing",
                after == null ? "nothing" : after.getString());

        var particle = new ParticleEmitter().config;
        var lifeDriven = new KilaMaterial();
        lifeDriven.dissolve.setEnable(true);
        lifeDriven.dissolve.progress.bind(KilaDriver.Source.LIFE, 1);
        particle.renderer.getMaterials().clear();
        particle.renderer.getMaterials().add(new MaterialSetting(lifeDriven));
        var lifetime = MaterialRequirements.describe(particle.renderer.getMaterials(), particle.additionalGPUDataSetting,
                true, particle.renderer::isUseGPUInstance);
        ctx.check("a particle emitter needs no instancing for lifetime — its CPU path carries it", lifetime == null,
                "nothing", lifetime == null ? "nothing" : lifetime.getString());

        // a particle emitter's own trails carry point data only instanced, like a trail emitter's
        var trails = particle.trails.config;
        trails.renderer.getMaterials().clear();
        trails.renderer.getMaterials().add(new MaterialSetting(KilaPresets.ENERGY_TRAIL.create()));
        var group = new ConfiguratorGroup();
        particle.trails.buildConfigurator(group);
        group.screenTick();
        ctx.check("the trails module shows what its material needs", requirementsRowShown(group), "shown", "none shown");
        var needs = MaterialRequirements.describe(trails.renderer.getMaterials(), trails.additionalGPUDataSetting, false,
                trails.renderer::isUseGPUInstance);
        ctx.check("the energy trail on particle trails asks for instancing", needs != null, "a message", "nothing");
        MaterialRequirements.fix(trails.renderer.getMaterials(), trails.additionalGPUDataSetting, false,
                trails.renderer::isUseGPUInstance, trails.renderer::setUseGPUInstance);
        ctx.check("and fixing it turns instancing on", trails.renderer.isUseGPUInstance(), true, false);
    }

    /** Searched through the elements: the trail type's rows sit in a selector, not a group. */
    private static boolean requirementsRowShown(ConfiguratorGroup group) {
        return group.selfAndAllChildren().anyMatch(element -> element instanceof Configurator row && row.isDisplayed()
                && row.label.getText().getContents() instanceof TranslatableContents key
                && key.getKey().equals("photon.material_requirements.title"));
    }

    /** The buffer sampler names, without reaching into the renderer package. */
    private static final class InstancedRenderBackendNames {
        static final String CUSTOM = "PhotonCustomData";
    }

    // ---- render rounds -----------------------------------------------------------------------------

    private static KilaMaterial dissolvingQuadMaterial() {
        var material = new RecordingKila();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(1f, 0.2f, 0.8f, 1f, 1f);
        material.worldLight = false;
        material.fog = false;
        material.dissolve.setEnable(true);
        material.dissolve.progress.value = 0;
        material.dissolve.progress.bindCustom(0, 0, 1);
        material.dissolve.edgeWidth.value = 0;
        material.dissolve.softness = 0.02f;
        return material;
    }

    private static CustomData stream(float value) {
        var stream = new CustomData();
        stream.getChannels().set(0, NumberFunction.constant(value));
        return stream;
    }

    private static void start(TestContext ctx, Round round) {
        stop(ctx);
        DREW.clear();
        ctx.put("uploadsAtStart", CpuParticleRecords.uploads());
        var player = ctx.requirePlayer();
        var ahead = player.getDirection();
        IFXObject object;
        if (round.path() == Path.BEAM) {
            var config = new BeamConfig();
            config.setDuration(200);
            config.setLooping(true);
            var side = ahead.getClockWise().getNormal();
            config.getEnd().set(side.getX() * 4f, side.getY() * 4f, side.getZ() * 4f);
            config.setWidth(NumberFunction.constant(1.5));
            config.renderer.setUseGPUInstance(true);
            config.renderer.getMaterials().clear();
            config.renderer.getMaterials().add(new MaterialSetting(dissolvingQuadMaterial()));
            config.additionalGPUDataSetting.setEnable(true);
            config.additionalGPUDataSetting.customDataStreams().add(stream(round.dissolve()));
            object = new BeamEmitter(config);
        } else {
            var emitter = new ParticleEmitter();
            var config = emitter.config;
            config.setLooping(true);
            config.setDuration(200);
            config.setStartLifetime(NumberFunction.constant(200));
            config.setStartSpeed(NumberFunction.constant(0));
            config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
            config.setMaxParticles(1);
            config.shape.setScale(new NumberFunction3(0, 0, 0));
            config.emission.setEmissionRate(NumberFunction.constant(0));
            var burst = new EmissionSetting.Burst();
            burst.time = 0;
            burst.setCount(NumberFunction.constant(1));
            burst.cycles = 1;
            config.emission.getBursts().add(burst);
            config.renderer.setUseGPUInstance(round.path() == Path.INSTANCED);
            config.renderer.getMaterials().clear();
            IMaterial material = round.path() == Path.TEXTURE_CPU
                    ? new RecordingTexture(ResourceLocation.parse("textures/block/white_concrete.png"))
                    : dissolvingQuadMaterial();
            config.renderer.getMaterials().add(new MaterialSetting(material));
            config.additionalGPUDataSetting.setEnable(true);
            config.additionalGPUDataSetting.customDataStreams().add(stream(round.dissolve()));
            object = emitter;
        }
        var fx = new FX();
        fx.getFxData().objects().add(object);
        var pos = player.blockPosition().relative(ahead, 6);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0, 1.5, 0);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        ctx.check("the effect started (" + round.tag() + ")", executor.getRuntime() != null, "a runtime", "null");
    }

    private static void afterRound(TestContext ctx, Round round) {
        var expected = switch (round.path()) {
            case CPU -> Set.of(MaterialContext.CPU_DATA_DEFINE);
            case INSTANCED -> Set.of("PARTICLE_INSTANCE");
            case BEAM -> Set.of("BEAM_INSTANCE");
            case TEXTURE_CPU -> Set.of("");
        };
        ctx.check("drew the %s variant and only that (%s)".formatted(describe(expected), round.tag()),
                DREW.equals(expected), describe(expected), describe(DREW));
        int uploads = CpuParticleRecords.uploads() - ctx.<Integer>get("uploadsAtStart");
        if (round.path() == Path.CPU) {
            ctx.check("the CPU pass uploaded per-particle records (" + round.tag() + ")", uploads > 0, "> 0", uploads);
        } else {
            ctx.check("no per-particle records were uploaded (" + round.tag() + ")", uploads == 0, 0, uploads);
        }
    }

    private static void comparePictures(TestContext ctx) {
        var empty = ScreenshotCompare.load(ctx, "empty");
        ctx.require("the empty capture exists", empty != null);
        var window = ctx.mc().getWindow();
        var region = new ScreenshotCompare.Region(window.getWidth() / 5, window.getHeight() / 8,
                window.getWidth() * 4 / 5, window.getHeight() * 7 / 8);
        ScreenshotCompare.Region shown = null;
        for (var round : ROUNDS) {
            var capture = ScreenshotCompare.load(ctx, round.tag());
            if (capture == null) {
                ctx.check("capture " + round.tag() + " was written", false, "an image", "missing");
                continue;
            }
            if (round.dissolve() < 0.5f) {
                var diff = capture.diff(empty, 24, region);
                ctx.check("%s is on screen".formatted(round.tag()), diff.count() > MIN_DRAWN_PIXELS,
                        "> " + MIN_DRAWN_PIXELS + " px", diff.count() + " px at " + diff.box());
                shown = diff.changed();
            } else {
                // only where the shown round drew: an effect another scenario left running elsewhere is not ours
                var diff = capture.diff(empty, 24, shown != null ? shown : region);
                ctx.check("%s is dissolved away".formatted(round.tag()), diff.count() < MAX_LEFT_PIXELS,
                        "< " + MAX_LEFT_PIXELS + " px", diff.count() + " px at " + diff.box());
            }
        }
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static String describe(Set<String> variants) {
        return variants.isEmpty() ? "(nothing drawn)"
                : variants.stream().map(v -> v.isEmpty() ? "(no defines)" : v).sorted().toList().toString();
    }

    private static final class RecordingKila extends KilaMaterial {
        @Override
        public ShaderInstance begin(MaterialContext context) {
            DREW.add(context.getVariantKey());
            return super.begin(context);
        }
    }

    private static final class RecordingTexture extends TextureMaterial {
        private RecordingTexture(ResourceLocation texture) {
            super(texture);
        }

        @Override
        public ShaderInstance getShader(MaterialContext context) {
            DREW.add(context.getVariantKey());
            return super.getShader(context);
        }
    }
}
