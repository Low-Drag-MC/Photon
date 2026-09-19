package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.noise.CurlNoise;
import com.lowdragmc.photon.client.gameobject.emitter.data.noise.NoiseParticleState;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Noise2UnityMotionTest {
    @BeforeAll
    static void initializeNumberFunctionRegistry() {
        // Unit tests do not boot the NeoForge client registry lifecycle.
        if (PhotonRegistries.NUMBER_FUNCTIONS == null) {
            PhotonRegistries.NUMBER_FUNCTIONS = AutoRegistry.LDLibRegisterClient.create(
                    ResourceLocation.fromNamespaceAndPath("photon", "number_function"),
                    NumberFunction.class, AutoRegistry::noArgsCreator);
        }
    }

    private List<String> reference(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/noise2-motion/" + name)) {
            assertNotNull(stream);
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().skip(1).toList();
        }
    }

    @Test
    void matchesUnitySpatialVelocitiesAcrossSeedsQualitiesAndFrequencies() throws Exception {
        for (var line : reference("unity-2022-field.csv")) {
            var a = line.split(",");
            var actual = CurlNoise.sample(Double.parseDouble(a[5]), Double.parseDouble(a[6]), Double.parseDouble(a[7]),
                    0, Integer.parseInt(a[1]), Float.parseFloat(a[3]), false, 1, 0.5f, 2,
                    CurlNoise.Quality.valueOf(a[0]), new Vector3f());
            var expected = vector(a, 8);
            // Unity position readback subtracts two floats over a 1/4096-second interval.
            assertTrue(actual.distance(expected) < 0.002f, line + " got " + actual);
        }
    }

    @Test
    void matchesUnityTrajectoriesAndRotationWithRuntimeControls() throws Exception {
        String previous = "";
        Noise2Setting config = null;
        Noise2Setting.Runtime runtime = null;
        NoiseParticleState state = null;
        var position = new Vector3f();
        float elapsed = 0;
        var trajectories = new java.util.ArrayList<>(reference("unity-2022-trajectories.csv"));
        trajectories.addAll(reference("unity-2022-extra-trajectories.csv"));
        for (var line : trajectories) {
            var a = line.split(",");
            String key = a[0] + "/" + a[1] + "/" + a[2];
            float seconds = Float.parseFloat(a[2]);
            if (!key.equals(previous)) {
                config = config(CurlNoise.Quality.valueOf(a[0]), a[1]);
                runtime = config.createRuntime();
                state = new NoiseParticleState();
                position.set(0.31f, 0.57f, -0.83f);
                elapsed = 0;
                previous = key;
            }
            // Nonlinear trajectories amplify float rounding over long runs. This checks every
            // recorded frame in the first second, rather than just fitting an initial velocity.
            if ((Integer.parseInt(a[3]) + 1) * seconds > 1.001f) continue;
            float dt = seconds * 20;
            runtime.advance(12345, elapsed / 10, 200, false, () -> 0.5f, dt, true);
            int steps = CurlNoise.substepCount(dt);
            for (int step = 0; step < steps; step++) {
                var value = runtime.sample(position, elapsed / 10, () -> 0.5f, (step + 1f) / steps, new Vector3f());
                state.update(value, config.positionAmount.get(elapsed / 10, () -> 0.5f).floatValue(), 30, 0, true, dt / steps);
                elapsed += seconds / steps;
                var velocity = new Vector3f(state.velocity);
                if (a[1].equals("velocity")) velocity.add(0.2f / 20, -0.1f / 20, 0.3f / 20);
                position.add(velocity.mul(dt / steps));
            }
            assertTrue(position.distance(vector(a, 4)) < 0.002f, key + " frame " + a[3] + " got " + position);
            var rotationDegrees = new Vector3f(state.rotation).mul(180f / (float) Math.PI);
            assertTrue(rotationDegrees.distance(vector(a, 7)) < 0.04f, key + " rotation frame " + a[3]);
        }
    }

    @Test
    void matchesUnityScrollCurvesLoopBoundariesAndEmptyIntervals() throws Exception {
        String previous = "";
        Noise2Setting.Runtime runtime = null;
        var position = new Vector3f(0.31f, 0.57f, -0.83f);
        for (var line : reference("unity-2022-scroll.csv")) {
            var a = line.split(",");
            if (!a[0].equals(previous)) {
                var config = config(CurlNoise.Quality.High, "base");
                config.scrollSpeed = a[0].equals("curve") || a[0].equals("loop")
                        ? new Curve(0, 1, 0, 1, "", "", new ECBCurves(0, 0, 1f / 3, 1f / 3, 2f / 3, 2f / 3, 1, 1))
                        : NumberFunction.constant(0.7f);
                runtime = config.createRuntime();
                previous = a[0];
            }
            float seconds = Float.parseFloat(a[1]);
            float duration = Float.parseFloat(a[4]);
            float elapsed = Integer.parseInt(a[2]) * seconds;
            // Match the emitter's wrapped clock. Keep it in ticks to avoid seconds roundoff at wraps.
            float time = (Integer.parseInt(a[2]) * (seconds * 20) % (duration * 20)) / (duration * 20);
            runtime.advance(12345, time, duration * 20, Boolean.parseBoolean(a[5]), () -> 0.5f,
                    seconds * 20, Boolean.parseBoolean(a[3]));
            var expected = CurlNoise.sample(position.x, position.y, position.z, Double.parseDouble(a[6]),
                    12345, 1, false, 1, 0.5f, 2, CurlNoise.Quality.High, new Vector3f());
            var actual = runtime.sample(position, elapsed / 10, () -> 0.5f, 1, new Vector3f());
            assertTrue(expected.distance(actual) < 0.0002f, line + " got " + actual + " expected " + expected);
        }
    }

    @Test
    void matchesUnityBillboardRotationAndBakedSize() throws Exception {
        String previous = "";
        Noise2Setting config = null;
        Noise2Setting.Runtime runtime = null;
        NoiseParticleState state = null;
        var position = new Vector3f(0.31f, 0.57f, -0.83f);
        for (var line : reference("unity-2022-appearance.csv")) {
            var a = line.split(",");
            String key = String.join("/", a[0], a[1], a[2], a[3]);
            if (!key.equals(previous)) {
                config = config(CurlNoise.Quality.High, "base");
                config.rotation3D = Boolean.parseBoolean(a[0]);
                config.remap.setEnable(Boolean.parseBoolean(a[2]));
                boolean separate = Boolean.parseBoolean(a[1]);
                config.remap.axes = new NumberFunction3(constantRemap(0.2f),
                        constantRemap(separate ? -0.3f : 0.2f), constantRemap(separate ? 0.4f : 0.2f));
                runtime = config.createRuntime();
                state = new NoiseParticleState();
                previous = key;
            }
            runtime.advance(12345, 0, 200, false, () -> 0.5f, 1, true);
            for (int step = 0; step < 2; step++) {
                var value = runtime.sample(position, 0, () -> 0.5f, (step + 1f) / 2, new Vector3f());
                state.update(value, 0, 30, 0.2f, runtime.rotation3D.get(), 0.5f);
            }
            var degrees = new Vector3f(state.rotation).mul(180f / (float) Math.PI);
            assertTrue(degrees.distance(vector(a, 5)) < 0.00002f, line + " rotation " + degrees);
            // GetCurrentSize omits Unity's renderer noise; this reference uses BakeMesh instead.
            assertEquals(Float.parseFloat(a[8]), state.size.x, 0.000002f, line);
            assertEquals(state.size.x, state.size.y);
            assertEquals(state.size.x, state.size.z);
        }
    }

    private Curve constantRemap(float value) {
        return new Curve(-1, 1, -1, 1, value, "", "");
    }

    private Noise2Setting config(CurlNoise.Quality quality, String scenario) {
        var config = new Noise2Setting();
        config.setEnable(true);
        config.quality = quality;
        config.frequency = List.of("damping", "remapDamping").contains(scenario) ? 0.5f : List.of("frequency", "remapFrequency").contains(scenario) ? 2 : 1;
        config.damping = List.of("damping", "remapDamping").contains(scenario);
        config.scrollSpeed = NumberFunction.constant(scenario.equals("scroll") ? 0.7f : scenario.equals("reverse") ? -0.7f : 0);
        if (scenario.equals("octaves")) config.octaveCount = 3;
        if (scenario.equals("strength") || scenario.startsWith("remap")) config.strengthAxes = new NumberFunction3(0.4f, 0.4f, 0.4f);
        if (scenario.equals("axes")) config.strengthAxes = new NumberFunction3(0.3f, 0.7f, 1.2f);
        if (scenario.equals("position")) config.positionAmount = NumberFunction.constant(0.3f);
        if (scenario.startsWith("remap")) {
            config.remap.setEnable(true);
            Curve curve = scenario.equals("remapConstant") ? new Curve(-1, 1, -1, 1, 0.2f, "", "") :
                    new Curve(-1, 1, -0.5f, 0.5f, "", "", new ECBCurves(0, 0, 1f / 3, 1f / 3, 2f / 3, 2f / 3, 1, 1));
            config.remap.axes = new NumberFunction3(curve.copy(), curve.copy(), curve.copy());
        }
        if (scenario.equals("octaveParameters")) {
            config.frequency = 1.3f;
            config.damping = true;
            config.scrollSpeed = NumberFunction.constant(0.35f);
            config.octaveCount = 3;
            config.octaveMultiplier = 0.3f;
            config.octaveScale = 3;
        }
        if (scenario.equals("zeroFrequency")) {
            config.frequency = 0;
            config.damping = true;
        }
        if (scenario.startsWith("lifetime")) {
            var curve = new Curve(0, 1, 0, 1, "", "", new ECBCurves(0, 0, 1f / 3, 1f / 3, 2f / 3, 2f / 3, 1, 1));
            if (scenario.equals("lifetimeStrength")) config.strengthAxes = new NumberFunction3(curve.copy(), curve.copy(), curve.copy());
            else config.positionAmount = curve;
        }
        return config;
    }

    private Vector3f vector(String[] values, int start) {
        return new Vector3f(Float.parseFloat(values[start]), Float.parseFloat(values[start + 1]), Float.parseFloat(values[start + 2]));
    }
}
