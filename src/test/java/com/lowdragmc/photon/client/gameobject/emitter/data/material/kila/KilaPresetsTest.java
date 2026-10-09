package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** The presets double as examples: between them every module and the main options are used, and each one fits. */
class KilaPresetsTest {
    private static List<KilaMaterial> PRESETS;

    @BeforeAll
    static void createPresets() {
        // IMaterial's codec reads the registry the client builds at start-up
        if (PhotonRegistries.MATERIALS == null) {
            PhotonRegistries.MATERIALS = AutoRegistry.LDLibRegisterClient.create(Photon.id("material"), IMaterial.class,
                    AutoRegistry::noArgsCreator);
        }
        PRESETS = KilaPresets.ALL.stream().map(KilaPresets.Preset::create).toList();
    }

    private static boolean any(Predicate<KilaMaterial> test) {
        return PRESETS.stream().anyMatch(test);
    }

    private static List<KilaTexture> textures(KilaMaterial material) {
        var out = new ArrayList<KilaTexture>();
        for (var module : material.modules()) {
            if (module.isEnable()) module.textures(out::add);
        }
        return out;
    }

    /** Whether the module reads any slot as procedural noise: a layer, say, always samples its image. */
    private static boolean readsNoise(KilaModule module) {
        return KilaLayout.surface().build(List.of(module)).defines.stream().anyMatch(define -> define.endsWith("_NOISE"));
    }

    private static Set<KilaDriver.Source> sources(KilaMaterial material) {
        var out = EnumSet.noneOf(KilaDriver.Source.class);
        out.add(material.alpha.source);
        out.add(material.intensity.source);
        for (var module : material.modules()) {
            if (module.isEnable()) module.drivers((param, driver) -> out.add(driver.source));
        }
        return out;
    }

    @Test
    void idsAreUniqueAndEveryCategoryHasOne() {
        var ids = new HashSet<String>();
        for (var preset : KilaPresets.ALL) assertTrue(ids.add(preset.id()), "duplicate " + preset.id());
        for (var category : KilaPresets.Category.values()) {
            assertTrue(KilaPresets.ALL.stream().anyMatch(p -> p.category() == category), "no preset in " + category);
        }
    }

    @Test
    void everyPresetFitsTheSamplerBudget() {
        for (int i = 0; i < PRESETS.size(); i++) {
            var material = PRESETS.get(i);
            for (boolean stacking : new boolean[]{false, true}) {
                material.screenDistortion.stacking = stacking && material.screenDistortion.stack;
                var dropped = material.layout().dropped;
                assertTrue(dropped.isEmpty(), KilaPresets.ALL.get(i).id() + " drops " + dropped.stream().map(KilaModule::id).toList());
            }
            material.screenDistortion.stacking = false;
        }
    }

    @Test
    void everyModuleIsUsed() {
        var modules = new KilaMaterial().modules();
        var unused = new ArrayList<String>();
        for (int i = 0; i < modules.size(); i++) {
            int index = i;
            if (!any(m -> m.modules().get(index).isEnable())) unused.add(modules.get(i).id());
        }
        assertTrue(unused.isEmpty(), "no preset uses " + unused);
    }

    @Test
    void everyBlendSourceNoiseAndWrapIsUsed() {
        var blends = EnumSet.noneOf(BlendMode.Preset.class);
        var sources = EnumSet.noneOf(KilaDriver.Source.class);
        var noises = EnumSet.noneOf(KilaTexture.Noise.class);
        var wraps = EnumSet.noneOf(KilaTexture.Wrap.class);
        var uvs = EnumSet.noneOf(KilaTexture.UvSource.class);
        for (var material : PRESETS) {
            blends.add(material.blend);
            sources.addAll(sources(material));
            for (var module : material.modules()) {
                if (!module.isEnable()) continue;
                boolean noise = readsNoise(module);
                module.textures(texture -> {
                    if (!texture.isProcedural() || noise) noises.add(texture.noise);
                    wraps.add(texture.wrapU);
                    wraps.add(texture.wrapV);
                    uvs.add(texture.uvSource);
                });
            }
        }
        assertEquals(EnumSet.allOf(BlendMode.Preset.class), blends);
        // red, green and blue read like alpha does
        assertTrue(sources.containsAll(EnumSet.complementOf(EnumSet.of(KilaDriver.Source.VERTEX_R,
                KilaDriver.Source.VERTEX_G, KilaDriver.Source.VERTEX_B))), "driver sources used: " + sources);
        assertEquals(EnumSet.allOf(KilaTexture.Noise.class), noises);
        assertEquals(EnumSet.allOf(KilaTexture.Wrap.class), wraps);
        // the three world planes are one feature
        assertTrue(uvs.contains(KilaTexture.UvSource.MESH) && uvs.contains(KilaTexture.UvSource.SCREEN)
                && uvs.stream().anyMatch(uv -> uv.name().startsWith("WORLD")), "uv sources used: " + uvs);
    }

    @Test
    void everyMainOptionIsUsed() {
        Map<String, Predicate<KilaMaterial>> options = new LinkedHashMap<>();
        options.put("flipbook by time", m -> m.main.flipbook && m.main.frameSource == KilaMainTexture.FrameSource.TIME);
        options.put("flipbook driven", m -> m.main.flipbook && m.main.frameSource == KilaMainTexture.FrameSource.DRIVER);
        options.put("frame blend", m -> m.main.flipbook && m.main.frameBlend);
        options.put("motion vectors", m -> m.main.flipbook && m.main.frameBlend && m.main.motionVectors);
        options.put("random row", m -> m.main.flipbook && m.main.randomRow);
        options.put("random start", m -> m.main.flipbook && m.main.randomStart);
        options.put("dispersion", m -> m.main.dispersion);
        options.put("nearest sampling", m -> textures(m).stream().anyMatch(t -> t.nearest));
        options.put("polar uv", m -> textures(m).stream().anyMatch(t -> t.polar));
        options.put("frame-rate stepping and boil", m -> m.frameRate > 0 && m.boil > 0);
        options.put("camera offset", m -> m.cameraOffset != 0);
        options.put("emission", m -> !m.emission.equals(HDRColor.black()));
        options.put("back colour", m -> m.doubleSided && !m.backColor.equals(HDRColor.white()));
        options.put("alpha cut-out", m -> m.alphaClip >= 0.2f);
        options.put("depth write", m -> m.depthWrite);
        options.put("back faces first", m -> m.doubleSided && m.backFacesFirst);
        options.put("flow map", m -> m.distortion.isEnable() && m.distortion.mode == KilaDistortion.Mode.FLOW_MAP);
        options.put("twirl", m -> m.uvEffects.isEnable() && m.uvEffects.twirl.value != 0);
        options.put("shear", m -> m.uvEffects.isEnable() && (m.uvEffects.shear.x != 0 || m.uvEffects.shear.y != 0));
        options.put("wave", m -> m.uvEffects.isEnable() && m.uvEffects.waveAmplitude.value != 0);
        options.put("ripple", m -> m.uvEffects.isEnable() && m.uvEffects.rippleAmplitude != 0);
        options.put("dissolve along u", m -> m.dissolve.isEnable() && m.dissolve.direction == KilaDissolve.Direction.U);
        options.put("dissolve radial", m -> m.dissolve.isEnable() && m.dissolve.direction == KilaDissolve.Direction.RADIAL);
        options.put("dissolve by a direction texture",
                m -> m.dissolve.isEnable() && m.dissolve.direction == KilaDissolve.Direction.TEXTURE);
        options.put("dissolve edge gradient", m -> m.dissolve.isEnable() && m.dissolve.edgeGradient);
        options.put("dissolve edge replace", m -> m.dissolve.isEnable() && m.dissolve.edge == KilaDissolve.Edge.REPLACE);
        options.put("ramp along u, multiplied", m -> m.ramp.isEnable() && m.ramp.input == KilaRamp.Input.U
                && m.ramp.mode == KilaRamp.Mode.MULTIPLY);
        options.put("ramp offset driven", m -> m.ramp.isEnable() && m.ramp.offset.isDriven());
        options.put("fresnel rim", m -> m.fresnel.isEnable() && m.fresnel.mode == KilaFresnel.Mode.RIM);
        options.put("fresnel fade centre", m -> m.fresnel.isEnable() && m.fresnel.mode == KilaFresnel.Mode.FADE_CENTER);
        options.put("six-way lighting", m -> m.shading.isEnable() && m.shading.model == KilaShading.Model.SIX_WAY);
        options.put("height bump", m -> m.shading.isEnable() && m.shading.bump == KilaShading.Bump.HEIGHT);
        options.put("normal map", m -> m.shading.isEnable() && m.shading.bump == KilaShading.Bump.NORMAL_MAP);
        options.put("contact glow", m -> m.depth.isEnable() && m.depth.contactWidth.value > 0);
        options.put("contact pulses and gradient", m -> m.depth.isEnable() && m.depth.contactPulses > 0
                && m.depth.contactGradient);
        options.put("stacked screen distortion", m -> m.screenDistortion.isEnable() && m.screenDistortion.stack);
        options.put("screen distortion by the normal", m -> m.screenDistortion.isEnable()
                && m.screenDistortion.source == KilaScreenDistortion.Source.NORMAL);
        options.put("vertex offset along an axis", m -> m.vertexOffset.isEnable()
                && m.vertexOffset.direction == KilaVertexOffset.Direction.AXIS);
        options.put("posterize", m -> m.colorAdjust.isEnable() && m.colorAdjust.posterize >= 2);
        var layerBlends = EnumSet.noneOf(KilaLayer.Blend.class);
        for (var material : PRESETS) {
            for (var layer : List.of(material.layer, material.layer2)) {
                if (layer.isEnable()) layerBlends.add(layer.blend);
            }
        }
        options.put("layer blends add, multiply, screen",
                m -> layerBlends.containsAll(EnumSet.of(KilaLayer.Blend.ADD, KilaLayer.Blend.MULTIPLY, KilaLayer.Blend.SCREEN)));
        var unused = options.entrySet().stream().filter(e -> !any(e.getValue())).map(Map.Entry::getKey).toList();
        assertTrue(unused.isEmpty(), "no preset uses " + unused);
    }
}
