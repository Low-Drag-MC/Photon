package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaLayout;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaSimpleModules;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lights as the pipelines declare them ({@code PhotonPipelines.declareLights}) against what the shipped GLSL
 * reads. 1.21 listed them in every lit shader's json; 26.1 declares them on the pipeline, so the names are the
 * contract — a name the pipeline binds and the shader does not read warns, one it reads and nobody binds fails.
 */
class LitParticlesTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                LitParticlesTest.class.getResourceAsStream("/assets/photon/shaders/" + path), path + " missing")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theIncludeDeclaresWhatThePipelineBinds() throws IOException {
        var glsl = resource("include/dynamic_light.glsl");
        var declared = new HashMap<String, String>();
        var matcher = Pattern.compile("(?m)^uniform\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(glsl);
        while (matcher.find()) declared.put(matcher.group(2), matcher.group(1));
        assertEquals(Map.of(
                PhotonPipelines.LIGHT_DATA, "samplerBuffer",
                PhotonPipelines.CLUSTER_GRID, "samplerBuffer",
                PhotonPipelines.CLUSTER_INDICES, "samplerBuffer",
                PhotonPipelines.VISIBILITY_ATLAS, "sampler2D"), declared);
        assertTrue(glsl.contains("layout(std140) uniform " + PhotonPipelines.LIGHTS_BLOCK + " {"));
    }

    @Test
    void everythingLightsHidesBehindTheDefine() throws IOException {
        var lit = resource("include/lit_particle.glsl");
        int define = lit.indexOf("#ifdef " + PhotonPipelines.LIT_DEFINE);
        assertTrue(define >= 0, "lit_particle.glsl is switched by " + PhotonPipelines.LIT_DEFINE);
        assertTrue(lit.indexOf("#moj_import <photon:dynamic_light.glsl>") > define,
                "an unlit pipeline declares none of the lights' buffers");
    }

    @Test
    void theShippedParticleShadersLightThroughTheMaterialBlock() throws IOException {
        for (var shader : List.of("hdr_particle", "sprite_hdr_particle", "pixel_hdr_particle")) {
            var fsh = resource("core/" + shader + ".fsh");
            assertTrue(LitParticles.includedBy(fsh), shader + " imports the lights");
            int block = fsh.indexOf("layout(std140) uniform PhotonMaterial {");
            assertTrue(block >= 0, shader);
            assertTrue(fsh.indexOf("float LitIntensity;", block) < fsh.indexOf("};", block),
                    shader + " reads its intensity from the material block");
        }
    }

    @Test
    void kilaLitTakesTheLightsSamplers() {
        var layout = KilaLayout.surface().build(List.of(new KilaSimpleModules.Lit()));
        assertTrue(layout.lit);
        assertTrue(layout.defines.contains("KILA_LIT"));
        assertTrue(layout.samplers.containsAll(List.of(PhotonPipelines.LIGHT_DATA, PhotonPipelines.CLUSTER_GRID,
                PhotonPipelines.CLUSTER_INDICES, PhotonPipelines.VISIBILITY_ATLAS)), "counted against the budget");
    }

    @Test
    void includedByLooksForTheImport() {
        assertTrue(LitParticles.includedBy("#version 150\n#moj_import <photon:lit_particle.glsl>\nvoid main() {}"));
        assertFalse(LitParticles.includedBy("#moj_import <photon:dynamic_light.glsl>"));
        assertFalse(LitParticles.includedBy("void main() {}"));
    }

    /** A converted 1.21 custom shader's json still lists these; they mark it lit and are not its own values. */
    @Test
    void declaresKnowsTheLightsOwnNames() {
        assertTrue(LitParticles.declares("PhotonLightData"));
        assertTrue(LitParticles.declares("LitParticleParams"));
        assertFalse(LitParticles.declares("Sampler0"));
    }
}
