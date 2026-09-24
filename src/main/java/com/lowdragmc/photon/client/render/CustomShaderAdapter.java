package com.lowdragmc.photon.client.render;

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
 * Adapts legacy custom shaders to 26.2's Vulkan rules, where inputs and varyings are matched by name and numbered
 * by counting matches:
 * <ul>
 *   <li>explicit-location vertex inputs are fed by name; unprovided ones become GL's default {@code (0, 0, 0, 1)}</li>
 *   <li>fragment inputs no vertex output writes become zero constants</li>
 *   <li>vertex outputs the fragment skipped are declared in it</li>
 *   <li>{@code \} line continuations (GLSL 4.20+) are joined</li>
 * </ul>
 * Only global-scope declarations in active preprocessor branches are touched.
 */
public final class CustomShaderAdapter {

    private CustomShaderAdapter() {
    }

    /** @param tailInputs tail location → the vertex input name reading it */
    public record Result(String vertex, String fragment, Map<Integer, String> tailInputs, boolean changed,
                         List<String> notes) {
    }

    private record Declaration(int line, String qualifiers, String storage, String type, String name, String array,
                               int location) {
    }

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid)\\s+)*)(in|out)\\s+(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;\\s*$");
    private static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");

    /**
     * @param providedLocations vertex elements before the attribute tail
     * @param tailLocations     tail locations the pipeline provides
     */
    public static Result adapt(String vertexSource, String fragmentSource, int providedLocations,
                               Set<Integer> tailLocations, Set<String> defines) {
        var notes = new ArrayList<String>();
        var vertexLines = lines(joinContinuations(vertexSource, "vertex", notes));
        var fragmentLines = lines(joinContinuations(fragmentSource, "fragment", notes));

        // ---- vertex inputs
        var tailInputs = new LinkedHashMap<Integer, String>();
        var vertexDeclarations = declarations(vertexLines, defines);
        var vertexOutputs = new ArrayList<Declaration>();
        for (var declaration : vertexDeclarations) {
            if (declaration.storage().equals("out")) {
                vertexOutputs.add(declaration);
                continue;
            }
            if (declaration.location() < 0) {
                continue;
            }
            var location = declaration.location();
            if (location < providedLocations) {
                vertexLines.set(declaration.line(), "in " + declaration.type() + " " + declaration.name()
                        + declaration.array() + ";");
                notes.add("vertex input " + declaration.name() + ": location qualifier dropped (fed by name)");
            } else if (tailLocations.contains(location)) {
                vertexLines.set(declaration.line(), "in " + declaration.type() + " " + declaration.name()
                        + declaration.array() + ";");
                tailInputs.put(location, declaration.name());
                notes.add("vertex input " + declaration.name() + ": reads attribute tail location " + location);
            } else {
                var constant = attributeDefault(declaration.type());
                if (constant == null || !declaration.array().isEmpty()) {
                    continue;
                }
                vertexLines.set(declaration.line(), declaration.type() + " " + declaration.name() + " = " + constant + ";");
                notes.add("vertex input " + declaration.name() + " (location " + location
                        + ") is not provided here: constant " + constant);
            }
        }

        // ---- fragment inputs against the vertex outputs
        var outputsByName = new LinkedHashMap<String, Declaration>();
        for (var output : vertexOutputs) {
            outputsByName.put(output.name(), output);
        }
        var fragmentInputs = new HashSet<String>();
        for (var declaration : declarations(fragmentLines, defines)) {
            if (!declaration.storage().equals("in")) {
                continue;
            }
            if (outputsByName.containsKey(declaration.name())) {
                fragmentInputs.add(declaration.name());
                if (declaration.location() >= 0) {
                    fragmentLines.set(declaration.line(), declaration.qualifiers() + "in " + declaration.type() + " "
                            + declaration.name() + declaration.array() + ";");
                }
                continue;
            }
            var zero = zero(declaration.type());
            if (zero == null || !declaration.array().isEmpty()) {
                continue;
            }
            fragmentLines.set(declaration.line(), declaration.type() + " " + declaration.name() + " = " + zero + ";");
            notes.add("fragment input " + declaration.name() + " is written by no vertex output: constant " + zero);
        }
        var missing = new ArrayList<String>();
        for (var output : vertexOutputs) {
            if (!fragmentInputs.contains(output.name())) {
                missing.add(output.qualifiers() + "in " + output.type() + " " + output.name() + output.array() + ";");
                notes.add("fragment stage declares vertex output " + output.name() + " it did not read");
            }
        }
        if (!missing.isEmpty()) {
            fragmentLines.addAll(insertionPoint(fragmentLines), missing);
        }

        var vertex = String.join("\n", vertexLines);
        var fragment = String.join("\n", fragmentLines);
        return new Result(vertex, fragment, Map.copyOf(tailInputs), !notes.isEmpty(), List.copyOf(notes));
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

    /** After the {@code #version} line and any {@code #extension} lines right behind it. */
    private static int insertionPoint(List<String> lines) {
        var index = 0;
        for (int i = 0; i < lines.size(); i++) {
            var trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#version")) {
                index = i + 1;
                while (index < lines.size() && lines.get(index).trim().startsWith("#extension")) {
                    index++;
                }
                return index;
            }
        }
        return index;
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
