package com.lowdragmc.photon.client.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code photon:particle.glsl}'s inputs, against the locations custom shaders and shader graphs number theirs after
 * ({@link CustomShaderAdapter#PARTICLE_INPUT_LOCATIONS}).
 */
class ParticleInputLocationsTest {

    private static final Pattern DEFINE = Pattern.compile("#define PHOTON_INPUT_LOCATIONS (\\d+)");
    private static final Pattern INPUT = Pattern.compile("layout\\(location = (\\d+)\\) in ");

    private static Path include(String name) {
        var relative = Path.of("src", "main", "resources", "assets", "photon", "shaders", "include", name);
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(relative))) return dir.resolve(relative);
        }
        throw new IllegalStateException(name + " not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void theJavaConstantIsTheIncludesWidestVariant() throws IOException {
        var source = Files.readString(include("particle.glsl"));
        var widest = 0;
        var defines = DEFINE.matcher(source);
        while (defines.find()) {
            widest = Math.max(widest, Integer.parseInt(defines.group(1)));
        }
        assertEquals(CustomShaderAdapter.PARTICLE_INPUT_LOCATIONS, widest);

        var inputs = INPUT.matcher(source);
        var found = false;
        while (inputs.find()) {
            found = true;
            assertTrue(Integer.parseInt(inputs.group(1)) < widest, "input at location " + inputs.group(1)
                    + " is not below PHOTON_INPUT_LOCATIONS " + widest);
        }
        assertTrue(found, "no inputs found");
    }
}
