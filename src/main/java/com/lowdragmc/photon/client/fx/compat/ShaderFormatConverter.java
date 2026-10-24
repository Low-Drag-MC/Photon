package com.lowdragmc.photon.client.fx.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.LitParticles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts 1.21-format Photon custom shaders — both stages — to the 26.1 contract
 * (see {@code assets/photon/shaders/core/circle.fsh} for the reference output):
 * <ul>
 *   <li>{@code #version} → 330, and {@code #moj_import} paths gain their {@code minecraft:} namespace.
 *       The blocks a stage needs are added: {@code dynamictransforms} for ModelViewMat/ColorModulator,
 *       {@code projection} for ProjMat, {@code globals} for GameTime, {@code sample_lightmap} when a
 *       lightmap fetch was rewritten, {@code photon:engine} for the {@code U_*} values.</li>
 *   <li>Engine uniform declarations (matrices / fog / ColorModulator / GameTime / ScreenSize) are
 *       removed — std140 blocks provide them, and leaving a loose declaration is a LINK error.</li>
 *   <li>Custom value uniforms move into the fixed {@code PhotonCustomMaterial} std140 block. Its members
 *       come from the sibling JSON, which is what the runtime packs from, so both stages emit the same
 *       set and every usage site still reads them by name; one the JSON does not list is frozen at 0.</li>
 *   <li>Fog: {@code linear_fog(...)} → {@code apply_fog(...)}, and in the vertex stage the single
 *       {@code vertexDistance}/{@code fog_distance} becomes the spherical/cylindrical pair.</li>
 *   <li>{@code ScreenSize} → {@code U_ViewPort.zw}: 26.1 renders the editor scene into its own
 *       widget-sized texture, so the window size is the wrong divisor for a scene-capture UV.</li>
 *   <li>Sampler declarations are kept (still plain uniforms in 26.1); {@code SamplerScene*} get the
 *       live capture bound at draw. An unknown {@code U_*} is frozen at 0 with a warning.</li>
 *   <li>The includes a 1.21 shader may import keep working: {@code photon:lit_particle.glsl}'s own json entries
 *       stay out of the block (26.1 declares the lights on the pipeline, see {@link LitParticles}), and
 *       {@code photon:soft_particle.glsl}'s fade takes its parameters as an argument now — the 1.21 one-argument
 *       call is given the {@code SoftParticleParams} the shader listed.</li>
 * </ul>
 * Pure text transform — unit-testable, also runnable standalone via {@link #main} on an assets directory.
 */
public final class ShaderFormatConverter {

    /** Uniforms owned by the engine (now std140 blocks) — declaration stripped, no define. */
    private static final List<String> ENGINE_UNIFORMS = List.of(
            "ModelViewMat", "ProjMat", "IViewRotMat", "ColorModulator", "FogStart", "FogEnd",
            "FogColor", "FogShape", "GameTime", "ScreenSize", "LineWidth");

    /** 1.21 dynamic uniforms now provided by the {@code photon:engine.glsl} std140 block. */
    private static final List<String> PHOTON_ENGINE_UNIFORMS = List.of(
            "U_CameraPosition", "U_InverseProjectionMatrix", "U_InverseViewMatrix", "U_ViewPort");

    private static final Pattern UNIFORM_DECL = Pattern.compile(
            "^\\s*uniform\\s+(float|int|vec2|vec3|vec4|mat4|sampler2D)\\s+(\\w+)\\s*;\\s*(//.*)?$");
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*#version\\s+\\d+(\\s+\\w+)?\\s*$");
    private static final Pattern LINEAR_FOG_CALL = Pattern.compile(
            "linear_fog\\(\\s*(.+?)\\s*,\\s*vertexDistance\\s*,\\s*FogStart\\s*,\\s*FogEnd\\s*,\\s*FogColor\\s*\\)");
    /** Whole-word {@code ScreenSize} reads (the declaration is already stripped as an engine uniform). */
    private static final Pattern SCREEN_SIZE_REF = Pattern.compile("\\bScreenSize\\b");
    private static final Pattern PHOTON_INCLUDE = Pattern.compile("<photon:([\\w./]+)>");
    /** The includes a custom shader may import (assets/photon/shaders/include); the rest are Photon's own passes'. */
    static final List<String> SHIPPED_PHOTON_INCLUDES = List.of("particle.glsl", "particle_utils.glsl", "engine.glsl",
            "lit_particle.glsl", "dynamic_light.glsl", "soft_particle.glsl");
    /** 1.21: {@code photon_soft_particle(color)}, reading the {@code SoftParticleParams} uniform the include declared. */
    private static final Pattern SOFT_PARTICLE_CALL = Pattern.compile("\\bphoton_soft_particle\\s*\\(");
    private static final String SOFT_PARTICLE_PARAMS = "SoftParticleParams";
    private static final String SOFT_PARTICLE_INCLUDE = "soft_particle.glsl";
    /** 1.21 vertex stage: {@code vertexDistance = fog_distance(<pos>, FogShape);} — 26.1 split the single
     *  distance into a spherical and a cylindrical one and dropped the FogShape uniform. */
    private static final Pattern FOG_DISTANCE_ASSIGN = Pattern.compile(
            "(^[ \\t]*)(\\w+)\\s*=\\s*fog_distance\\(\\s*(.+?)\\s*,\\s*FogShape\\s*\\)\\s*;",
            Pattern.MULTILINE);
    /** 1.21 lightmap fetch: {@code texelFetch(Sampler2, <uv> / 16, 0)} → {@code sample_lightmap(Sampler2, <uv>)}. */
    private static final Pattern LIGHTMAP_FETCH = Pattern.compile(
            "texelFetch\\(\\s*Sampler2\\s*,\\s*(.+?)\\s*/\\s*16\\s*,\\s*0\\s*\\)");

    public record Conversion(String source, List<String> warnings, boolean changed) {
    }

    public record Report(Path file, boolean converted, List<String> warnings) {
    }

    private ShaderFormatConverter() {
    }

    /** Whether the fragment source still uses the 1.21 uniform contract. */
    public static boolean isLegacyFragment(String source) {
        if (source.contains("minecraft:fog.glsl") || source.contains("minecraft:dynamictransforms.glsl")) {
            return false; // already 26.1
        }
        var matcher = UNIFORM_DECL.matcher("");
        for (String line : source.split("\n", -1)) {
            matcher.reset(line);
            if (matcher.matches() && !matcher.group(1).equals("sampler2D")) {
                return true; // any non-sampler uniform declaration = 1.21 contract
            }
        }
        return false;
    }

    /**
     * Convert one 1.21 fragment source. {@code jsonDefaults} maps uniform name → default values from
     * the sibling shader JSON (may be empty).
     */
    public static Conversion convertFragment(String source, Map<String, float[]> jsonDefaults) {
        return convert(source, jsonDefaults, Set.of(), false);
    }

    /** {@link #convertFragment(String, Map)}, knowing which json uniforms are {@code int}s ({@link #parseJsonInts}). */
    public static Conversion convertFragment(String source, Map<String, float[]> jsonDefaults, Set<String> jsonInts) {
        return convert(source, jsonDefaults, jsonInts, false);
    }

    /**
     * Convert one 1.21 <b>vertex</b> source. Same contract as {@link #convertFragment}, plus the deltas
     * that are vertex-only:
     * <ul>
     *   <li>{@code ModelViewMat}/{@code ProjMat} are no longer loose uniforms — they come from the
     *       {@code DynamicTransforms}/{@code Projection} std140 blocks. Leaving the declarations in is a
     *       LINK error ("already declared in the interface block"), which fails the pipeline and makes the
     *       material render nothing at all.</li>
     *   <li>{@code out float vertexDistance} → the spherical/cylindrical pair the 26.1 fragment stage reads,
     *       and {@code fog_distance(pos, FogShape)} → the two distance functions.</li>
     *   <li>{@code texelFetch(Sampler2, uv / 16, 0)} → {@code sample_lightmap(Sampler2, uv)}.</li>
     * </ul>
     */
    public static Conversion convertVertex(String source, Map<String, float[]> jsonDefaults) {
        return convert(source, jsonDefaults, Set.of(), true);
    }

    /** {@link #convertVertex(String, Map)}, knowing which json uniforms are {@code int}s ({@link #parseJsonInts}). */
    public static Conversion convertVertex(String source, Map<String, float[]> jsonDefaults, Set<String> jsonInts) {
        return convert(source, jsonDefaults, jsonInts, true);
    }

    private static Conversion convert(String source, Map<String, float[]> jsonDefaults, Set<String> jsonInts,
                                      boolean vertexStage) {
        if (!isLegacyFragment(source)) {
            return new Conversion(source, List.of(), false);
        }
        var warnings = new ArrayList<String>();
        var body = new ArrayList<String>();
        var imports = new ArrayList<String>();
        // name → glsl type of stripped custom value uniforms (insertion order for stable output)
        var customValues = new LinkedHashMap<String, String>();
        var frozenDynamics = new LinkedHashMap<String, String>();

        boolean usesColorModulator = source.contains("ColorModulator");
        boolean usesGlobals = source.contains("GameTime");
        // ScreenSize -> U_ViewPort.zw (see the rewrite below), so it pulls in the engine block, not Globals
        boolean usesEngineBlock = PHOTON_ENGINE_UNIFORMS.stream().anyMatch(source::contains)
                || source.contains("ScreenSize");

        for (String line : source.split("\n", -1)) {
            if (VERSION_LINE.matcher(line).matches()) {
                continue; // re-emitted at the top
            }
            var trimmed = line.trim();
            if (trimmed.startsWith("#moj_import")) {
                var rewritten = line
                        .replace("<fog.glsl>", "<minecraft:fog.glsl>")
                        .replace("<projection.glsl>", "<minecraft:projection.glsl>")
                        .replace("<globals.glsl>", "<minecraft:globals.glsl>")
                        .replace("<dynamictransforms.glsl>", "<minecraft:dynamictransforms.glsl>");
                var photonInclude = PHOTON_INCLUDE.matcher(rewritten);
                if (photonInclude.find() && !SHIPPED_PHOTON_INCLUDES.contains(photonInclude.group(1))) {
                    warnings.add("imports " + trimmed.replace("#moj_import", "").trim()
                            + " — not an include a 26.1 custom shader can use; the shader will not compile");
                }
                imports.add(rewritten.trim());
                continue;
            }
            var uniform = UNIFORM_DECL.matcher(line);
            if (uniform.matches()) {
                var type = uniform.group(1);
                var name = uniform.group(2);
                if (type.equals("sampler2D")) {
                    // samplers stay plain uniforms; SamplerScene* get the live capture bound at draw
                    body.add(line);
                } else if (ENGINE_UNIFORMS.contains(name) || PHOTON_ENGINE_UNIFORMS.contains(name)) {
                    continue; // provided by the std140 blocks / imports (U_* via photon:engine.glsl)
                } else if (name.startsWith("U_")) {
                    frozenDynamics.put(name, type);
                    warnings.add("unknown dynamic uniform " + name + " frozen at "
                            + (type.equals("mat4") ? "identity" : "0"));
                } else {
                    customValues.put(name, type);
                }
                continue;
            }
            if (trimmed.equals("in float vertexDistance;") || trimmed.equals("out float vertexDistance;")) {
                var direction = vertexStage ? "out" : "in";
                body.add(direction + " float sphericalVertexDistance;");
                body.add(direction + " float cylindricalVertexDistance;");
                continue;
            }
            body.add(line);
        }

        // fog call rewrite on the collected body
        var joined = String.join("\n", body);
        joined = LINEAR_FOG_CALL.matcher(joined).replaceAll(match ->
                "apply_fog(" + Matcher.quoteReplacement(match.group(1))
                        + ", sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, "
                        + "FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)");
        if (joined.contains("linear_fog")) {
            warnings.add("a linear_fog call did not match the standard pattern — port it to apply_fog manually");
        }
        var usesLightmapFetch = false;
        if (vertexStage) {
            // one distance in, two out — keep the original indentation so the body still reads naturally
            joined = FOG_DISTANCE_ASSIGN.matcher(joined).replaceAll(match -> {
                var indent = Matcher.quoteReplacement(match.group(1));
                var position = Matcher.quoteReplacement(match.group(3));
                return indent + "sphericalVertexDistance = fog_spherical_distance(" + position + ");\n"
                        + indent + "cylindricalVertexDistance = fog_cylindrical_distance(" + position + ");";
            });
            if (joined.contains("fog_distance")) {
                warnings.add("a fog_distance call did not match the standard pattern — port it to "
                        + "fog_spherical_distance/fog_cylindrical_distance manually");
            }
            var lightmap = LIGHTMAP_FETCH.matcher(joined);
            usesLightmapFetch = lightmap.find();
            joined = lightmap.reset().replaceAll(match ->
                    "sample_lightmap(Sampler2, " + Matcher.quoteReplacement(match.group(1)) + ")");
        }
        if (imports.stream().anyMatch(line -> line.contains("<photon:" + SOFT_PARTICLE_INCLUDE + ">"))) {
            joined = softParticleParams(joined, jsonDefaults.containsKey(SOFT_PARTICLE_PARAMS), warnings);
        }
        if (joined.contains("vertexDistance") && !joined.contains("sphericalVertexDistance")) {
            warnings.add("vertexDistance is still referenced — replace with sphericalVertexDistance manually");
        }
        // PIP draws the editor scene into a widget-sized texture: U_ViewPort.zw is that target's size, ScreenSize was
        // the window's
        joined = SCREEN_SIZE_REF.matcher(joined).replaceAll("U_ViewPort.zw");

        var out = new StringBuilder("#version 330\n\n");
        out.append("// converted to the Photon 26.1 custom-shader contract: the 1.21 plain uniforms live in\n");
        out.append("// the fixed PhotonCustomMaterial std140 block (dynamic values, no recompiles); the shader\n");
        out.append("// JSON stays as the material configurator's uniform metadata\n\n");
        // ModelViewMat/ColorModulator live in DynamicTransforms, ProjMat in Projection: their loose declarations were
        // dropped above, so the blocks are imported instead
        var added = new ArrayList<String>();
        if (joined.contains("apply_fog")) added.add("#moj_import <minecraft:fog.glsl>");
        if (usesColorModulator || source.contains("ModelViewMat")) added.add("#moj_import <minecraft:dynamictransforms.glsl>");
        if (source.contains("ProjMat")) added.add("#moj_import <minecraft:projection.glsl>");
        if (usesLightmapFetch) added.add("#moj_import <minecraft:sample_lightmap.glsl>");
        if (usesGlobals) added.add("#moj_import <minecraft:globals.glsl>");
        if (usesEngineBlock) added.add("#moj_import <photon:engine.glsl>");
        // ahead of the shader's own: a file is pasted where it is first imported, and an include may import one of
        // these under its #ifdef (soft_particle.glsl's engine.glsl), where a define left out would lose it
        Stream.concat(added.stream(), imports.stream()).distinct().forEach(imp -> out.append(imp).append('\n'));
        out.append('\n');

        // The members come from the JSON, which is what CustomShaderMaterial packs: a stage declaring a subset, or
        // one more, would read from the wrong offsets, and the two stages' blocks must be identical.
        var blockMembers = new TreeMap<String, String>();
        if (jsonDefaults.isEmpty()) blockMembers.putAll(customValues);
        jsonDefaults.forEach((name, values) -> {
            // the lights' own entries are the pipeline's in 26.1 — CustomShaderMaterial leaves them out of the block too
            if (ENGINE_UNIFORMS.contains(name) || PHOTON_ENGINE_UNIFORMS.contains(name)
                    || name.startsWith("U_") || LitParticles.declares(name)) {
                return;
            }
            // json ints are ints in both stages: CustomShaderMaterial packs a json "int" as one
            boolean isInt = jsonInts.contains(name);
            if (isInt && values.length > 1) {
                warnings.add("json int " + name + " has " + values.length + " values — 26.1 packs only the first");
            }
            blockMembers.put(name, isInt ? "int" : glslTypeFor(values.length));
        });
        var unlisted = new LinkedHashMap<String, String>();
        customValues.forEach((name, type) -> {
            if (blockMembers.containsKey(name)) return;
            unlisted.put(name, type);
            warnings.add("uniform " + name + " is not in the shader json, which is what 26.1 fills — frozen at 0");
        });
        // A stage that reads none of them declares no block at all (GLSL only requires the declarations to
        // AGREE where both stages have one) — that keeps a vertex stage which merely transforms positions
        // free of a block it never touches.
        var body_ = joined;
        var usesAnyMember = blockMembers.keySet().stream()
                .anyMatch(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(body_).find());
        if (!blockMembers.isEmpty() && usesAnyMember) {
            // members SORTED BY NAME — PhotonCustomUniforms packs in the same order; block members
            // are global scope in GLSL, so every usage site reads them unchanged
            out.append("layout(std140) uniform PhotonCustomMaterial {\n");
            blockMembers.forEach((name, type) ->
                    out.append("    ").append(type).append(' ').append(name).append(";\n"));
            out.append("};\n\n");
        }
        for (var entry : frozenDynamics.entrySet()) {
            var type = entry.getValue();
            if (type.equals("mat4")) {
                out.append("const mat4 ").append(entry.getKey()).append(" = mat4(1.0); // no 26.1 source\n");
            } else if (type.equals("float") || type.equals("int")) {
                appendGuardedDefine(out, entry.getKey());
            } else {
                out.append("const ").append(type).append(' ').append(entry.getKey())
                        .append(" = ").append(type).append("(0.0); // no 26.1 source\n");
            }
        }
        unlisted.forEach((name, type) -> out.append("const ").append(type).append(' ').append(name).append(" = ")
                .append(type).append("(0); // not in the shader json\n"));
        if (!customValues.isEmpty() || !frozenDynamics.isEmpty()) {
            out.append('\n');
        }
        out.append(joined.strip()).append('\n');
        return new Conversion(out.toString(), warnings, true);
    }

    /**
     * 1.21's one-argument {@code photon_soft_particle} call gets the listed {@code SoftParticleParams}, else
     * {@code vec4(0.0)} (fade off, as 1.21 read it).
     */
    private static String softParticleParams(String body, boolean listed, List<String> warnings) {
        var matcher = SOFT_PARTICLE_CALL.matcher(body);
        var out = new StringBuilder();
        int copied = 0;
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = closingParen(body, open);
            if (close < 0) {
                warnings.add("a photon_soft_particle call has unbalanced parentheses — port it by hand");
                break;
            }
            out.append(body, copied, close);
            if (!hasTopLevelComma(body, open + 1, close)) {
                out.append(", ").append(listed ? SOFT_PARTICLE_PARAMS : "vec4(0.0)");
            }
            copied = close;
        }
        return out.append(body, copied, body.length()).toString();
    }

    private static int closingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static boolean hasTopLevelComma(String text, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return true;
        }
        return false;
    }

    /** An unknown {@code U_*} dynamic uniform, frozen at 0 so the shader still compiles. */
    private static void appendGuardedDefine(StringBuilder out, String name) {
        out.append("#ifndef ").append(name).append('\n')
                .append("#define ").append(name).append(" 0.0\n")
                .append("#endif\n");
    }

    /** JSON {@code count} → GLSL type, mirroring {@code CustomShaderMaterial.jsonUniformType}. */
    private static String glslTypeFor(int componentCount) {
        return switch (componentCount) {
            case 2 -> "vec2";
            case 3 -> "vec3";
            case 4 -> "vec4";
            case 16 -> "mat4";
            default -> "float";
        };
    }

    /** The names of a 1.21 shader JSON's {@code "type": "int"} uniforms. */
    public static Set<String> parseJsonInts(String json) {
        var ints = new HashSet<String>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("uniforms")) {
                for (var element : root.getAsJsonArray("uniforms")) {
                    var uniform = element.getAsJsonObject();
                    if (uniform.has("type") && "int".equals(uniform.get("type").getAsString())) {
                        ints.add(uniform.get("name").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // tolerated, as the defaults are
        }
        return ints;
    }

    /** Parse a 1.21 shader JSON's {@code uniforms} array into name → default values. */
    public static Map<String, float[]> parseJsonDefaults(String json) {
        var defaults = new LinkedHashMap<String, float[]>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("uniforms")) {
                for (var element : root.getAsJsonArray("uniforms")) {
                    var uniform = element.getAsJsonObject();
                    var values = uniform.getAsJsonArray("values");
                    var array = new float[values.size()];
                    for (int i = 0; i < array.length; i++) {
                        array[i] = values.get(i).getAsFloat();
                    }
                    defaults.put(uniform.get("name").getAsString(), array);
                }
            }
        } catch (Exception ignored) {
            // tolerated: defaults simply fall back to 0
        }
        return defaults;
    }

    /**
     * Convert every legacy {@code shaders/core/*.fsh} and {@code *.vsh} under {@code assetsRoot} in place,
     * backing each original up as {@code <name>.bak_1_21} (never overwritten). Both stages read the same
     * sibling JSON, so their {@code PhotonCustomMaterial} blocks come out identical — which GLSL requires.
     */
    public static List<Report> convertDirectory(Path assetsRoot) throws IOException {
        var reports = new ArrayList<Report>();
        if (!Files.isDirectory(assetsRoot)) {
            return reports;
        }
        try (Stream<Path> files = Files.walk(assetsRoot)) {
            for (Path file : files.filter(f -> f.toString().replace('\\', '/').contains("/shaders/core/")).toList()) {
                var name = file.getFileName().toString();
                var vertexStage = name.endsWith(".vsh");
                if (!vertexStage && !name.endsWith(".fsh")) {
                    continue;
                }
                var source = Files.readString(file, StandardCharsets.UTF_8);
                var jsonPath = file.resolveSibling(name.substring(0, name.length() - 4) + ".json");
                var json = Files.isRegularFile(jsonPath) ? Files.readString(jsonPath, StandardCharsets.UTF_8) : null;
                var defaults = json == null ? Map.<String, float[]>of() : parseJsonDefaults(json);
                var ints = json == null ? Set.<String>of() : parseJsonInts(json);
                var conversion = vertexStage
                        ? convertVertex(source, defaults, ints) : convertFragment(source, defaults, ints);
                if (!conversion.changed()) {
                    reports.add(new Report(file, false, List.of("already 26.1 format")));
                    continue;
                }
                var backup = file.resolveSibling(name + ".bak_1_21");
                if (!Files.exists(backup)) {
                    Files.writeString(backup, source, StandardCharsets.UTF_8);
                }
                Files.writeString(file, conversion.source(), StandardCharsets.UTF_8);
                reports.add(new Report(file, true, conversion.warnings()));
            }
        }
        return reports;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ShaderFormatConverter <assets-dir>");
            return;
        }
        for (var report : convertDirectory(Path.of(args[0]))) {
            System.out.println((report.converted() ? "[converted] " : "[skipped]   ") + report.file());
            for (var warning : report.warnings()) {
                System.out.println("            ! " + warning);
            }
        }
    }
}
