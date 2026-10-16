package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What a set of enabled modules compiles to: defines, the generated JSON, shared samplers, the budget. */
class KilaLayoutTest {

    private static KilaLayout build(KilaModule... modules) {
        return KilaLayout.surface().build(List.of(modules));
    }

    private static List<String> names(KilaLayout layout) {
        var names = new ArrayList<String>();
        layout.uniforms.forEach(uniform -> names.add(uniform.name()));
        return names;
    }

    @Test
    void surfaceOnly() {
        var layout = build();
        assertTrue(layout.defines.contains(KilaLayout.BASE_DEFINE));
        assertTrue(layout.defines.contains("KILA_P0"), "alpha and intensity always travel in group 0");
        assertFalse(layout.defines.contains("KILA_P1"));
        assertEquals(List.of("Sampler2"), layout.samplers);
        var uniforms = names(layout);
        assertTrue(uniforms.containsAll(List.of("KilaTint", "KilaSurface", "KilaSurfaceModes", "KilaBase0", "KilaScale0", "KilaSrc0")));
        assertFalse(uniforms.contains("ScreenSize"), "nothing samples in screen space, so it would be unused");
        assertTrue(layout.dropped.isEmpty());
    }

    @Test
    void jsonDeclaresExactlyTheLayout() {
        var main = new KilaMainTexture();
        var mask = new KilaMask(1);
        mask.setEnable(true);
        var layout = build(main, mask);
        var json = JsonParser.parseString(layout.json()).getAsJsonObject();
        assertEquals(KilaLayout.PROGRAM, json.get("vertex").getAsString());
        assertEquals(KilaLayout.PROGRAM, json.get("fragment").getAsString());
        var samplers = new ArrayList<String>();
        json.getAsJsonArray("samplers").forEach(e -> samplers.add(e.getAsJsonObject().get("name").getAsString()));
        assertEquals(layout.samplers, samplers);
        var uniforms = new HashSet<String>();
        json.getAsJsonArray("uniforms").forEach(e -> {
            var uniform = e.getAsJsonObject();
            uniforms.add(uniform.get("name").getAsString());
            assertEquals(uniform.get("count").getAsInt(), uniform.getAsJsonArray("values").size(),
                    uniform.get("name").getAsString() + " count matches its values");
        });
        assertEquals(new HashSet<>(names(layout)), uniforms);
        assertEquals(layout.uniforms.size(), uniforms.size(), "no uniform is declared twice");
        assertTrue(uniforms.containsAll(List.of("KilaMainST", "KilaMainOpts", "KilaMask1Params", "ScreenSize",
                "KilaBase2", "KilaSrc2")));
    }

    @Test
    void identicalTexturesShareOneSampler() {
        var dissolve = new KilaDissolve();
        dissolve.setEnable(true);
        var layer = new KilaLayer();
        layer.setEnable(true);
        layer.texture.texture = dissolve.texture.getTexture();
        layer.texture.wrapU = dissolve.texture.wrapU;
        layer.texture.wrapV = dissolve.texture.wrapV;
        var shared = build(layer, dissolve);
        assertEquals(1, shared.textures.size(), "same texture, same sampling: one sampler");
        assertTrue(shared.defines.contains("KILA_TEX_LAYER KilaTex0"));
        assertTrue(shared.defines.contains("KILA_TEX_DISSOLVE KilaTex0"));

        layer.texture.wrapU = KilaTexture.Wrap.MIRROR;
        var split = build(layer, dissolve);
        assertEquals(2, split.textures.size(), "a different wrap needs its own sampler");
        assertNotEquals(shared.key, split.key);
    }

    @Test
    void clipSharesWithClampButNotWithRepeat() {
        var a = new KilaTexture(KilaTextures.RING).wrap(KilaTexture.Wrap.CLIP, KilaTexture.Wrap.CLIP);
        var b = new KilaTexture(KilaTextures.RING).wrap(KilaTexture.Wrap.CLAMP, KilaTexture.Wrap.CLAMP);
        var c = new KilaTexture(KilaTextures.RING);
        assertTrue(a.sameSampler(b), "clip is clamp at the sampler, transparency is the shader's business");
        assertFalse(a.sameSampler(c));
        assertEquals(KilaTexture.FLAG_CLIP_U | KilaTexture.FLAG_CLIP_V, a.flags());
    }

    @Test
    void flipbookAddsItsGroupAndBlend() {
        var main = new KilaMainTexture();
        assertFalse(build(main).defines.contains("KILA_FLIPBOOK"));
        main.flipbook = true;
        var layout = build(main);
        assertTrue(layout.defines.contains("KILA_FLIPBOOK"));
        assertTrue(layout.defines.contains("KILA_P1"), "the frame driver lives in group 1");
        assertFalse(layout.defines.contains("KILA_FLIPBOOK_BLEND"));
        main.frameBlend = true;
        assertTrue(build(main).defines.contains("KILA_FLIPBOOK_BLEND"));
    }

    @Test
    void directionTextureIsAnExtraSlot() {
        var dissolve = new KilaDissolve();
        dissolve.setEnable(true);
        assertEquals(1, build(dissolve).textures.size());
        dissolve.direction = KilaDissolve.Direction.TEXTURE;
        var layout = build(dissolve);
        assertTrue(layout.defines.contains("KILA_DISSOLVE_DIRTEX"));
        assertEquals(2, layout.textures.size());
    }

    @Test
    void overBudgetModulesAreLeftOutInOrder() {
        var modules = new ArrayList<KilaModule>();
        var main = new KilaMainTexture();
        modules.add(main);
        var layer = new KilaLayer();
        layer.texture.texture = KilaTextures.NOISE_PERLIN;
        modules.add(layer);
        var mask1 = new KilaMask(1);
        mask1.texture.texture = KilaTextures.RING;
        modules.add(mask1);
        var mask2 = new KilaMask(2);
        mask2.texture.texture = KilaTextures.SPARK;
        modules.add(mask2);
        var dissolve = new KilaDissolve();
        dissolve.texture.texture = KilaTextures.NOISE_CELLS;
        modules.add(dissolve);
        var distortion = new KilaDistortion();
        modules.add(distortion);
        var ramp = new KilaRamp();
        modules.add(ramp);
        var vertex = new KilaVertexOffset();
        vertex.texture.texture = KilaTextures.GRADIENT_LINEAR;
        modules.add(vertex);
        modules.add(new KilaDepth());
        var lit = new KilaSimpleModules.Lit();
        modules.add(lit);
        modules.forEach(m -> m.setEnable(true));

        var layout = KilaLayout.surface().build(modules);
        assertTrue(layout.samplers.size() <= KilaLayout.SAMPLER_BUDGET, "budget kept: " + layout.samplers);
        assertEquals(List.of(lit), layout.dropped, "the last module that does not fit is the one left out");
        assertFalse(layout.defines.contains("KILA_LIT"));
        assertTrue(layout.samplers.contains("SamplerSceneDepth"), "the depth module before it still fits");
        assertFalse(layout.samplers.contains("PhotonLightData"));

        modules.remove(vertex);
        modules.remove(ramp);
        var fits = KilaLayout.surface().build(modules);
        assertTrue(fits.dropped.isEmpty(), "with two textures less the lights fit: " + fits.samplers);
        assertEquals(KilaLayout.SAMPLER_BUDGET, fits.samplers.size());
        assertTrue(fits.defines.contains("KILA_LIT"));
    }

    @Test
    void aProceduralSlotTakesNoSampler() {
        var dissolve = new KilaDissolve();
        dissolve.setEnable(true);
        dissolve.texture.noise(KilaTexture.Noise.VORONOI, 6);
        var layout = build(dissolve);
        assertTrue(layout.textures.isEmpty(), "no texture: " + layout.textures);
        assertEquals(List.of("Sampler2"), layout.samplers);
        assertTrue(layout.defines.contains("KILA_DISSOLVE_NOISE"));
        var uniforms = names(layout);
        assertTrue(uniforms.contains("KilaDissolveNoise"));
        assertFalse(uniforms.contains("KilaDissolveChannel"), "the channel is a texture's");
        assertFalse(layout.defines.stream().anyMatch(d -> d.startsWith("KILA_TEX_DISSOLVE")));

        var distortion = new KilaDistortion();
        distortion.setEnable(true);
        distortion.texture.noise(KilaTexture.Noise.GRADIENT, 3);
        var flow = build(distortion);
        assertTrue(flow.defines.contains("KILA_DISTORT_NOISE"));
        assertFalse(names(flow).contains("KilaDistortChannel"), "distortion reads red and green, never a channel");
    }

    @Test
    void theNoiseTypeIsAUniformButBeingProceduralIsNot() {
        var texture = new KilaTexture(KilaTextures.NOISE_CLOUD);
        int sampled = texture.structureHash();
        texture.noise(KilaTexture.Noise.SIMPLE, 4);
        int simple = texture.structureHash();
        assertNotEquals(sampled, simple);
        texture.noise(KilaTexture.Noise.VORONOI, 9);
        assertEquals(simple, texture.structureHash(), "switching noises only changes a uniform");
        texture.texture = KilaTextures.RING;
        assertEquals(simple, texture.structureHash(), "a procedural slot's texture is not read");
        texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        int world = texture.structureHash();
        assertNotEquals(simple, world, "world space adds the camera position");
        texture.uvSource = KilaTexture.UvSource.WORLD_ZY;
        assertEquals(world, texture.structureHash(), "which plane is a flag");
    }

    @Test
    void aWorldSlotDeclaresTheCameraOnce() {
        var main = new KilaMainTexture();
        assertFalse(build(main).world);
        main.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        var mask = new KilaMask(1);
        mask.setEnable(true);
        mask.texture.uvSource = KilaTexture.UvSource.WORLD_XY;
        var layout = build(main, mask);
        assertTrue(layout.world);
        assertTrue(layout.defines.contains("KILA_WORLD_UV"));
        assertEquals(1, names(layout).stream().filter("KilaCameraPos"::equals).count());
        var camera = layout.uniforms.stream().filter(u -> u.name().equals("KilaCameraPos")).findFirst().orElseThrow();
        assertEquals(3, camera.count());
        assertEquals((main.texture.flags() & KilaTexture.FLAG_WORLD_XZ), KilaTexture.FLAG_WORLD_XZ);
    }

    @Test
    void flowMapScreenDispersionAndUvEffects() {
        var distortion = new KilaDistortion();
        distortion.setEnable(true);
        assertFalse(build(distortion).defines.contains("KILA_FLOWMAP"));
        distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        var flow = build(distortion);
        assertTrue(flow.defines.contains("KILA_FLOWMAP"));
        assertTrue(names(flow).contains("KilaFlowMap"));

        var screen = new KilaScreenDistortion();
        screen.setEnable(true);
        var haze = build(screen);
        assertTrue(haze.defines.contains("KILA_SCREEN_DISTORT"));
        assertTrue(haze.samplers.contains("SamplerSceneColor"));
        assertTrue(haze.groups[KilaParam.SCREEN_STRENGTH.group]);
        assertTrue(names(haze).contains("ScreenSize"));

        var main = new KilaMainTexture();
        main.dispersion = true;
        var split = build(main);
        assertTrue(split.defines.contains("KILA_DISPERSION"));
        assertTrue(split.groups[KilaParam.DISPERSION_AMOUNT.group]);

        var uv = new KilaUvEffects();
        uv.setEnable(true);
        var bent = build(uv);
        assertTrue(bent.defines.contains("KILA_UV_EFFECTS"));
        assertTrue(names(bent).contains("GameTime"), "the wave moves with the clock even with no slot after it");
        assertTrue(names(bent).containsAll(List.of("KilaTwirl", "KilaShear", "KilaWave")));
        assertTrue(bent.groups[KilaParam.TWIRL_STRENGTH.group]);
    }

    @Test
    void depthDeclaresOnlyWhatItsContactUses() {
        var depth = new KilaDepth();
        depth.setEnable(true);
        var plain = build(depth);
        assertTrue(plain.defines.contains("KILA_DEPTH"));
        assertTrue(plain.groups[KilaParam.CONTACT_WIDTH.group], "the contact width and strength are drivable");
        assertTrue(plain.samplers.contains("SamplerSceneDepth"));
        assertTrue(plain.textures.isEmpty());
        assertFalse(plain.defines.contains("KILA_REL_POS"));
        assertFalse(names(plain).contains("KilaCameraPos"));

        depth.measure = KilaDepth.Measure.SURFACE;
        depth.contactGradient = true;
        depth.contactBreakup = 0.5f;
        var all = build(depth);
        assertTrue(all.defines.containsAll(List.of("KILA_REL_POS", "KILA_CONTACT_RAMP", "KILA_CONTACT_BREAKUP",
                "KILA_CONTACT_NOISE")));
        assertFalse(all.world, "the surface measure needs the position, not the camera");
        assertEquals(1, all.textures.size(), "the gradient bakes into a texture; the default breakup noise takes none");
        assertTrue(names(all).containsAll(List.of("KilaContactST", "KilaContactNoise")));

        depth.contactNoise.noise = KilaTexture.Noise.TEXTURE;
        var textured = build(depth);
        assertEquals(2, textured.textures.size());
        assertTrue(names(textured).contains("KilaContactChannel"));
    }

    @Test
    void edgeGradientRandomFlipbookAndForeground() {
        var dissolve = new KilaDissolve();
        dissolve.setEnable(true);
        assertFalse(build(dissolve).defines.contains("KILA_DISSOLVE_RAMP"));
        dissolve.edgeGradient = true;
        var burning = build(dissolve);
        assertTrue(burning.defines.contains("KILA_DISSOLVE_RAMP"));
        assertEquals(2, burning.textures.size());

        var main = new KilaMainTexture();
        main.randomRow = true;
        assertFalse(build(main).defines.contains("KILA_FLIPBOOK_RANDOM"), "nothing to pick a row of without a flipbook");
        main.flipbook = true;
        var random = build(main);
        assertTrue(random.defines.contains("KILA_FLIPBOOK_RANDOM"));
        assertTrue(random.groups[KilaParam.FLIPBOOK_SEED.group]);
        var seeds = new ArrayList<KilaDriver>();
        main.drivers((param, driver) -> {
            if (param == KilaParam.FLIPBOOK_SEED) seeds.add(driver);
        });
        assertEquals(1, seeds.size());
        assertEquals(KilaDriver.Source.RANDOM, seeds.getFirst().source, "the seed is the particle's random number");

        var screen = new KilaScreenDistortion();
        screen.setEnable(true);
        var masked = build(screen);
        assertTrue(masked.defines.contains("KILA_SCREEN_DEPTH"));
        assertTrue(masked.samplers.contains("SamplerSceneDepth"));
        screen.keepForeground = false;
        var bare = build(screen);
        assertFalse(bare.samplers.contains("SamplerSceneDepth"));
        assertFalse(names(bare).contains("U_InverseProjectionMatrix"));

        var depth = new KilaDepth();
        depth.setEnable(true);
        screen.keepForeground = true;
        var both = build(screen, depth);
        assertEquals(1, both.samplers.stream().filter("SamplerSceneDepth"::equals).count(), "one depth sampler for both");
    }

    @Test
    void shadingDeclaresWhatItsBumpAndModelRead() {
        var shading = new KilaShading();
        shading.setEnable(true);
        var plain = build(shading);
        assertTrue(plain.defines.contains("KILA_SHADING"));
        assertTrue(plain.textures.isEmpty(), "a sphere normal and a key light need no texture");
        assertTrue(names(plain).containsAll(List.of("KilaShadingOpts", "KilaShadingParams", "KilaKeyLight", "KilaKeyColor")));
        shading.bump = KilaShading.Bump.HEIGHT;
        var height = build(shading);
        assertTrue(height.defines.containsAll(List.of("KILA_BUMP", "KILA_BUMP_HEIGHT", "KILA_BUMP_NOISE")));
        assertTrue(height.textures.isEmpty(), "the default height is a procedural noise");
        shading.bump = KilaShading.Bump.NORMAL_MAP;
        shading.model = KilaShading.Model.SIX_WAY;
        var mapped = build(shading);
        assertFalse(mapped.defines.contains("KILA_BUMP_HEIGHT"));
        assertTrue(mapped.defines.containsAll(List.of("KILA_BUMP", "KILA_SIXWAY")));
        assertEquals(2, mapped.textures.size(), "the normal map and the negative lightmaps");
        assertFalse(names(mapped).contains("KilaBumpChannel"), "a normal map is read whole");
    }

    @Test
    void secondLayerMatcapVolumeAndDistortionSources() {
        var layer2 = new KilaLayer(2);
        layer2.setEnable(true);
        var second = build(layer2);
        assertTrue(second.defines.contains("KILA_LAYER2"));
        assertFalse(second.defines.contains("KILA_LAYER"));
        assertTrue(second.groups[KilaParam.LAYER2_STRENGTH.group]);
        assertTrue(names(second).contains("KilaLayer2ST"));
        assertEquals("layer2", layer2.id());

        var matcap = new KilaMatcap();
        matcap.setEnable(true);
        assertTrue(build(matcap).defines.contains("KILA_MATCAP"));

        var volume = new KilaVolume();
        volume.setEnable(true);
        var clipped = build(volume);
        assertTrue(clipped.defines.contains("KILA_VOLUME_CLIP"));
        assertTrue(clipped.samplers.contains("SamplerSceneDepth"));
        volume.clip = false;
        assertFalse(build(volume).samplers.contains("SamplerSceneDepth"));

        var screen = new KilaScreenDistortion();
        screen.setEnable(true);
        screen.source = KilaScreenDistortion.Source.NORMAL;
        screen.chromatic = 0.3f;
        var bent = build(screen);
        assertTrue(bent.defines.containsAll(List.of("KILA_SCREEN_NORMAL", "KILA_SCREEN_CHROMA")));
        assertTrue(bent.textures.isEmpty(), "bent by the normal, the noise texture is not read");
        assertFalse(names(bent).contains("KilaScreenST"));

        var main = new KilaMainTexture();
        main.flipbook = true;
        main.motionVectors = true;
        assertFalse(build(main).defines.contains("KILA_FLIPBOOK_MOTION"), "motion vectors push blended frames only");
        main.frameBlend = true;
        var moving = build(main);
        assertTrue(moving.defines.contains("KILA_FLIPBOOK_MOTION"));
        assertEquals(2, moving.textures.size());
    }

    @Test
    void aStackingDistortionWritesItsPushInsteadOfReadingTheScene() {
        var screen = new KilaScreenDistortion();
        screen.setEnable(true);
        screen.stack = true;
        assertFalse(build(screen).defines.contains("KILA_SCREEN_STACK"), "stacking is the pipeline's to allow, per draw");
        screen.stacking = true;
        var stacked = build(screen);
        assertTrue(stacked.defines.contains("KILA_SCREEN_STACK"));
        assertFalse(stacked.samplers.contains("SamplerSceneColor"), "the scene is read once, by the bend");
        assertFalse(stacked.samplers.contains("SamplerSceneDepth"), "so is the depth that masks the foreground");
        assertTrue(names(stacked).contains("KilaScreenParams"));
    }

    @Test
    void layoutKeyIsTheSortedDefines() {
        var main = new KilaMainTexture();
        var dissolve = new KilaDissolve();
        dissolve.setEnable(true);
        var a = build(main, dissolve);
        var b = build(main, dissolve);
        assertEquals(a.key, b.key);
        assertEquals(String.join("|", a.defines), a.key);
    }

    @Test
    void driverBitsFollowTheSource() {
        var driver = new KilaDriver(0.5f);
        assertEquals(0L, driver.channelMask());
        assertFalse(driver.isDriven());
        driver.bind(KilaDriver.Source.LIFE, 1);
        assertNotEquals(0L, driver.channelMask());
        assertFalse(driver.readsCustomData());
        driver.bindCustom(1, 3, 2);
        assertTrue(driver.readsCustomData());
        assertEquals(0L, driver.channelMask(), "custom data has its own buffer, no additional-data channel");
        assertTrue(driver.source.isParticleData());
        driver.bind(KilaDriver.Source.VERTEX_A, -1);
        assertFalse(driver.source.isParticleData(), "vertex colour is on every path");
    }
}
