package com.lowdragmc.photon.client.fx.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       set and every usage site still reads them by name.</li>
 *   <li>Fog: {@code linear_fog(...)} → {@code apply_fog(...)}, and in the vertex stage the single
 *       {@code vertexDistance}/{@code fog_distance} becomes the spherical/cylindrical pair.</li>
 *   <li>{@code ScreenSize} → {@code U_ViewPort.zw}: 26.1 renders the editor scene into its own
 *       widget-sized texture, so the window size is the wrong divisor for a scene-capture UV.</li>
 *   <li>Sampler declarations are kept (still plain uniforms in 26.1); {@code SamplerScene*} get the
 *       live capture bound at draw. An unknown {@code U_*} is frozen at 0 with a warning.</li>
 * </ul>
 * Pure text transform — no Minecraft classes, unit-testable, also runnable standalone via
 * {@link #main} on an assets directory.
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
    /** Includes Photon actually ships (assets/photon/shaders/include) — importing anything else is fatal. */
    private static final List<String> SHIPPED_PHOTON_INCLUDES = List.of(
            "particle.glsl", "particle_utils.glsl", "engine.glsl");
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
        return convert(source, jsonDefaults, false);
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
        return convert(source, jsonDefaults, true);
    }

    private static Conversion convert(String source, Map<String, float[]> jsonDefaults, boolean vertexStage) {
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
                            + " — no such photon include ships in 26.1; the shader will not compile");
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
        if (joined.contains("vertexDistance") && !joined.contains("sphericalVertexDistance")) {
            warnings.add("vertexDistance is still referenced — replace with sphericalVertexDistance manually");
        }
        // ScreenSize -> U_ViewPort.zw. 1.21 always rendered fx into a window-sized target (the editor scene
        // was a sub-viewport of one), so `gl_FragCoord.xy / ScreenSize` indexed the scene capture correctly.
        // 26.1's PIP gives the editor scene its OWN texture sized to the widget, and Photon sizes the capture
        // after the target — so the window size is the wrong divisor there (a stretched corner of the capture,
        // changing with the panel size). U_ViewPort.zw is the target Photon is actually drawing into.
        joined = SCREEN_SIZE_REF.matcher(joined).replaceAll("U_ViewPort.zw");

        var out = new StringBuilder("#version 330\n\n");
        out.append("// converted to the Photon 26.1 custom-shader contract: the 1.21 plain uniforms live in\n");
        out.append("// the fixed PhotonCustomMaterial std140 block (dynamic values, no recompiles); the shader\n");
        out.append("// JSON stays as the material configurator's uniform metadata\n\n");
        if (!imports.contains("#moj_import <minecraft:fog.glsl>") && joined.contains("apply_fog")) {
            imports.add("#moj_import <minecraft:fog.glsl>");
        }
        // ModelViewMat/ColorModulator live in DynamicTransforms, ProjMat in Projection — declaring either as
        // a loose uniform is a link error in 26.1, so the declarations were dropped above and the blocks
        // must be imported instead.
        if (usesColorModulator || source.contains("ModelViewMat")) {
            imports.add("#moj_import <minecraft:dynamictransforms.glsl>");
        }
        if (source.contains("ProjMat")) {
            imports.add("#moj_import <minecraft:projection.glsl>");
        }
        if (usesLightmapFetch) {
            imports.add("#moj_import <minecraft:sample_lightmap.glsl>");
        }
        if (usesGlobals) {
            imports.add("#moj_import <minecraft:globals.glsl>");
        }
        if (usesEngineBlock) {
            imports.add("#moj_import <photon:engine.glsl>");
        }
        imports.stream().distinct().forEach(imp -> out.append(imp).append('\n'));
        out.append('\n');

        // The block's members come from the JSON when it has any, NOT from what this stage declared.
        // CustomShaderMaterial builds the runtime PhotonCustomUniforms layout from the JSON's uniforms
        // array, so that is the authoritative member set: a stage declaring a subset would compute
        // different std140 offsets and read every value from the wrong place — and the two stages would
        // disagree, which GLSL rejects outright ("members of uniform block are not the same between
        // shader stages"). A vertex stage that uses one uniform still declares the whole set.
        var blockMembers = new TreeMap<>(customValues);
        jsonDefaults.forEach((name, values) -> {
            if (ENGINE_UNIFORMS.contains(name) || PHOTON_ENGINE_UNIFORMS.contains(name)
                    || name.startsWith("U_")) {
                return;
            }
            blockMembers.putIfAbsent(name, glslTypeFor(values.length));
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
                out.append("const mat4 ").append(entry.getKey()).append(" = mat4(1.0); // frozen until M3\n");
            } else if (type.equals("float") || type.equals("int")) {
                appendGuardedDefine(out, entry.getKey());
            } else {
                out.append("const ").append(type).append(' ').append(entry.getKey())
                        .append(" = ").append(type).append("(0.0); // frozen until M3\n");
            }
        }
        if (!customValues.isEmpty() || !frozenDynamics.isEmpty()) {
            out.append('\n');
        }
        out.append(joined.strip()).append('\n');
        return new Conversion(out.toString(), warnings, true);
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
                var defaults = Files.isRegularFile(jsonPath)
                        ? parseJsonDefaults(Files.readString(jsonPath, StandardCharsets.UTF_8))
                        : Map.<String, float[]>of();
                var conversion = vertexStage
                        ? convertVertex(source, defaults) : convertFragment(source, defaults);
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
