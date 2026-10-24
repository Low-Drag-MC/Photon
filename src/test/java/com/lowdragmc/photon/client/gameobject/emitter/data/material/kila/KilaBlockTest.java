package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code KilaMaterial} block each variant builds from what its modules declare, and the shaders that read it. A
 * member a variant's shaders read but its modules never declared does not compile; one out of place reads another
 * member's value, which compiles and renders — just wrongly. Neither shows until somebody turns on that combination of
 * modules, so both are checked here for every combination the presets and the module options make.
 */
class KilaBlockTest {
    private static final Pattern MEMBER = Pattern.compile("\\b(Kila[A-Z]\\w*|SoftParticleParams)\\b");
    /** What {@link #MEMBER} also matches but is no block member: the block itself, a macro, the samplers. */
    private static final Pattern NOT_A_MEMBER = Pattern.compile("KilaMaterial|KilaCameraPos|KilaTex\\d+");

    @BeforeAll
    static void registry() {
        // IMaterial's codec reads the registry the client builds at start-up
        if (PhotonRegistries.MATERIALS == null) {
            PhotonRegistries.MATERIALS = AutoRegistry.LDLibRegisterClient.create(Photon.id("material"), IMaterial.class,
                    AutoRegistry::noArgsCreator);
        }
    }

    private static String shader(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                KilaBlockTest.class.getResourceAsStream("/assets/photon/shaders/" + path), path + " missing")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    // ---- the block itself ---------------------------------------------------------------------------------------

    private static KilaBlock block(Object... nameThenType) {
        var members = new ArrayList<KilaBlock.Member>();
        for (int i = 0; i < nameThenType.length; i += 2) {
            members.add(new KilaBlock.Member((String) nameThenType[i], (KilaBlock.Type) nameThenType[i + 1]));
        }
        return new KilaBlock(members);
    }

    @Test
    void theVectorsGoFirstSoNothingIsPadded() {
        var block = block("a", KilaBlock.Type.FLOAT, "b", KilaBlock.Type.VEC4, "c", KilaBlock.Type.INT,
                "d", KilaBlock.Type.IVEC4);
        assertEquals(List.of("b", "d", "a", "c"), block.fields().stream().map(KilaBlock.Field::name).toList());
        assertEquals("vec4 b; ivec4 d; float a; int c;", block.glsl());
        assertEquals(KilaBlock.MEMBERS_DEFINE + " vec4 b; ivec4 d; float a; int c;", block.define());
        assertEquals(48, block.size(), "two vec4s and two scalars, rounded up to a whole vec4");
    }

    /** std140, worked out independently: vec4 and ivec4 at a multiple of 16, a scalar at 4, the block a whole vec4. */
    @Test
    void offsetsAreStd140() {
        var layout = KilaLayout.surface().build(List.of());
        int offset = 0;
        for (var field : layout.block.fields()) {
            int align = field.type().size == 16 ? 16 : 4;
            offset = (offset + align - 1) / align * align;
            assertEquals(offset, field.offset(), field.name());
            offset += field.type().size;
        }
        assertEquals((offset + 15) / 16 * 16, layout.block.size());
    }

    @Test
    void aNameDeclaredTwiceIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> block("a", KilaBlock.Type.VEC4, "a", KilaBlock.Type.VEC4));
        assertThrows(IllegalStateException.class, () -> KilaLayout.surface().ivec4("KilaTint"),
                "the surface declared it a vec4");
    }

    @Test
    void theLibraryDeclaresTheBlockAroundTheMembers() throws IOException {
        var lib = shader("include/kila_lib.glsl");
        int guard = lib.indexOf("#ifndef KILA_SLOT_PREVIEW");
        int block = lib.indexOf("layout(std140) uniform " + KilaBlock.NAME + " {\n    " + KilaBlock.MEMBERS_DEFINE + "\n};");
        assertTrue(block >= 0, "the block is the generated members, nothing else");
        assertTrue(guard >= 0 && guard < block, "the slot preview leaves the material's out");
    }

    /** 26.1 links no free uniform: anything that is not a sampler must sit in a block, or it reads 0 forever. */
    @Test
    void noFreeUniforms() throws IOException {
        var free = Pattern.compile("(?m)^\\s*uniform\\s+(?!sampler|isampler|usampler)(\\w+)\\s+(\\w+)\\s*;");
        for (var path : List.of("core/kila.vsh", "core/kila.fsh", "include/kila_lib.glsl", "core/kila_preview.vsh",
                "core/kila_preview.fsh", "core/kila_stack.fsh")) {
            var matcher = free.matcher(shader(path));
            assertFalse(matcher.find(), path + " declares a free uniform: " + (matcher.hasMatch() ? matcher.group() : ""));
        }
    }

    @Test
    void theSlotPreviewHasABlockOfItsOwn() throws IOException {
        var source = shader("core/kila_preview.fsh");
        int start = source.indexOf("layout(std140) uniform KilaSlot {");
        assertTrue(start >= 0, "no KilaSlot block");
        var body = source.substring(source.indexOf('{', start) + 1, source.indexOf("};", start)).replaceAll("//[^\n]*", "");
        var members = new ArrayList<String[]>();
        var matcher = Pattern.compile("(?m)^\\s*(\\w+)\\s+(\\w+)\\s*;").matcher(body);
        while (matcher.find()) members.add(new String[]{matcher.group(1), matcher.group(2)});
        assertEquals(List.of("KilaPreviewST", "KilaPreviewAnim", "KilaPreviewPolar", "KilaPreviewOpts", "KilaPreviewChannel",
                "KilaPreviewNoise"), members.stream().map(m -> m[1]).toList(), "the order KilaSlotPreview writes");
        assertEquals("ivec4", members.get(3)[0]);
    }

    // ---- every variant ------------------------------------------------------------------------------------------

    /** Every configuration worth compiling: the presets as the material lays them out, and each module option alone. */
    private static Map<String, KilaLayout> variants() {
        var variants = new LinkedHashMap<String, KilaLayout>();
        for (var preset : KilaPresets.ALL) {
            var material = preset.create();
            variants.put(preset.id(), material.layout());
            variants.put(preset.id() + " (thumbnail)", material.layout(true));
        }
        var everything = new KilaMaterial();
        everything.modules().forEach(module -> module.setEnable(true));
        everything.cameraOffset = 1;
        everything.frameRate = 12;
        everything.alpha.source = KilaDriver.Source.LIFE;
        variants.put("every module", everything.layout());
        for (var entry : modules().entrySet()) {
            entry.getValue().setEnable(true);
            variants.put(entry.getKey(), KilaLayout.surface().build(List.of(entry.getValue())));
        }
        return variants;
    }

    /** Each module, and each option that declares something of its own. */
    private static Map<String, KilaModule> modules() {
        var modules = new LinkedHashMap<String, KilaModule>();
        modules.put("main", new KilaMainTexture());
        var flipbook = new KilaMainTexture();
        flipbook.flipbook = true;
        flipbook.frameBlend = true;
        flipbook.randomRow = true;
        flipbook.motionVectors = true;
        flipbook.dispersion = true;
        flipbook.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        modules.put("main: flipbook, dispersion, world uv", flipbook);
        modules.put("layer", new KilaLayer());
        modules.put("layer 2", new KilaLayer(2));
        modules.put("mask 1", new KilaMask(1));
        var noisyMask = new KilaMask(2);
        noisyMask.texture.noise(KilaTexture.Noise.GRADIENT, 4);
        modules.put("mask 2: noise", noisyMask);
        modules.put("dissolve", new KilaDissolve());
        var dissolve = new KilaDissolve();
        dissolve.direction = KilaDissolve.Direction.TEXTURE;
        dissolve.edgeGradient = true;
        dissolve.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        modules.put("dissolve: direction texture, edge ramp, noise", dissolve);
        modules.put("distortion", new KilaDistortion());
        var flow = new KilaDistortion();
        flow.mode = KilaDistortion.Mode.FLOW_MAP;
        flow.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        modules.put("distortion: flow map, noise", flow);
        modules.put("uv effects", new KilaUvEffects());
        modules.put("projection", new KilaProjection());
        modules.put("screen", new KilaScreenDistortion());
        var screen = new KilaScreenDistortion();
        screen.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        screen.keepForeground = true;
        screen.chromatic = 0.5f;
        modules.put("screen: noise, depth, chroma", screen);
        var normalScreen = new KilaScreenDistortion();
        normalScreen.source = KilaScreenDistortion.Source.NORMAL;
        modules.put("screen: normal", normalScreen);
        var stacked = new KilaScreenDistortion();
        stacked.stacking = true;
        modules.put("screen: stacked", stacked);
        modules.put("ramp", new KilaRamp());
        modules.put("fresnel", new KilaFresnel());
        modules.put("vertex offset", new KilaVertexOffset());
        var vertex = new KilaVertexOffset();
        vertex.texture.noise(KilaTexture.Noise.SIMPLE, 4);
        modules.put("vertex offset: noise", vertex);
        modules.put("depth", new KilaDepth());
        var depth = new KilaDepth();
        depth.measure = KilaDepth.Measure.SURFACE;
        depth.contactGradient = true;
        depth.contactBreakup = 1;
        modules.put("depth: surface, contact ramp, breakup", depth);
        var texturedDepth = new KilaDepth();
        texturedDepth.contactBreakup = 1;
        texturedDepth.contactNoise.noise = KilaTexture.Noise.TEXTURE;
        modules.put("depth: textured breakup", texturedDepth);
        modules.put("shading", new KilaShading());
        var height = new KilaShading();
        height.bump = KilaShading.Bump.HEIGHT;
        modules.put("shading: height", height);
        var texturedHeight = new KilaShading();
        texturedHeight.bump = KilaShading.Bump.HEIGHT;
        texturedHeight.height.noise = KilaTexture.Noise.TEXTURE;
        modules.put("shading: textured height", texturedHeight);
        var mapped = new KilaShading();
        mapped.bump = KilaShading.Bump.NORMAL_MAP;
        mapped.model = KilaShading.Model.SIX_WAY;
        modules.put("shading: normal map, six-way", mapped);
        modules.put("matcap", new KilaMatcap());
        modules.put("volume", new KilaVolume());
        var clipped = new KilaVolume();
        clipped.clip = true;
        modules.put("volume: clipped", clipped);
        modules.put("camera fade", new KilaSimpleModules.CameraFade());
        modules.put("colour adjust", new KilaSimpleModules.ColorAdjust());
        modules.put("pixelate", new KilaSimpleModules.Pixelate());
        modules.put("lit", new KilaSimpleModules.Lit());
        return modules;
    }

    /** What the pipeline adds to the layout's own defines (KilaPipelines.build, PhotonPipelines.applyGeometry). */
    private static Set<String> pipelineDefines(KilaLayout layout, boolean instanced) {
        var defined = new HashSet<String>();
        for (var define : layout.defines) defined.add(define.split(" ", 2)[0]);
        if (layout.readsSceneDepth()) defined.add(PhotonPipelines.SOFT_PARTICLE_DEFINE);
        if (layout.lit) defined.add(PhotonPipelines.LIT_DEFINE);
        if (instanced) defined.add("PARTICLE_INSTANCE");
        return defined;
    }

    /**
     * The one thing per-variant blocks ask of the shaders: a member is read only where the variant declares it. GLSL
     * rejects an undeclared name even in a function nobody calls, so every line the preprocessor keeps counts.
     */
    @Test
    void everyMemberAVariantReadsIsInItsBlock() throws IOException {
        var missing = new ArrayList<String>();
        for (var variant : variants().entrySet()) {
            var layout = variant.getValue();
            for (var stage : List.of("core/kila.vsh", "core/kila.fsh")) {
                for (boolean instanced : new boolean[]{false, true}) {
                    for (var read : Preprocessor.reads(stage, pipelineDefines(layout, instanced))) {
                        if (layout.block.field(read.name()) == null) {
                            missing.add(variant.getKey() + ": " + read.name() + " at " + read.where());
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "read but not declared:\n" + String.join("\n", missing.stream().distinct().toList()));
    }

    /** And the other half: what the material writes, it declared — or the value never reaches the program. */
    @Test
    void everyValueAVariantWritesIsDeclared() {
        var undeclared = new ArrayList<String>();
        for (var preset : KilaPresets.ALL) {
            var material = preset.create();
            for (boolean preview : new boolean[]{false, true}) {
                var layout = material.layout(preview);
                var values = new KilaValues(preset.id(), layout.block);
                material.pack(values, layout, preview);
                values.undeclared().forEach(name -> undeclared.add(preset.id() + ": " + name));
            }
        }
        float[] scratch = new float[4];
        for (var entry : modules().entrySet()) {
            var module = entry.getValue();
            module.setEnable(true);
            var layout = KilaLayout.surface().build(List.of(module));
            var values = new KilaValues(entry.getKey(), layout.block);
            module.apply(values, false, scratch);
            values.undeclared().forEach(name -> undeclared.add(entry.getKey() + ": " + name));
        }
        assertTrue(undeclared.isEmpty(), "written but not declared:\n" + String.join("\n", undeclared));
    }

    /**
     * Just enough of a GLSL preprocessor for the Kila shaders: {@code photon:} imports inlined once each, comments
     * dropped, and {@code #ifdef}/{@code #ifndef}/{@code #if defined(..) || ..}/{@code #elif}/{@code #else} followed.
     * Anything it does not understand fails the test rather than being guessed at.
     */
    private static final class Preprocessor {
        record Read(String name, String where) {
        }

        private record Line(String file, int number, String text) {
        }

        static List<Read> reads(String stage, Set<String> defines) throws IOException {
            var lines = new ArrayList<Line>();
            inline(stage, lines, new HashSet<>());
            var defined = new HashSet<>(defines);
            var reads = new ArrayList<Read>();
            // per open #if: {this branch is live, some branch was taken, the enclosing one is live}
            var stack = new ArrayDeque<boolean[]>();
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                var text = line.text().strip();
                while (text.endsWith("\\") && i + 1 < lines.size()) {
                    text = text.substring(0, text.length() - 1) + " " + lines.get(++i).text().strip();
                }
                boolean live = stack.isEmpty() || stack.peek()[0];
                if (text.startsWith("#")) {
                    var directive = text.substring(1).strip();
                    var keyword = directive.split("\\s+", 2)[0];
                    var rest = directive.substring(keyword.length()).strip();
                    switch (keyword) {
                        case "ifdef" -> stack.push(open(live, defined.contains(rest)));
                        case "ifndef" -> stack.push(open(live, !defined.contains(rest)));
                        case "if" -> stack.push(open(live, live && new Condition(rest, defined).evaluate()));
                        case "elif" -> {
                            var top = stack.peek();
                            boolean take = top[2] && !top[1] && new Condition(rest, defined).evaluate();
                            top[0] = take;
                            top[1] |= take;
                        }
                        case "else" -> {
                            var top = stack.peek();
                            top[0] = top[2] && !top[1];
                            top[1] = true;
                        }
                        case "endif" -> stack.pop();
                        case "define" -> {
                            if (!live) break;
                            defined.add(rest.split("[\\s(]", 2)[0]);
                            collect(rest, line, reads);
                        }
                        case "undef" -> {
                            if (live) defined.remove(rest);
                        }
                        case "version", "moj_import", "extension" -> {
                        }
                        default -> fail("unknown directive " + text + " at " + line.file() + ":" + line.number());
                    }
                    continue;
                }
                if (live) collect(text, line, reads);
            }
            assertTrue(stack.isEmpty(), stage + ": unbalanced #if");
            return reads;
        }

        private static boolean[] open(boolean enclosing, boolean taken) {
            return new boolean[]{enclosing && taken, enclosing && taken, enclosing};
        }

        private static void collect(String code, Line line, List<Read> reads) {
            var matcher = MEMBER.matcher(code);
            while (matcher.find()) {
                if (!NOT_A_MEMBER.matcher(matcher.group(1)).matches()) {
                    reads.add(new Read(matcher.group(1), line.file() + ":" + line.number()));
                }
            }
        }

        /** {@code path} with its {@code photon:} imports in place, each once, comments blanked line for line. */
        private static void inline(String path, List<Line> into, Set<String> seen) throws IOException {
            if (!seen.add(path)) return;
            var source = stripComments(shader(path));
            var lines = source.split("\n", -1);
            var importLine = Pattern.compile("^\\s*#moj_import\\s+<photon:([\\w/]+\\.glsl)>");
            for (int i = 0; i < lines.length; i++) {
                var matcher = importLine.matcher(lines[i]);
                if (matcher.find()) {
                    inline("include/" + matcher.group(1), into, seen);
                } else {
                    into.add(new Line(path, i + 1, lines[i]));
                }
            }
        }

        /** {@code //} and {@code /* *\/} comments to spaces, newlines kept so line numbers still point right. */
        private static String stripComments(String source) {
            var out = new StringBuilder(source.length());
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') i++;
                    if (i < source.length()) out.append('\n');
                } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                        if (source.charAt(i) == '\n') out.append('\n');
                        i++;
                    }
                    i++;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    /** {@code defined(X)}, {@code defined X}, {@code !}, {@code &&}, {@code ||} and parentheses, nothing more. */
    private static final class Condition {
        private final List<String> tokens = new ArrayList<>();
        private final Set<String> defined;
        private int at;

        Condition(String text, Set<String> defined) {
            this.defined = defined;
            var matcher = Pattern.compile("\\|\\||&&|!|\\(|\\)|\\w+").matcher(text);
            while (matcher.find()) tokens.add(matcher.group());
        }

        boolean evaluate() {
            boolean value = or();
            assertEquals(tokens.size(), at, "cannot read #if " + String.join(" ", tokens));
            return value;
        }

        private boolean or() {
            boolean value = and();
            while (at < tokens.size() && tokens.get(at).equals("||")) {
                at++;
                value |= and();
            }
            return value;
        }

        private boolean and() {
            boolean value = unary();
            while (at < tokens.size() && tokens.get(at).equals("&&")) {
                at++;
                value &= unary();
            }
            return value;
        }

        private boolean unary() {
            var token = tokens.get(at++);
            if (token.equals("!")) return !unary();
            if (token.equals("(")) {
                boolean value = or();
                assertEquals(")", tokens.get(at++));
                return value;
            }
            if (token.equals("defined")) {
                boolean parenthesised = tokens.get(at).equals("(");
                if (parenthesised) at++;
                boolean value = defined.contains(tokens.get(at++));
                if (parenthesised) assertEquals(")", tokens.get(at++));
                return value;
            }
            if (token.matches("\\d+")) return Integer.parseInt(token) != 0;
            fail("cannot read #if " + String.join(" ", tokens));
            return false;
        }
    }
}
