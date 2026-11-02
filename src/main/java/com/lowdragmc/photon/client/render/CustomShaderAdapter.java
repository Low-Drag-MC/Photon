package com.lowdragmc.photon.client.render;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts custom shaders written for earlier versions to 26.3's rules. 26.3 compiles both stages with shaderc to SPIR-V,
 * with no automatic location mapping, on OpenGL and Vulkan alike: every vertex input, varying and fragment output needs
 * an explicit {@code layout(location = N)}, the stages are paired by location (not by name), and includes are
 * {@code #include} rather than {@code #moj_import}. The adapter:
 * <ul>
 *   <li>turns {@code #moj_import} into {@code #include}, and raises {@code #version} to 330 with
 *       {@code GL_ARB_separate_shader_objects}, which locations on varyings need there;</li>
 *   <li>numbers the vertex inputs: they are paired with the vertex format by name, so any distinct locations do,
 *       after the ones {@code photon:particle.glsl} takes when the stage includes it. An input with an explicit
 *       location the pipeline does not provide becomes GL's default {@code (0, 0, 0, 1)}; one on a provided tail
 *       location is fed by name ({@link Result#tailInputs});</li>
 *   <li>numbers the vertex outputs in declaration order and gives each fragment input the location of the output of
 *       its name; a fragment input no vertex output writes becomes a zero constant;</li>
 *   <li>numbers the fragment outputs;</li>
 *   <li>joins {@code \} line continuations (GLSL 4.20+);</li>
 *   <li>writes the fragment stage's twin for order-independent transparency ({@link Result#oitFragment}).</li>
 * </ul>
 * Only global-scope declarations in active preprocessor branches are touched; declarations inside an include are the
 * include's to number.
 */
public final class CustomShaderAdapter {

    /** The input locations {@code photon:particle.glsl} takes at most (its {@code PHOTON_INPUT_LOCATIONS}). */
    public static final int PARTICLE_INPUT_LOCATIONS = 9;

    private CustomShaderAdapter() {
    }

    /** @param tailInputs tail location → the vertex input name reading it */
    /** {@code oitFragment}: the fragment stage for order-independent transparency, null when it can't have one. */
    public record Result(String vertex, String fragment, Map<Integer, String> tailInputs, boolean changed,
                         List<String> notes, @Nullable String oitFragment) {
    }

    private record Declaration(int line, String qualifiers, String storage, String type, String name, String array,
                               int location) {
        String declare(int location) {
            return "layout(location = " + location + ") " + qualifiers + storage + " " + type + " " + name + array
                    + ";";
        }
    }

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid)\\s+)*)(in|out)\\s+(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;\\s*$");
    private static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");
    private static final Pattern MOJ_IMPORT = Pattern.compile("^(\\s*)#moj_import(\\s*[<\"])");
    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\s+(\\d+)(\\s+\\w+)?\\s*$");
    private static final Pattern PARTICLE_INCLUDE = Pattern.compile("^\\s*#include\\s*<photon:particle\\.glsl>");
    private static final Pattern GL_VERTEX_ID = Pattern.compile("\\bgl_VertexID\\b");
    private static final Pattern GL_INSTANCE_ID = Pattern.compile("\\bgl_InstanceID\\b");
    private static final Pattern ARRAY_SIZE = Pattern.compile("\\[\\s*(\\d+)\\s*\\]");
    private static final String SEPARATE_SHADER_OBJECTS = "#extension GL_ARB_separate_shader_objects : require";
    /** Photon's fragment tail for order-independent transparency ({@code PhotonOit}). */
    static final String OIT_INCLUDE_PATH = "photon:oit.glsl";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Pattern FRAG_DEPTH = Pattern.compile("\\bgl_FragDepth\\b");

    /**
     * @param providedLocations vertex elements before the attribute tail
     * @param tailLocations     tail locations the pipeline provides
     */
    public static Result adapt(String vertexSource, String fragmentSource, int providedLocations,
                               Set<Integer> tailLocations, Set<String> defines) {
        return adapt(vertexSource, fragmentSource, providedLocations, tailLocations, defines, true);
    }

    /** {@code oit}: also build the fragment stage's order-independent twin ({@link Result#oitFragment}). */
    public static Result adapt(String vertexSource, String fragmentSource, int providedLocations,
                               Set<Integer> tailLocations, Set<String> defines, boolean oit) {
        var notes = new ArrayList<String>();
        var vertexLines = lines(joinContinuations(vertexSource, "vertex", notes));
        var fragmentLines = lines(joinContinuations(fragmentSource, "fragment", notes));
        header(vertexLines, "vertex", notes);
        header(fragmentLines, "fragment", notes);

        // ---- vertex inputs: paired with the vertex format by name, so any distinct locations do
        var tailInputs = new LinkedHashMap<Integer, String>();
        var nextInput = includesParticle(vertexLines) ? PARTICLE_INPUT_LOCATIONS : 0;
        var vertexOutputs = new ArrayList<Declaration>();
        for (var declaration : declarations(vertexLines, defines)) {
            if (declaration.storage().equals("out")) {
                vertexOutputs.add(declaration);
                continue;
            }
            var location = declaration.location();
            if (location >= providedLocations && !tailLocations.contains(location)) {
                var constant = attributeDefault(declaration.type());
                if (constant != null && declaration.array().isEmpty()) {
                    vertexLines.set(declaration.line(),
                            declaration.type() + " " + declaration.name() + " = " + constant + ";");
                    notes.add("vertex input " + declaration.name() + " (location " + location
                            + ") is not provided here: constant " + constant);
                    continue;
                }
            }
            if (location >= providedLocations) {
                tailInputs.put(location, declaration.name());
                notes.add("vertex input " + declaration.name() + ": reads attribute tail location " + location);
            }
            nextInput = renumber(vertexLines, declaration, nextInput, "vertex input", notes);
        }

        // ---- vertex outputs in declaration order, the fragment inputs at the location of the output of their name
        var outputsByName = new LinkedHashMap<String, Integer>();
        var nextVarying = 0;
        for (var output : vertexOutputs) {
            outputsByName.put(output.name(), nextVarying);
            nextVarying = renumber(vertexLines, output, nextVarying, "vertex output", notes);
        }
        var nextOutput = 0;
        var fragmentOutputs = new ArrayList<Declaration>();
        for (var declaration : declarations(fragmentLines, defines)) {
            if (declaration.storage().equals("out")) {
                fragmentOutputs.add(declaration);
                nextOutput = renumber(fragmentLines, declaration, nextOutput, "fragment output", notes);
                continue;
            }
            var location = outputsByName.get(declaration.name());
            if (location != null) {
                renumber(fragmentLines, declaration, location, "fragment input", notes);
                continue;
            }
            var zero = zero(declaration.type());
            if (zero == null || !declaration.array().isEmpty()) {
                continue;
            }
            fragmentLines.set(declaration.line(), declaration.type() + " " + declaration.name() + " = " + zero + ";");
            notes.add("fragment input " + declaration.name() + " is written by no vertex output: constant " + zero);
        }
        var oitFragment = oit ? wrapForOit(fragmentLines, fragmentOutputs) : null;

        var vertex = String.join("\n", vertexLines);
        var fragment = String.join("\n", fragmentLines);
        return new Result(vertex, fragment, Map.copyOf(tailInputs), !notes.isEmpty(), List.copyOf(notes), oitFragment);
    }

    /**
     * The fragment stage written through Photon's OIT tail ({@code photon:oit.glsl}): its one {@code vec4} output a
     * plain variable, {@code main} renamed, and a new {@code main} handing the variable to {@code photon_write}. Null
     * for anything else (several outputs, a depth write, a shader that handles OIT itself); a twin that doesn't compile
     * only keeps its material out of the OIT passes.
     */
    @Nullable
    private static String wrapForOit(List<String> source, List<Declaration> outputs) {
        if (outputs.size() != 1) {
            return null;
        }
        var output = outputs.getFirst();
        if (!output.type().equals("vec4") || !output.array().isEmpty()) {
            return null;
        }
        var code = stripComments(source);
        var text = String.join("\n", code);
        if (text.contains("executeAlphaOnlyPhase") || text.contains(OIT_INCLUDE_PATH) || text.contains("photon_write")
                || FRAG_DEPTH.matcher(text).find()) {
            return null;
        }
        var mainLine = -1;
        for (int i = 0; i < code.size(); i++) {
            if (MAIN.matcher(code.get(i)).find()) {
                if (mainLine >= 0) {
                    return null; // a prototype as well as the definition
                }
                mainLine = i;
            }
        }
        if (mainLine < 0) {
            return null;
        }
        var lines = new ArrayList<>(source);
        // renamed: vanilla's depth-bounds phase declares an output called fragColor of its own
        var variable = "photon_custom_" + output.name();
        var uses = Pattern.compile("\\b" + Pattern.quote(output.name()) + "\\b");
        lines.set(mainLine, MAIN.matcher(code.get(mainLine)).replaceFirst("void photon_custom_main()"));
        lines.set(output.line(), "vec4 " + variable + ";");
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, uses.matcher(lines.get(i)).replaceAll(Matcher.quoteReplacement(variable)));
        }
        lines.add(headerEnd(lines, code), "#include <" + OIT_INCLUDE_PATH + ">");
        lines.add("");
        lines.add("void main() {");
        lines.add("    photon_custom_main();");
        lines.add("    photon_write(" + variable + ");");
        lines.add("}");
        return String.join("\n", lines);
    }

    /** The line after {@code #version} and the {@code #extension}s that follow it. */
    private static int headerEnd(List<String> lines, List<String> code) {
        for (int i = 0; i < lines.size(); i++) {
            if (!VERSION.matcher(lines.get(i)).matches()) {
                continue;
            }
            var end = i + 1;
            for (int j = i + 1; j < code.size(); j++) {
                var line = code.get(j).trim();
                if (line.startsWith("#extension")) {
                    end = j + 1;
                } else if (!line.isEmpty()) {
                    break;
                }
            }
            return end;
        }
        return 0;
    }

    /** Give {@code declaration} {@code location} unless it has it already; returns the location after it. */
    private static int renumber(List<String> lines, Declaration declaration, int location, String what,
                                List<String> notes) {
        if (declaration.location() != location) {
            lines.set(declaration.line(), declaration.declare(location));
            notes.add(what + " " + declaration.name() + ": location " + location);
        }
        return location + locations(declaration.type(), declaration.array());
    }

    /** Interface locations a declaration takes: a matrix one per column, an array one per element. */
    static int locations(String type, String array) {
        var columns = switch (type) {
            case "mat2", "dmat2" -> 2;
            case "mat3", "dmat3" -> 3;
            case "mat4", "dmat4" -> 4;
            default -> 1;
        };
        if (array.isEmpty()) {
            return columns;
        }
        var m = ARRAY_SIZE.matcher(array);
        return m.find() ? columns * Integer.parseInt(m.group(1)) : columns;
    }

    /** {@code #moj_import} → {@code #include}; {@code #version} to at least 330, with separate shader objects. */
    private static void header(List<String> lines, String stage, List<String> notes) {
        var imports = 0;
        for (int i = 0; i < lines.size(); i++) {
            var m = MOJ_IMPORT.matcher(lines.get(i));
            if (m.find()) {
                lines.set(i, m.replaceFirst("$1#include$2"));
                imports++;
            }
        }
        if (imports > 0) {
            notes.add(stage + " stage: #moj_import became #include");
        }
        // every stage compiles as Vulkan GLSL, which names these gl_VertexIndex/gl_InstanceIndex; Photon's draws start
        // at instance 0, so the instance one means the same, and both count the base vertex alike
        var builtins = 0;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var replaced = GL_VERTEX_ID.matcher(line).replaceAll("gl_VertexIndex");
            replaced = GL_INSTANCE_ID.matcher(replaced).replaceAll("gl_InstanceIndex");
            if (!replaced.equals(line)) {
                lines.set(i, replaced);
                builtins++;
            }
        }
        if (builtins > 0) {
            notes.add(stage + " stage: gl_VertexID/gl_InstanceID became gl_VertexIndex/gl_InstanceIndex");
        }
        var hasExtension = lines.stream().anyMatch(line -> line.contains("GL_ARB_separate_shader_objects"));
        for (int i = 0; i < lines.size(); i++) {
            var m = VERSION.matcher(lines.get(i));
            if (!m.matches()) {
                continue;
            }
            if (Integer.parseInt(m.group(1)) < 330) {
                lines.set(i, "#version 330");
                notes.add(stage + " stage: #version " + m.group(1) + " raised to 330");
            }
            if (!hasExtension) {
                lines.add(i + 1, SEPARATE_SHADER_OBJECTS);
                notes.add(stage + " stage: requires GL_ARB_separate_shader_objects");
            }
            return;
        }
    }

    /**
     * Whether the stage includes {@code photon:particle.glsl}, whose inputs take the first locations. Counted wherever
     * the include sits: reserving its locations when a branch leaves it out costs nothing.
     */
    private static boolean includesParticle(List<String> lines) {
        for (var line : stripComments(lines)) {
            if (PARTICLE_INCLUDE.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- sources ---------------------------------------------------------------------------------------------

    private static String joinContinuations(String source, String stage, List<String> notes) {
        var normalized = source.replace("\r\n", "\n");
        var joined = normalized.replaceAll("\\\\[ \\t]*\\n", " ");
        if (!joined.equals(normalized)) {
            notes.add(stage + " stage: line continuations joined");
        }
        return joined;
    }

    private static List<String> lines(String source) {
        return new ArrayList<>(List.of(source.split("\n", -1)));
    }

    // ---- declarations ------------------------------------------------------------------------------------------

    private static List<Declaration> declarations(List<String> lines, Set<String> defines) {
        var out = new ArrayList<Declaration>();
        var code = stripComments(lines);
        var active = activeLines(code, defines);
        var depth = 0;
        for (int i = 0; i < code.size(); i++) {
            var line = code.get(i);
            if (depth == 0 && active[i]) {
                Matcher m = DECLARATION.matcher(line);
                if (m.matches()) {
                    var location = -1;
                    if (m.group(1) != null) {
                        var l = LOCATION.matcher(m.group(1));
                        if (l.find()) {
                            location = Integer.parseInt(l.group(1));
                        }
                    }
                    out.add(new Declaration(i, m.group(2) == null ? "" : m.group(2), m.group(3), m.group(4),
                            m.group(5), m.group(6) == null ? "" : m.group(6), location));
                }
            }
            // braces in a branch the compiler will not see do not open a scope
            if (active[i] && !line.trim().startsWith("#")) {
                for (int c = 0; c < line.length(); c++) {
                    var ch = line.charAt(c);
                    if (ch == '{') depth++;
                    else if (ch == '}') depth = Math.max(0, depth - 1);
                }
            }
        }
        return out;
    }

    private static List<String> stripComments(List<String> lines) {
        var out = new ArrayList<String>(lines.size());
        var inBlock = false;
        for (var line : lines) {
            var sb = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); i++) {
                if (inBlock) {
                    if (line.startsWith("*/", i)) {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (line.startsWith("//", i)) {
                    break;
                }
                if (line.startsWith("/*", i)) {
                    inBlock = true;
                    i++;
                    continue;
                }
                sb.append(line.charAt(i));
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ---- a conditional-only preprocessor --------------------------------------------------------------------------

    private static final class Frame {
        final boolean parentActive;
        boolean taken;
        boolean active;

        Frame(boolean parentActive, boolean condition) {
            this.parentActive = parentActive;
            this.taken = condition;
            this.active = parentActive && condition;
        }
    }

    /** Which lines the compiler will see, given {@code defines} and the {@code #define}s met along the way. */
    static boolean[] activeLines(List<String> code, Set<String> initialDefines) {
        var defines = new HashMap<String, String>();
        for (var define : initialDefines) {
            defines.put(define, "1");
        }
        var result = new boolean[code.size()];
        var stack = new ArrayList<Frame>();
        for (int i = 0; i < code.size(); i++) {
            var trimmed = code.get(i).trim();
            var active = stack.isEmpty() || stack.getLast().active;
            if (trimmed.startsWith("#")) {
                var directive = trimmed.substring(1).trim();
                var keyword = directive.split("\\s+|\\(", 2)[0];
                var rest = directive.substring(keyword.length()).trim();
                switch (keyword) {
                    case "ifdef" -> stack.add(new Frame(active, defines.containsKey(firstWord(rest))));
                    case "ifndef" -> stack.add(new Frame(active, !defines.containsKey(firstWord(rest))));
                    case "if" -> stack.add(new Frame(active, active && evaluate(rest, defines) != 0));
                    case "elif" -> {
                        if (!stack.isEmpty()) {
                            var frame = stack.getLast();
                            var condition = !frame.taken && frame.parentActive && evaluate(rest, defines) != 0;
                            frame.active = frame.parentActive && condition;
                            frame.taken |= condition;
                        }
                    }
                    case "else" -> {
                        if (!stack.isEmpty()) {
                            var frame = stack.getLast();
                            frame.active = frame.parentActive && !frame.taken;
                            frame.taken = true;
                        }
                    }
                    case "endif" -> {
                        if (!stack.isEmpty()) stack.removeLast();
                    }
                    case "define" -> {
                        if (active) {
                            var parts = rest.split("\\s+", 2);
                            if (!parts[0].isEmpty() && !parts[0].contains("(")) {
                                defines.put(parts[0], parts.length > 1 ? parts[1].trim() : "");
                            }
                        }
                    }
                    case "undef" -> {
                        if (active) defines.remove(firstWord(rest));
                    }
                    default -> {
                    }
                }
                result[i] = false;
                continue;
            }
            result[i] = active;
        }
        return result;
    }

    private static String firstWord(String text) {
        var parts = text.trim().split("[^\\w]", 2);
        return parts.length == 0 ? "" : parts[0];
    }

    /** {@code defined(X)}, {@code !}, {@code &&}, {@code ||}, parentheses, integers and comparisons. */
    static long evaluate(String expression, Map<String, String> defines) {
        return new Expression(expression, defines).parse();
    }

    private static final class Expression {
        private final String text;
        private final Map<String, String> defines;
        private int pos;

        Expression(String text, Map<String, String> defines) {
            this.text = text;
            this.defines = defines;
        }

        long parse() {
            try {
                return or();
            } catch (RuntimeException e) {
                return 0;
            }
        }

        private void skip() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private boolean eat(String token) {
            skip();
            if (text.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private long or() {
            var value = and();
            while (eat("||")) {
                var right = and();
                value = (value != 0 || right != 0) ? 1 : 0;
            }
            return value;
        }

        private long and() {
            var value = comparison();
            while (eat("&&")) {
                var right = comparison();
                value = (value != 0 && right != 0) ? 1 : 0;
            }
            return value;
        }

        private long comparison() {
            var value = unary();
            while (true) {
                if (eat("==")) value = value == unary() ? 1 : 0;
                else if (eat("!=")) value = value != unary() ? 1 : 0;
                else if (eat(">=")) value = value >= unary() ? 1 : 0;
                else if (eat("<=")) value = value <= unary() ? 1 : 0;
                else if (eat(">")) value = value > unary() ? 1 : 0;
                else if (eat("<")) value = value < unary() ? 1 : 0;
                else return value;
            }
        }

        private long unary() {
            if (eat("!")) return unary() == 0 ? 1 : 0;
            if (eat("(")) {
                var value = or();
                eat(")");
                return value;
            }
            skip();
            var start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            var word = text.substring(start, pos);
            if (word.equals("defined")) {
                var parenthesized = eat("(");
                skip();
                var nameStart = pos;
                while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                    pos++;
                }
                var name = text.substring(nameStart, pos);
                if (parenthesized) eat(")");
                return defines.containsKey(name) ? 1 : 0;
            }
            if (word.isEmpty()) {
                throw new IllegalStateException("unexpected token at " + pos + " in " + text);
            }
            if (Character.isDigit(word.charAt(0))) {
                return Long.parseLong(word.replaceAll("[uUlL]+$", ""));
            }
            var value = defines.get(word);
            if (value == null || value.isEmpty()) {
                return value == null ? 0 : 1;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }

    // ---- values ------------------------------------------------------------------------------------------------

    /** OpenGL's value for a vertex attribute with no array enabled: (0, 0, 0, 1), truncated to the type. */
    static String attributeDefault(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0, 0.0, 0.0, 1.0)";
            case "int" -> "0";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0, 0, 0, 1)";
            case "uint" -> "0u";
            case "uvec2" -> "uvec2(0u)";
            case "uvec3" -> "uvec3(0u)";
            case "uvec4" -> "uvec4(0u, 0u, 0u, 1u)";
            default -> null;
        };
    }

    static String zero(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "vec2", "vec3", "vec4", "mat2", "mat3", "mat4" -> type + "(0.0)";
            case "int" -> "0";
            case "ivec2", "ivec3", "ivec4" -> type + "(0)";
            case "uint" -> "0u";
            case "uvec2", "uvec3", "uvec4" -> type + "(0u)";
            case "bool" -> "false";
            default -> null;
        };
    }

    /** Inputs declared without a location; for tests. */
    static Set<String> namedInputs(String source, Set<String> defines) {
        var out = new HashSet<String>();
        for (var declaration : declarations(lines(source), defines)) {
            if (declaration.storage().equals("in") && declaration.location() < 0) {
                out.add(declaration.name());
            }
        }
        return out;
    }
}
