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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts 1.21-format Photon custom fragment shaders to the 26.1 define-based contract
 * (see {@code assets/photon/shaders/core/circle.fsh} for the reference output):
 * <ul>
 *   <li>{@code #version} → 330; {@code #moj_import <fog.glsl>} → {@code <minecraft:fog.glsl>};
 *       {@code minecraft:dynamictransforms.glsl} added when ColorModulator is used,
 *       {@code minecraft:globals.glsl} when GameTime/ScreenSize are used.</li>
 *   <li>Engine uniforms (matrices/fog/ColorModulator/GameTime/ScreenSize) — declarations removed,
 *       the std140 blocks provide them.</li>
 *   <li>Custom value uniforms — declarations removed; scalars become {@code #ifndef}-guarded defines
 *       keeping the uniform name, vecN become {@code NAME_X.._W} defines plus a
 *       {@code const vecN NAME = ...} so the body compiles verbatim. Defaults come from the sibling
 *       shader JSON.</li>
 *   <li>{@code linear_fog(c, vertexDistance, FogStart, FogEnd, FogColor)} →
 *       {@code apply_fog(...)} with the 26.1 spherical/cylindrical distances.</li>
 *   <li>Sampler declarations are kept (still plain uniforms in 26.1).</li>
 *   <li>Dynamic {@code U_*} uniforms are frozen (0 / identity) with a warning — their runtime
 *       returns with the M3 engine UBO. Scene samplers keep compiling but sample the missing
 *       texture until M3's scene capture.</li>
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
    private static final String[] COMPONENT_SUFFIX = {"_X", "_Y", "_Z", "_W"};

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
        boolean usesGlobals = source.contains("GameTime") || source.contains("ScreenSize");
        boolean usesEngineBlock = PHOTON_ENGINE_UNIFORMS.stream().anyMatch(source::contains);

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
                if (rewritten.contains("photon:")) {
                    warnings.add("imports " + trimmed.replace("#moj_import", "").trim()
                            + " — photon includes are not available yet (M3); the shader will not compile until then");
                }
                imports.add(rewritten.trim());
                continue;
            }
            var uniform = UNIFORM_DECL.matcher(line);
            if (uniform.matches()) {
                var type = uniform.group(1);
                var name = uniform.group(2);
                if (type.equals("sampler2D")) {
                    body.add(line); // samplers stay plain uniforms
                    if (name.startsWith("SamplerScene")) {
                        // SamplerScene* bind the live PhotonSceneCapture views at draw (M3 scene capture)
                    }
                } else if (ENGINE_UNIFORMS.contains(name) || PHOTON_ENGINE_UNIFORMS.contains(name)) {
                    // dropped: provided by the std140 blocks / imports (U_* via photon:engine.glsl)
                } else if (name.startsWith("U_")) {
                    frozenDynamics.put(name, type);
                    warnings.add("unknown dynamic uniform " + name + " frozen at "
                            + (type.equals("mat4") ? "identity" : "0"));
                } else {
                    customValues.put(name, type);
                }
                continue;
            }
            if (trimmed.equals("in float vertexDistance;")) {
                body.add("in float sphericalVertexDistance;");
                body.add("in float cylindricalVertexDistance;");
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
        if (joined.contains("vertexDistance") && !joined.contains("sphericalVertexDistance")) {
            warnings.add("vertexDistance is still referenced — replace with sphericalVertexDistance manually");
        }

        var out = new StringBuilder("#version 330\n\n");
        out.append("// converted to the Photon 26.1 custom-shader contract: the 1.21 plain uniforms live in\n");
        out.append("// the fixed PhotonCustomMaterial std140 block (dynamic values, no recompiles); the shader\n");
        out.append("// JSON stays as the material configurator's uniform metadata\n\n");
        if (!imports.contains("#moj_import <minecraft:fog.glsl>") && joined.contains("apply_fog")) {
            imports.add("#moj_import <minecraft:fog.glsl>");
        }
        if (usesColorModulator) {
            imports.add("#moj_import <minecraft:dynamictransforms.glsl>");
        }
        if (usesGlobals) {
            imports.add("#moj_import <minecraft:globals.glsl>");
        }
        if (usesEngineBlock) {
            imports.add("#moj_import <photon:engine.glsl>");
        }
        imports.stream().distinct().forEach(imp -> out.append(imp).append('\n'));
        out.append('\n');

        if (!customValues.isEmpty()) {
            // members SORTED BY NAME — PhotonCustomUniforms packs in the same order; block members
            // are global scope in GLSL, so every usage site reads them unchanged
            out.append("layout(std140) uniform PhotonCustomMaterial {\n");
            customValues.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> out.append("    ").append(e.getValue()).append(' ')
                            .append(e.getKey()).append(";\n"));
            out.append("};\n\n");
        }
        for (var entry : frozenDynamics.entrySet()) {
            var type = entry.getValue();
            if (type.equals("mat4")) {
                out.append("const mat4 ").append(entry.getKey()).append(" = mat4(1.0); // frozen until M3\n");
            } else if (type.equals("float") || type.equals("int")) {
                appendGuardedDefine(out, entry.getKey(), 0f);
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

    private static void appendGuardedDefine(StringBuilder out, String name, float value) {
        out.append("#ifndef ").append(name).append('\n')
                .append("#define ").append(name).append(' ')
                .append(String.format(Locale.ROOT, "%s", value)).append('\n')
                .append("#endif\n");
    }

    private static int componentCount(String glslType) {
        return switch (glslType) {
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> 1;
        };
    }

    private static float valueAt(float[] values, int index) {
        return index < values.length ? values[index] : 0f;
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
     * Convert every legacy {@code shaders/core/*.fsh} under {@code assetsRoot} in place, backing the
     * original up as {@code <name>.fsh.bak_1_21} (never overwritten). Custom {@code .vsh} files are
     * reported but untouched — the 26.1 contract renders through the shared Photon vertex stage.
     */
    public static List<Report> convertDirectory(Path assetsRoot) throws IOException {
        var reports = new ArrayList<Report>();
        if (!Files.isDirectory(assetsRoot)) {
            return reports;
        }
        try (Stream<Path> files = Files.walk(assetsRoot)) {
            for (Path file : files.filter(f -> f.toString().replace('\\', '/').contains("/shaders/core/")).toList()) {
                var name = file.getFileName().toString();
                if (name.endsWith(".vsh")) {
                    // custom vertex shaders ARE supported (the JSON's "vertex" field selects them);
                    // their 1.21→26.1 deltas (std140 transform imports, spherical/cylindrical fog,
                    // sample_lightmap, custom-uniform block) are still hand-applied — auto-conversion
                    // of the vertex stage (fog position space + custom varyings/logic) is a follow-up
                    reports.add(new Report(file, false, List.of(
                            "custom vertex shader — convert by hand (see scan.vsh) until auto-conversion lands")));
                } else if (name.endsWith(".fsh")) {
                    var source = Files.readString(file, StandardCharsets.UTF_8);
                    var jsonPath = file.resolveSibling(name.substring(0, name.length() - 4) + ".json");
                    var defaults = Files.isRegularFile(jsonPath)
                            ? parseJsonDefaults(Files.readString(jsonPath, StandardCharsets.UTF_8))
                            : Map.<String, float[]>of();
                    var conversion = convertFragment(source, defaults);
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
