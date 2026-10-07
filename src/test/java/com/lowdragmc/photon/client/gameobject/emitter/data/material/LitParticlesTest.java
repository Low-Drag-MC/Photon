package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaLayout;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaSimpleModules;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The lights' samplers and uniforms, as Java declares them for a json it writes, against the shipped GLSL and json. */
class LitParticlesTest {
    private static final Map<String, String> GLSL_TYPES = Map.of("int", "ivec4", "float", "vec4", "matrix4x4", "mat4");

    private static String resource(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                LitParticlesTest.class.getResourceAsStream("/assets/photon/shaders/" + path), path + " missing")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, JsonObject> byName(JsonArray entries) {
        var map = new HashMap<String, JsonObject>();
        entries.forEach(entry -> map.put(entry.getAsJsonObject().get("name").getAsString(), entry.getAsJsonObject()));
        return map;
    }

    private static List<String> samplerNames(JsonObject manifest) {
        var names = new ArrayList<String>();
        manifest.getAsJsonArray("samplers").forEach(e -> names.add(e.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    @Test
    void theIncludeDeclaresExactlyWhatJavaLists() throws IOException {
        var glsl = resource("include/dynamic_light.glsl") + resource("include/lit_particle.glsl");
        var declared = new HashMap<String, String>();
        var matcher = Pattern.compile("^uniform\\s+(\\w+)\\s+(\\w+)\\s*;", Pattern.MULTILINE).matcher(glsl);
        while (matcher.find()) declared.put(matcher.group(2), matcher.group(1));

        var expected = new HashMap<String, String>();
        LitParticles.SAMPLERS.forEach(name -> expected.put(name, "sampler2D"));
        LitParticles.uniformEntries().forEach(entry -> {
            var uniform = entry.getAsJsonObject();
            var type = uniform.get("type").getAsString();
            var glslType = "matrix4x4".equals(type) ? "mat4" : uniform.get("count").getAsInt() == 4 ? GLSL_TYPES.get(type) : "?";
            expected.put(uniform.get("name").getAsString(), glslType);
        });
        assertEquals(expected, declared);
    }

    @Test
    void theShippedParticleShadersDeclareTheSameEntries() throws IOException {
        var ours = byName(LitParticles.uniformEntries());
        for (var shader : List.of("hdr_particle", "sprite_hdr_particle", "pixel_hdr_particle")) {
            var manifest = JsonParser.parseString(resource("core/" + shader + ".json")).getAsJsonObject();
            assertTrue(samplerNames(manifest).containsAll(LitParticles.SAMPLERS), shader);
            var theirs = byName(manifest.getAsJsonArray("uniforms"));
            for (var entry : ours.entrySet()) {
                var their = theirs.get(entry.getKey());
                assertNotNull(their, shader + " lacks " + entry.getKey());
                var mine = entry.getValue();
                assertEquals(mine.get("type"), their.get("type"), shader + " " + entry.getKey());
                assertEquals(mine.get("count"), their.get("count"), shader + " " + entry.getKey());
                var a = mine.getAsJsonArray("values");
                var b = their.getAsJsonArray("values");
                assertEquals(a.size(), b.size(), shader + " " + entry.getKey());
                for (int i = 0; i < a.size(); i++) {
                    assertEquals(a.get(i).getAsDouble(), b.get(i).getAsDouble(), 1e-9, shader + " " + entry.getKey());
                }
            }
        }
    }

    @Test
    void kilaLitDeclaresTheSameNames() {
        var layout = KilaLayout.surface().build(List.of(new KilaSimpleModules.Lit()));
        assertTrue(layout.samplers.containsAll(LitParticles.SAMPLERS));
        var names = new HashSet<String>();
        layout.uniforms.forEach(uniform -> names.add(uniform.name()));
        assertTrue(names.containsAll(LitParticles.UNIFORMS), names.toString());
    }

    @Test
    void declareInAppendsAfterWhatIsThere() {
        var manifest = """
                { "vertex": "a", "fragment": "a",
                  "samplers": [ { "name": "Sampler0" } ],
                  "uniforms": [ { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 ] } ] }""";
        var json = JsonParser.parseString(LitParticles.declareIn(manifest)).getAsJsonObject();
        var samplers = samplerNames(json);
        assertEquals("Sampler0", samplers.getFirst(), "the graph's own samplers keep their texture units");
        assertEquals(LitParticles.SAMPLERS, samplers.subList(1, samplers.size()));
        var uniforms = byName(json.getAsJsonArray("uniforms"));
        assertTrue(uniforms.containsKey("ModelViewMat"));
        assertTrue(uniforms.keySet().containsAll(LitParticles.UNIFORMS));
        assertEquals("a", json.get("vertex").getAsString());
    }

    @Test
    void includedByLooksForTheImport() {
        assertTrue(LitParticles.includedBy("#version 150\n#moj_import <photon:lit_particle.glsl>\nvoid main() {}"));
        assertFalse(LitParticles.includedBy("#moj_import <photon:dynamic_light.glsl>"));
        assertFalse(LitParticles.includedBy("void main() {}"));
    }

    @Test
    void declaresKnowsTheLightsOwnNames() {
        assertTrue(LitParticles.declares("PhotonLightData"));
        assertTrue(LitParticles.declares("LitParticleParams"));
        assertFalse(LitParticles.declares("Sampler0"));
    }
}
