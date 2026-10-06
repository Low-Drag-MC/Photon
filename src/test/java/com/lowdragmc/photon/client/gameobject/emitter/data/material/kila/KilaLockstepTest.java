package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every number the Kila shaders and the Java side must agree on, read out of the shipped GLSL — a drift
 * compiles and renders, just wrongly, so nothing else would notice.
 */
class KilaLockstepTest {

    private static String shader(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                KilaLockstepTest.class.getResourceAsStream("/assets/photon/shaders/" + path), path + " missing")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parameterSlotsMatchTheFragmentMacros() throws IOException {
        var fsh = shader("core/kila.fsh");
        var macro = Pattern.compile("#define KILA_([A-Z0-9_]+) kilaP(\\d)\\.([xyzw]{1,2})");
        var slots = new HashMap<String, int[]>();
        var matcher = macro.matcher(fsh);
        while (matcher.find()) {
            var name = matcher.group(1);
            int group = Integer.parseInt(matcher.group(2));
            var swizzle = matcher.group(3);
            if (swizzle.length() == 1) {
                slots.put(name, new int[]{group, "xyzw".indexOf(swizzle.charAt(0))});
            } else {
                // a two-component macro carries an _U / _V pair
                slots.put(name + "_U", new int[]{group, "xyzw".indexOf(swizzle.charAt(0))});
                slots.put(name + "_V", new int[]{group, "xyzw".indexOf(swizzle.charAt(1))});
            }
        }
        for (var param : KilaParam.values()) {
            if (param == KilaParam.VERTEX_OFFSET_STRENGTH) continue; // read in the vertex stage only
            var slot = slots.get(param.name());
            assertNotNull(slot, "kila.fsh has no KILA_" + param.name() + " macro");
            assertEquals(param.group, slot[0], param + " group");
            assertEquals(param.component, slot[1], param + " component");
        }
        var vsh = shader("core/kila.vsh");
        assertTrue(vsh.contains("position += direction * push * kilaP4.w;"),
                "the vertex offset strength is read from kilaP4.w in kila.vsh");
        assertEquals(4, KilaParam.VERTEX_OFFSET_STRENGTH.group);
        assertEquals(3, KilaParam.VERTEX_OFFSET_STRENGTH.component);
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            assertTrue(vsh.contains("out vec4 kilaP" + group + ";"), "kila.vsh writes kilaP" + group);
            assertTrue(fsh.contains("in vec4 kilaP" + group + ";"), "kila.fsh reads kilaP" + group);
        }
    }

    /**
     * A macro the shader defines must not share a name with a switch the layout passes in: the shader's own
     * {@code #define} would turn the {@code #ifdef} on for every variant, and redefine it for the ones that set it.
     */
    @Test
    void noShaderMacroShadowsAFeatureSwitch() throws IOException {
        var defined = new HashSet<String>();
        var tested = new HashSet<String>();
        var define = Pattern.compile("(?m)^\\s*#define\\s+(\\w+)");
        var test = Pattern.compile("#ifn?def\\s+(\\w+)|defined\\((\\w+)\\)");
        for (var path : List.of("core/kila.fsh", "core/kila.vsh", "include/kila_lib.glsl")) {
            var source = shader(path);
            var m = define.matcher(source);
            while (m.find()) defined.add(m.group(1));
            m = test.matcher(source);
            while (m.find()) tested.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        // every switch on, each in a layout of its own so none is left out for the samplers
        var main = new KilaMainTexture();
        main.flipbook = true;
        main.frameBlend = true;
        main.dispersion = true;
        main.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        var dissolve = new KilaDissolve();
        dissolve.direction = KilaDissolve.Direction.TEXTURE;
        var distortion = new KilaDistortion();
        distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        var mask = new KilaMask(1);
        mask.texture.noise(KilaTexture.Noise.GRADIENT, 4);
        var noisyDissolve = new KilaDissolve();
        noisyDissolve.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        var noisyDistortion = new KilaDistortion();
        noisyDistortion.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        var screen = new KilaScreenDistortion();
        screen.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        var vertex = new KilaVertexOffset();
        vertex.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        main.randomRow = true;
        dissolve.edgeGradient = true;
        var depth = new KilaDepth();
        depth.measure = KilaDepth.Measure.SURFACE;
        depth.contactGradient = true;
        depth.contactBreakup = 1;
        var texturedDepth = new KilaDepth();
        texturedDepth.contactBreakup = 1;
        texturedDepth.contactNoise.noise = KilaTexture.Noise.TEXTURE;
        main.motionVectors = true;
        var heightShading = new KilaShading();
        heightShading.bump = KilaShading.Bump.HEIGHT;
        var texturedHeight = new KilaShading();
        texturedHeight.bump = KilaShading.Bump.HEIGHT;
        texturedHeight.height.noise = KilaTexture.Noise.TEXTURE;
        var mappedShading = new KilaShading();
        mappedShading.bump = KilaShading.Bump.NORMAL_MAP;
        mappedShading.model = KilaShading.Model.SIX_WAY;
        var normalScreen = new KilaScreenDistortion();
        normalScreen.source = KilaScreenDistortion.Source.NORMAL;
        normalScreen.chromatic = 0.5f;
        var passed = new HashSet<String>();
        for (var module : List.of(main, new KilaLayer(), mask, new KilaMask(2), dissolve, noisyDissolve, distortion,
                noisyDistortion, new KilaUvEffects(), new KilaProjection(), screen, new KilaScreenDistortion(), new KilaRamp(), new KilaFresnel(),
                vertex, new KilaVertexOffset(), new KilaDepth(), depth, texturedDepth, new KilaLayer(2), heightShading,
                texturedHeight, mappedShading, normalScreen, new KilaMatcap(), new KilaVolume(), new KilaSimpleModules.CameraFade(),
                new KilaSimpleModules.ColorAdjust(), new KilaSimpleModules.Pixelate(), new KilaSimpleModules.Lit())) {
            module.setEnable(true);
            for (var name : KilaLayout.surface().build(List.of(module)).defines) passed.add(name.split(" ")[0]);
        }
        assertTrue(passed.containsAll(List.of("KILA_DISPERSION", "KILA_FLOWMAP", "KILA_MASK1_NOISE", "KILA_SCREEN_NOISE",
                "KILA_WORLD_UV", "KILA_TEX_MAIN", "KILA_P5", "KILA_P6", "KILA_FLIPBOOK_RANDOM", "KILA_DISSOLVE_RAMP",
                "KILA_REL_POS", "KILA_CONTACT_RAMP", "KILA_CONTACT_BREAKUP", "KILA_CONTACT_NOISE", "KILA_TEX_CONTACT",
                "KILA_SCREEN_DEPTH", "KILA_LAYER2", "KILA_SHADING", "KILA_BUMP", "KILA_BUMP_HEIGHT", "KILA_BUMP_NOISE",
                "KILA_SIXWAY", "KILA_TEX_SIXWAY", "KILA_TEX_BUMP", "KILA_SCREEN_NORMAL", "KILA_SCREEN_CHROMA", "KILA_MATCAP",
                "KILA_VOLUME", "KILA_VOLUME_CLIP", "KILA_FLIPBOOK_MOTION", "KILA_TEX_MOTION")), passed.toString());
        for (var name : defined) {
            assertFalse(tested.contains(name), name + " is #defined in the shader and also tested with #ifdef");
            assertFalse(passed.contains(name), name + " is #defined in the shader and also passed in by the layout");
        }
    }

    @Test
    void driverSourceCodesMatchTheVertexShader() throws IOException {
        var vsh = shader("core/kila.vsh");
        assertTrue(vsh.contains("if (code == 0) return 0.0;"));
        assertEquals(0, KilaDriver.Source.CONSTANT.code);
        assertTrue(vsh.contains("if (code <= 4) return data.Color[code - 1];"));
        assertEquals(1, KilaDriver.Source.VERTEX_R.code);
        assertEquals(4, KilaDriver.Source.VERTEX_A.code);
        Map<KilaDriver.Source, String> calls = Map.of(
                KilaDriver.Source.LIFE, "photon_data_t()",
                KilaDriver.Source.RANDOM, "photon_data_random()",
                KilaDriver.Source.POINT_LIFE, "photon_data_point_life()",
                KilaDriver.Source.POINT_T, "photon_data_point_t()");
        calls.forEach((source, call) -> assertTrue(vsh.contains("if (code == " + source.code + ") return " + call + ";"),
                source + " is code " + source.code + " -> " + call));
        assertTrue(vsh.contains("int c = code - " + KilaDriver.Source.CUSTOM.code + ";"));
        var custom = new KilaDriver().bindCustom(3, 2, 1);
        assertEquals(KilaDriver.Source.CUSTOM.code + 3 * 4 + 2, custom.code());
        assertTrue(vsh.contains("return photon_custom_data(c / 4)[c % 4];"));
    }

    @Test
    void uvFlagsMatchTheLibrary() throws IOException {
        var lib = shader("include/kila_lib.glsl");
        assertTrue(lib.contains("#define KILA_UV_POLAR " + KilaTexture.FLAG_POLAR));
        assertTrue(lib.contains("#define KILA_UV_SCREEN " + KilaTexture.FLAG_SCREEN));
        assertTrue(lib.contains("#define KILA_UV_CLIP_U " + KilaTexture.FLAG_CLIP_U));
        assertTrue(lib.contains("#define KILA_UV_CLIP_V " + KilaTexture.FLAG_CLIP_V));
        assertTrue(lib.contains("#define KILA_UV_WORLD_XZ " + KilaTexture.FLAG_WORLD_XZ));
        assertTrue(lib.contains("#define KILA_UV_WORLD_XY " + KilaTexture.FLAG_WORLD_XY));
        assertTrue(lib.contains("#define KILA_UV_WORLD_ZY " + KilaTexture.FLAG_WORLD_ZY));
        assertTrue(lib.contains("if ((flags & KILA_UV_WORLD_XZ) != 0) return world.xz;"));
        assertTrue(lib.contains("if ((flags & KILA_UV_WORLD_XY) != 0) return world.xy;"));
        assertTrue(lib.contains("if ((flags & KILA_UV_WORLD_ZY) != 0) return world.zy;"));
        int all = 0;
        for (var source : KilaTexture.UvSource.values()) {
            assertEquals(0, all & source.flag, source + " shares a bit");
            all |= source.flag;
        }
        assertEquals(0, all & (KilaTexture.FLAG_POLAR | KilaTexture.FLAG_CLIP_U | KilaTexture.FLAG_CLIP_V),
                "uv sources overlap the other flags");
        for (int i = 0; i < KilaLayout.MAX_TEXTURES; i++) {
            assertTrue(lib.contains("#ifdef KILA_TEX" + i + "\nuniform sampler2D KilaTex" + i + ";")
                            || lib.contains("#ifdef KILA_TEX" + i + "\r\nuniform sampler2D KilaTex" + i + ";"),
                    "kila_lib.glsl declares KilaTex" + i);
        }
        assertFalse(lib.contains("KilaTex" + KilaLayout.MAX_TEXTURES + ";"), "more samplers declared than MAX_TEXTURES");
    }

    @Test
    void noiseTypesMatchTheLibrary() throws IOException {
        var lib = shader("include/kila_lib.glsl");
        assertEquals(0, KilaTexture.Noise.TEXTURE.ordinal(), "0 is a texture slot, never handed to kila_noise");
        assertTrue(lib.contains("if (type == " + KilaTexture.Noise.SIMPLE.ordinal() + ") {"));
        assertTrue(lib.contains("if (type == " + KilaTexture.Noise.GRADIENT.ordinal() + ") return kila_gradient_noise("));
        assertEquals(KilaTexture.Noise.values().length - 1, KilaTexture.Noise.VORONOI.ordinal(),
                "voronoi is kila_noise's fall-through");
        assertTrue(lib.contains("return kila_voronoi(uv, 2.0 + kila_time() * noise.z, noise.y);"));
    }

    @Test
    void debugViewsMatchTheFragmentShader() throws IOException {
        var fsh = shader("core/kila.fsh");
        assertEquals(0, KilaMaterial.DebugView.OFF.ordinal());
        assertTrue(fsh.contains("if (view != " + KilaMaterial.DebugView.OFF.ordinal() + ") {"));
        assertTrue(fsh.contains("view == " + KilaMaterial.DebugView.MAIN.ordinal() + " ? debugMain.rgb * debugMain.a"));
        assertTrue(fsh.contains("view == " + KilaMaterial.DebugView.ALPHA.ordinal() + " ? vec3(c.a)"));
        assertTrue(fsh.contains("view == " + KilaMaterial.DebugView.MASK.ordinal() + " ? vec3(debugMask)"));
        assertTrue(fsh.contains("view == " + KilaMaterial.DebugView.DISSOLVE.ordinal() + " ? vec3(debugNoise)"));
        assertTrue(fsh.contains("view == " + KilaMaterial.DebugView.DISTORTION.ordinal() + " ? vec3(debugFlow"));
        assertTrue(fsh.contains("view == " + KilaMaterial.DebugView.NORMAL.ordinal() + " ? kilaNormal * 0.5 + 0.5"));
        assertEquals(KilaMaterial.DebugView.values().length - 1, KilaMaterial.DebugView.UV.ordinal(),
                "uv is the views' fall-through");
        assertTrue(fsh.contains(": vec3(fract(kilaMeshUv), 0.0);"));
    }

    /** The inspector's slot thumbnail reads the slot the way the material does. */
    @Test
    void slotPreviewMatchesTheTextureKinds() throws IOException {
        var fsh = shader("core/kila_preview.fsh");
        assertTrue(fsh.contains("if (reads == " + KilaUI.TextureKind.COLOR.ordinal() + ") {"));
        assertTrue(fsh.contains("} else if (reads == " + KilaUI.TextureKind.SCALAR.ordinal() + ") {"));
        assertEquals(KilaUI.TextureKind.values().length - 1, KilaUI.TextureKind.VECTOR.ordinal(),
                "red and green is the preview's fall-through");
        assertTrue(fsh.contains("#moj_import <photon:kila_lib.glsl>"), "the same uv chain and noise as the material");
        assertTrue(fsh.contains("kila_noise(uv, KilaPreviewNoise)") && fsh.contains("kila_noise2(uv, KilaPreviewNoise)"));
    }

    @Test
    void colorModesMatchTheLibrary() throws IOException {
        var lib = shader("include/kila_lib.glsl");
        assertTrue(lib.contains("if (mode == " + KilaTexture.ColorMode.RED_AS_ALPHA.ordinal() + ") return vec4(t.rgb, t.r);"));
        assertTrue(lib.contains("if (mode == " + KilaTexture.ColorMode.ALPHA_ONLY.ordinal() + ") return vec4(1.0, 1.0, 1.0, t.a);"));
        assertTrue(lib.contains("if (mode == " + KilaTexture.ColorMode.LUMINANCE_AS_ALPHA.ordinal() + ") return vec4(t.rgb, kila_luma(t.rgb));"));
    }

    @Test
    void outputAndVertexColorModesMatchTheFragmentShader() throws IOException {
        var fsh = shader("core/kila.fsh");
        assertTrue(fsh.contains("if (preset == " + BlendMode.Preset.PREMULTIPLIED.ordinal() + ") {"));
        assertEquals(0, BlendMode.Preset.ALPHA.ordinal(), "a slot-state material outputs straight colour as ALPHA");
        assertTrue(fsh.contains("} else if (preset == " + BlendMode.Preset.MULTIPLY.ordinal() + ") {"));
        assertTrue(fsh.contains("} else if (preset == " + BlendMode.Preset.OPAQUE.ordinal() + ") {"));
        assertTrue(fsh.contains("if (vertexMode == " + KilaMaterial.VertexColor.MULTIPLY.ordinal() + ") {"));
        assertTrue(fsh.contains("} else if (vertexMode == " + KilaMaterial.VertexColor.ALPHA_ONLY.ordinal() + ") {"));
        assertTrue(fsh.contains("} else if (vertexMode == " + KilaMaterial.VertexColor.RGB_ONLY.ordinal() + ") {"));
    }

    @Test
    void depthOptionsMatchTheFragmentShader() throws IOException {
        var fsh = shader("core/kila.fsh");
        assertEquals(1, KilaDepth.Measure.SURFACE.ordinal());
        assertTrue(fsh.contains("if (KilaDepthOpts.y == " + KilaDepth.Measure.SURFACE.ordinal() + ") {"));
        assertEquals(1, KilaDepth.ContactMode.REPLACE.ordinal());
        assertTrue(fsh.contains("vec3 over = KilaDepthOpts.z == " + KilaDepth.ContactMode.REPLACE.ordinal()
                + " ? color * glow + under * (1.0 - glow) : under + color * glow;"));
        assertEquals(KilaDepth.ContactMode.values().length - 1, KilaDepth.ContactMode.REPLACE.ordinal(),
                "anything else adds");
    }

    @Test
    void projectionOrientationMatchesTheFragmentShader() throws IOException {
        var fsh = shader("core/kila.fsh");
        assertEquals(0, KilaProjection.Orientation.GROUND.ordinal());
        assertEquals(KilaProjection.Orientation.values().length - 1, KilaProjection.Orientation.QUAD.ordinal());
        int quad = fsh.indexOf("if (KilaProjectionShape.x > 0.5) {");
        assertTrue(quad >= 0 && fsh.indexOf("up = n / area;", quad) - quad < 60, "QUAD is the quad's own plane");
    }

    @Test
    void shadingMatcapAndLayersMatchTheFragmentShader() throws IOException {
        var fsh = shader("core/kila.fsh");
        assertEquals(1, KilaShading.Normal.SPHERE.ordinal());
        assertTrue(fsh.contains("if (KilaShadingOpts.x == " + KilaShading.Normal.SPHERE.ordinal() + ") {"));
        assertTrue(fsh.contains("int capBlend = int(KilaMatcap.y + 0.5);"));
        assertTrue(fsh.contains("capBlend == " + KilaMatcap.Blend.ADD.ordinal() + " ? c.rgb + cap * KilaMatcap.x : capBlend == "
                + KilaMatcap.Blend.MULTIPLY.ordinal() + " ? "));
        assertEquals(KilaMatcap.Blend.values().length - 1, KilaMatcap.Blend.REPLACE.ordinal(), "replace is the fall-through");
        assertTrue(fsh.contains("if (mode == " + KilaLayer.Blend.MULTIPLY.ordinal() + ") {"));
        assertTrue(fsh.contains("} else if (mode == " + KilaLayer.Blend.ADD.ordinal() + ") {"));
        assertTrue(fsh.contains("} else if (mode == " + KilaLayer.Blend.LERP.ordinal() + ") {"));
        assertEquals(KilaLayer.Blend.values().length - 1, KilaLayer.Blend.SCREEN.ordinal(), "screen is the fall-through");
    }

    @Test
    void distortionTargetsMatchTheFragmentShader() throws IOException {
        var fsh = shader("core/kila.fsh");
        for (var target : KilaDistortion.Target.values()) {
            assertTrue(fsh.contains("#define KILA_FLOW_" + target.name() + " " + target.bit), target.name());
        }
    }

    @Test
    void blendPresetsRoundTrip() {
        for (var preset : BlendMode.Preset.values()) {
            assertEquals(preset, BlendMode.Preset.of(preset.create()), preset.name());
        }
        assertFalse(BlendMode.Preset.OPAQUE.create().isEnableBlend());
    }

    /** The CPU path's records use the tile layout, and every instanced kind carries custom data. */
    @Test
    void particleDataLayoutsMatchParticleGlsl() throws IOException {
        var glsl = shader("include/particle.glsl");
        assertEquals("PHOTON_CPU_DATA", MaterialContext.CPU_DATA_DEFINE);
        int cpu = glsl.indexOf("#ifdef PHOTON_CPU_DATA");
        assertTrue(cpu >= 0, "particle.glsl has a PHOTON_CPU_DATA block");
        var cpuBlock = glsl.substring(cpu, glsl.indexOf("#endif", cpu));
        var tile = PhotonGpuChannels.Kind.TILE;
        assertEquals(PhotonGpuChannels.declaredSlotCount(tile), PhotonGpuChannels.declaredSlotCount(PhotonGpuChannels.Kind.TILE_MODEL),
                "CPU records are written with whichever tile kind the emitter is; both must pack alike");
        assertTrue(cpuBlock.contains("#define PHOTON_DATA_TEXELS " + PhotonGpuChannels.declaredSlotCount(tile)), cpuBlock);
        assertTrue(cpuBlock.contains("#define PHOTON_CUSTOM_TEXELS " + AdditionalGPUDataSetting.MAX_CUSTOM_DATA), cpuBlock);
        assertTrue(cpuBlock.contains("uniform samplerBuffer PhotonData;"));
        assertTrue(cpuBlock.contains("uniform samplerBuffer PhotonCustomData;"));
        assertTrue(glsl.contains("#define PHOTON_RECORD_INDEX (gl_VertexID / max(PhotonVertsPerParticle, 1))"));

        for (var variant : List.of("PARTICLE_INSTANCE", "PARTICLE_MODEL_INSTANCE", "TRAIL_INSTANCE",
                "ARA_TRAIL_INSTANCE", "ARA_TRAIL_TUBE_INSTANCE", "BEAM_INSTANCE")) {
            int start = glsl.indexOf("defined(" + variant + ")") >= 0 && glsl.indexOf("#ifdef " + variant) == 0
                    ? 0 : glsl.indexOf("#elif defined(" + variant + ")");
            assertTrue(start >= 0, variant + " declaration block");
            int end = glsl.indexOf("#elif", start + 6);
            int fallback = glsl.indexOf("#else", start + 6);
            if (end < 0 || (fallback >= 0 && fallback < end)) end = fallback;
            var block = glsl.substring(start, end);
            assertTrue(block.contains("uniform samplerBuffer PhotonCustomData;"),
                    variant + " declares PhotonCustomData — the Java side uploads custom data for it");
            assertTrue(block.contains("#define PHOTON_CUSTOM_TEXELS " + AdditionalGPUDataSetting.MAX_CUSTOM_DATA), variant);
        }
        int accessor = glsl.indexOf("vec4 photon_custom_data(int i) {");
        var guard = glsl.substring(glsl.lastIndexOf("#if ", accessor), accessor);
        for (var variant : List.of("PARTICLE_INSTANCE", "PARTICLE_MODEL_INSTANCE", "TRAIL_INSTANCE",
                "ARA_TRAIL_INSTANCE", "ARA_TRAIL_TUBE_INSTANCE", "BEAM_INSTANCE", "PHOTON_CPU_DATA")) {
            assertTrue(guard.contains("defined(" + variant + ")"), "photon_custom_data() reads the buffer on " + variant);
        }
    }
}
