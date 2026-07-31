package com.lowdragmc.photon.client.postfx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pass-shader contract, enforced on the shipped assets.
 * <p>
 * A hand-written post-effect pass carries its interface in its shader JSON, and 26.1 requires its values
 * to live in a {@code layout(std140) uniform PhotonPass} block. The executor packs that block straight
 * from the JSON list, so the two MUST agree on <b>name, component count and order</b> — a mismatch does
 * not fail loudly at runtime, it silently shifts every following member's std140 offset and the effect
 * renders with scrambled parameters. This test is what keeps them in step.
 */
public class PostFXShaderAssetsTest {

    private static final Path CORE = coreAssets();
    private static Path coreAssets() {
        // the test JVM runs under the mod's transformed module layer (and not from the project root),
        // so walk up from the working directory to the module that owns these assets
        var relative = Path.of("src", "main", "resources", "assets", "photon", "shaders", "core");
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("photon shader assets not found from " + Path.of("").toAbsolutePath());
    }

    private static final Pattern BLOCK = Pattern.compile(
            "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+PhotonPass\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern MEMBER = Pattern.compile("^\\s*(\\w+)\\s+(\\w+)\\s*;", Pattern.MULTILINE);
    private static final Pattern LOOSE_UNIFORM = Pattern.compile("^uniform\\s+(?!sampler)", Pattern.MULTILINE);

    /** Every shipped pass shader, found by what makes one: a {@code PhotonPass} block. Discovered rather
     *  than listed, so a new pass cannot quietly escape the contract this test enforces. */
    private static List<Path> passShaders() throws IOException {
        try (var files = Files.walk(CORE)) {
            return files.filter(p -> p.toString().endsWith(".fsh"))
                    .filter(p -> BLOCK.matcher(readUnchecked(p)).find())
                    .sorted()
                    .toList();
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return read(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    public void blockMatchesJsonLayout() throws IOException {
        var shaders = passShaders();
        assertFalse(shaders.isEmpty(), "no pass shaders found — is the assets path still right?");
        for (var fsh : shaders) {
            var name = fsh.getFileName().toString();
            var json = JsonParser.parseString(read(fsh.resolveSibling(name.replace(".fsh", ".json"))))
                    .getAsJsonObject();
            var source = read(fsh);

            var expected = new ArrayList<String>();
            for (var element : json.getAsJsonArray("uniforms")) {
                var uniform = element.getAsJsonObject();
                if (!"float".equals(string(uniform, "type"))) continue;
                var count = uniform.has("count") ? uniform.get("count").getAsInt() : 1;
                expected.add(glslType(count) + " " + uniform.get("name").getAsString());
            }

            assertFalse(LOOSE_UNIFORM.matcher(source).find(),
                    name + ": non-sampler uniforms must live in the PhotonPass block (26.1 has no loose uniforms)");
            var actual = blockMembers(source);
            assertEquals(expected, actual,
                    name + ": the PhotonPass block must mirror the json 'uniforms' list exactly, in order");
        }
    }

    @Test
    public void samplersAreDeclared() throws IOException {
        for (var fsh : passShaders()) {
            var name = fsh.getFileName().toString();
            var json = JsonParser.parseString(read(fsh.resolveSibling(name.replace(".fsh", ".json"))))
                    .getAsJsonObject();
            var source = read(fsh);
            for (var element : json.getAsJsonArray("samplers")) {
                var sampler = element.getAsJsonObject().get("name").getAsString();
                // every json sampler becomes a pipeline-declared sampler, and 26.1 fails the draw if a
                // declared sampler has no use in the shader
                assertTrue(source.contains("uniform sampler2D " + sampler + ";"),
                        name + ": json declares sampler '" + sampler + "' the shader does not");
            }
        }
    }

    /** The vertex stage every pass shares must exist and expose the {@code texCoord} they all read. */
    @Test
    public void sharedVertexStageExists() throws IOException {
        var vertex = read(CORE.resolve("fullscreen.vsh"));
        assertTrue(vertex.contains("out vec2 texCoord"), "the shared fullscreen stage must emit texCoord");
    }

    private static List<String> blockMembers(String source) {
        var block = BLOCK.matcher(source);
        if (!block.find()) return List.of();
        var members = new ArrayList<String>();
        var member = MEMBER.matcher(block.group(1));
        while (member.find()) {
            members.add(member.group(1) + " " + member.group(2));
        }
        return members;
    }

    private static String glslType(int count) {
        return switch (count) {
            case 1 -> "float";
            case 2 -> "vec2";
            case 3 -> "vec3";
            default -> "vec4";
        };
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "float";
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
