package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every label KilaMaterial can show has an English and a Chinese text — a missing one shows the raw key. */
class KilaLangTest {

    private static JsonObject lang(String name) throws IOException {
        try (var in = new InputStreamReader(Objects.requireNonNull(
                KilaLangTest.class.getResourceAsStream("/assets/photon/lang/" + name + ".json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        }
    }

    private static void choices(Set<String> keys, String key, Enum<?>[] values) {
        keys.add(key);
        for (var value : values) keys.add(key + "." + value.name().toLowerCase());
    }

    private static Set<String> expectedKeys() throws IOException {
        var keys = new LinkedHashSet<String>();
        // every literal key in the sources
        var literal = Pattern.compile("\"((?:kila|photon\\.material_requirements|photon\\.blend_preset|MaterialSetting)\\.[a-z0-9_.]+[a-z0-9_])\"");
        var root = Path.of("src/main/java/com/lowdragmc/photon/client/gameobject/emitter/data");
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) keys.add(matcher.group(1));
            }
        }
        // the ones built at runtime
        for (var source : KilaDriver.Source.values()) keys.add(source.langKey());
        for (var preset : KilaPresets.ALL) {
            keys.add(preset.langKey());
            keys.add(preset.langKey() + ".desc");
        }
        for (var category : KilaPresets.Category.values()) keys.add(category.langKey());
        for (var target : KilaPresets.Target.values()) {
            if (target != KilaPresets.Target.PARTICLE) keys.add(target.langKey());
        }
        for (var preset : BlendMode.Preset.values()) keys.add(preset.langKey());
        for (var category : KilaModule.Category.values()) keys.add("kila.module.category." + category.name().toLowerCase());
        for (var module : List.of(new KilaMainTexture(), new KilaLayer(), new KilaLayer(2), new KilaMask(1), new KilaMask(2), new KilaDissolve(),
                new KilaDistortion(), new KilaUvEffects(), new KilaProjection(), new KilaScreenDistortion(), new KilaRamp(), new KilaFresnel(),
                new KilaVertexOffset(), new KilaDepth(), new KilaShading(), new KilaMatcap(), new KilaVolume(),
                new KilaSimpleModules.CameraFade(), new KilaSimpleModules.ColorAdjust(), new KilaSimpleModules.Pixelate(),
                new KilaSimpleModules.Lit())) {
            keys.add(module.langKey());
            keys.add(module.langKey() + ".desc");
        }
        for (var texture : KilaTextures.ALL) {
            var path = texture.getPath();
            keys.add("kila.texture.builtin." + path.substring(path.lastIndexOf('/') + 1).replace(".png", ""));
        }
        for (var source : MaterialSetting.RenderStateSource.values()) {
            keys.add("MaterialSetting.renderStateSource." + source.name().toLowerCase());
        }
        choices(keys, "kila.render.blend", BlendMode.Preset.values());
        choices(keys, "kila.surface.vertex_color", KilaMaterial.VertexColor.values());
        choices(keys, "kila.texture.color_mode", KilaTexture.ColorMode.values());
        choices(keys, "kila.texture.channel", KilaTexture.Channel.values());
        choices(keys, "kila.texture.wrap_u", KilaTexture.Wrap.values());
        choices(keys, "kila.texture.wrap_v", KilaTexture.Wrap.values());
        choices(keys, "kila.main.flipbook.source", KilaMainTexture.FrameSource.values());
        choices(keys, "kila.layer.blend", KilaLayer.Blend.values());
        choices(keys, "kila.dissolve.edge", KilaDissolve.Edge.values());
        choices(keys, "kila.dissolve.direction.mode", KilaDissolve.Direction.values());
        choices(keys, "kila.distortion.mask", KilaDistortion.Mask.values());
        choices(keys, "kila.ramp.input", KilaRamp.Input.values());
        choices(keys, "kila.ramp.mode", KilaRamp.Mode.values());
        choices(keys, "kila.fresnel.mode", KilaFresnel.Mode.values());
        choices(keys, "kila.depth.contact.mode", KilaDepth.ContactMode.values());
        choices(keys, "kila.depth.contact.measure", KilaDepth.Measure.values());
        choices(keys, "kila.projection.orientation", KilaProjection.Orientation.values());
        choices(keys, "kila.shading.normal", KilaShading.Normal.values());
        choices(keys, "kila.shading.bump", KilaShading.Bump.values());
        choices(keys, "kila.shading.model", KilaShading.Model.values());
        choices(keys, "kila.matcap.blend", KilaMatcap.Blend.values());
        choices(keys, "kila.screen_distortion.source", KilaScreenDistortion.Source.values());
        choices(keys, "kila.vertex_offset.direction", KilaVertexOffset.Direction.values());
        choices(keys, "kila.texture.uv_source", KilaTexture.UvSource.values());
        for (var noise : KilaTexture.Noise.values()) keys.add("kila.texture.noise." + noise.name().toLowerCase());
        for (var background : KilaMaterial.PreviewBackground.values()) {
            keys.add("kila.preview.background." + background.name().toLowerCase());
        }
        choices(keys, "kila.distortion.mode", KilaDistortion.Mode.values());
        choices(keys, "kila.uv_effects.wave_axis", KilaUvEffects.WaveAxis.values());
        choices(keys, "kila.debug_view", KilaMaterial.DebugView.values());
        for (var choice : MaterialSetting.BlendChoice.values()) keys.add(choice.langKey());
        keys.add("MaterialSetting.blendPreset");
        keys.add("photon.material.convert_to_kila");
        keys.add("photon.material.convert_to_kila.confirm");
        // ".tips" keys are optional by design: KilaUI only attaches one that exists
        keys.removeIf(key -> key.endsWith(".tips"));
        return keys;
    }

    @Test
    void everyLabelIsTranslated() throws IOException {
        var keys = expectedKeys();
        assertTrue(keys.size() > 200, "found " + keys.size() + " keys — the scan is not finding the sources");
        for (var language : List.of("en_us", "zh_cn")) {
            var json = lang(language);
            var missing = new ArrayList<String>();
            for (var key : keys) {
                if (!json.has(key) || json.get(key).getAsString().isBlank()) missing.add(key);
            }
            assertTrue(missing.isEmpty(), language + " misses " + missing);
        }
    }

    @Test
    void bothLanguagesHaveTheSameKilaKeys() throws IOException {
        var en = lang("en_us");
        var zh = lang("zh_cn");
        for (var key : en.keySet()) {
            if (key.startsWith("kila")) assertTrue(zh.has(key), "zh_cn misses " + key);
        }
        for (var key : zh.keySet()) {
            if (key.startsWith("kila")) assertTrue(en.has(key), "en_us misses " + key);
        }
    }
}
