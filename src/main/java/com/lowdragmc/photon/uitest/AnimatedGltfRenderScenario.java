package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource.PhaseSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Does an animated glTF actually draw, and differently from one moment to the next? Everything else about
 * the feature is asserted numerically; only a capture answers this.
 *
 * <p>Runs with GPU instancing both off and on — two separate geometry paths that are supposed to be
 * indistinguishable in the picture. ⚠️ Fails rather than passing quietly if {@code fox.glb} is missing.</p>
 */
@LDLRegisterClient(name = "animated_gltf_render", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AnimatedGltfRenderScenario implements UIScenario {

    /** Where the fixture is copied to, inside the injected assets pack. */
    private static final ResourceLocation MODEL = Photon.id("models/uitest_fox.glb");
    /** Author units: the fox is about 140 tall, so this puts it at a bit over a block. */
    private static final double SIZE = 0.018;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("copy the fox fixture into the assets pack", ctx -> {
            var bytes = readFixture();
            ctx.require("fox.glb was found (see the class javadoc for where it is looked for)",
                    bytes != null);
            var target = new File(LDLib2.getAssetsDir(), "photon/models/uitest_fox.glb");
            try {
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), bytes);
            } catch (IOException e) {
                throw new IllegalStateException("could not stage the fox fixture", e);
            }
            ctx.put("foxFile", target);
            ctx.check("the fixture is in place", target.isFile() && target.length() > 100_000,
                    "> 100 KB", target.length());
        })

        .step("the staged model parses through the resource manager", ctx -> {
            var source = new AnimatedGltfModelSource(MODEL);
            source.invalidate();
            AnimatedGltfModelSource.pinClock(0f);
            try {
                var mesh = source.getMesh();
                ctx.check("1728 vertices arrived", mesh.vertexCount() == 1728, 1728, mesh.vertexCount());
                ctx.check("it is animated", source.asDynamic() != null, "dynamic", "static");
            } finally {
                AnimatedGltfModelSource.pinClock(null);
            }
        })

        .step("look at the spot the fox will stand on", AnimatedGltfRenderScenario::aimCamera)
        .frames(2);

        // Both geometry paths, and several poses of each.
        for (boolean instanced : List.of(false, true)) {
            String tag = instanced ? "instanced" : "cpu";
            s.step("play the fox with instancing " + (instanced ? "on" : "off"),
                            ctx -> startFox(ctx, instanced))
                    .frames(4);
            for (float seconds : new float[]{0f, 0.25f, 0.5f, 0.75f}) {
                s.step("pose %s at %.2fs".formatted(tag, seconds),
                                ctx -> AnimatedGltfModelSource.pinClock(seconds))
                        .frames(3)
                        .screenshot("fox_%s_%02d".formatted(tag, (int) (seconds * 100)));
            }
            // ⚠️ captures are found by name suffix, so "_speed3" has to come last
            s.step("speed 3 at 0.25s (" + tag + ")", ctx -> {
                        source(ctx).setSpeed(3f);
                        AnimatedGltfModelSource.pinClock(0.25f);
                    })
                    .frames(3)
                    .screenshot("fox_%s_25_speed3".formatted(tag))
                    .step("speed scales the clock (" + tag + ")", ctx -> assertSpeedScalesClock(ctx, tag));
            s.step("stop the fox (" + tag + ")", AnimatedGltfRenderScenario::stopFox);
        }

        // Per-particle phase: one baked table, every particle at its own frame. The point of the capture
        // is that the poses DIFFER between particles, which no numeric check states as plainly.
        // ⚠️ both paths: GPU instancing is off by default, and the CPU one reads the same baked table
        for (boolean instanced : List.of(false, true)) {
            String tag = instanced ? "instanced" : "cpu";
            s.step("play a swarm with per-particle phase (" + tag + ")",
                            ctx -> startSwarm(ctx, instanced, PhaseSource.Random))
                    .frames(6)
                    .screenshot("fox_swarm_" + tag)
                    .step("stop the swarm (" + tag + ")", AnimatedGltfRenderScenario::stopFox);
        }

        // Lifetime follows the particle's age, not the pinned clock: speed 0 has to hold it across real ticks
        for (boolean instanced : List.of(false, true)) {
            String tag = instanced ? "instanced" : "cpu";
            s.step("play a lifetime swarm at speed 0 (" + tag + ")", ctx -> {
                        startSwarm(ctx, instanced, PhaseSource.Lifetime);
                        source(ctx).setSpeed(0f);
                    })
                    .ticks(20) // old enough for speed 4 to have an age to scale
                    .screenshot("fox_life_%s_a".formatted(tag))
                    .ticks(2)
                    .screenshot("fox_life_%s_b".formatted(tag))
                    .step("speed 4 (" + tag + ")", ctx -> source(ctx).setSpeed(4f))
                    .frames(3)
                    .screenshot("fox_life_%s_speed4".formatted(tag))
                    .step("speed scales a lifetime phase (" + tag + ")", ctx -> assertSpeedScalesLifetime(ctx, tag))
                    .step("stop the lifetime swarm (" + tag + ")", AnimatedGltfRenderScenario::stopFox);
        }

        // Loop off at speed 40: the clip ends 5 ticks in and has to hold; "gone" proves anything was drawn
        for (boolean instanced : List.of(false, true)) {
            String tag = instanced ? "instanced" : "cpu";
            s.step("play a lifetime swarm that does not loop (" + tag + ")", ctx -> {
                        startSwarm(ctx, instanced, PhaseSource.Lifetime);
                        source(ctx).setLoop(false);
                        source(ctx).setSpeed(40f);
                    })
                    .ticks(10)
                    .screenshot("fox_hold_%s_a".formatted(tag))
                    .ticks(2)
                    .screenshot("fox_hold_%s_b".formatted(tag))
                    .step("stop the held swarm (" + tag + ")", AnimatedGltfRenderScenario::stopFox)
                    .frames(3)
                    .screenshot("fox_hold_%s_gone".formatted(tag))
                    .step("a clip that does not loop holds its end (" + tag + ")", ctx -> assertHoldsEnd(ctx, tag));
        }

        s.teardown("unpin the clock", ctx -> AnimatedGltfModelSource.pinClock(null))
                .teardown("stop any effect", AnimatedGltfRenderScenario::stopFox)
                .teardown("remove the fixture", ctx -> {
                    var file = ctx.<File>get("foxFile");
                    if (file != null && file.exists() && !file.delete()) {
                        Photon.LOGGER.warn("could not delete the fox fixture {}", file);
                    }
                });
    }

    /**
     * The fixtures live in the test source set, off the client run's classpath, so they are read off disk.
     * Package-visible because {@link EditorModelInstanceScenario} stages one too, and where to look is a
     * function of the client's working directory rather than of any one scenario.
     */
    static byte[] readFixture() {
        return readFixture("fox.glb");
    }

    static byte[] readFixture(String fileName) {
        var classpath = AnimatedGltfRenderScenario.class
                .getResourceAsStream("/assets/photon/models/" + fileName);
        if (classpath != null) {
            try (var in = classpath) {
                return in.readAllBytes();
            } catch (IOException ignored) {
                // fall through to the disk candidates
            }
        }
        for (String candidate : List.of(
                "src/test/resources/assets/photon/models/" + fileName,
                "../src/test/resources/assets/photon/models/" + fileName,
                "../../src/test/resources/assets/photon/models/" + fileName)) {
            var path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                try {
                    return Files.readAllBytes(path);
                } catch (IOException ignored) {
                    // try the next one
                }
            }
        }
        return null;
    }

    /** Point the camera at the block in front of the player, from a little way back. */
    private static void aimCamera(TestContext ctx) {
        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        player.setXRot(-5f);
        player.setYRot(player.getYRot());
    }

    private static void startFox(TestContext ctx, boolean instanced) {
        stopFox(ctx);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(1);
        // collapse the default cone to a point, or two captures differ by where the particle landed
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        // one particle from a burst at tick 0, so the pose is the only thing changing between captures
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);

        var source = new AnimatedGltfModelSource(MODEL);
        ctx.put("foxSource", source);
        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        renderer.setModel(new MeshData(source));
        renderer.setUseGPUInstance(instanced);
        // ⚠️ two defaults have to go for a capture to be readable, neither of them a bug: the default
        // sprite is a soft circle (a model's UVs land all over it, so the shape arrives full of holes),
        // and depth writing is off (so the far side blends through the near side)
        renderer.getMaterials().add(new com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting(
                new com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial(
                        ResourceLocation.parse("textures/block/white_concrete.png")))
                .setDepthMask(true).setCull(true));

        var fx = new FX();
        fx.getFxData().objects().add(emitter);

        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        var pos = player.blockPosition().relative(player.getDirection(), 9);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0, 1, 0);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("foxExecutor", executor);
        ctx.check("the effect started", executor.getRuntime() != null, "a runtime", "null");
    }

    /** Twelve of them, spread out, each at its own point in the clip. */
    private static void startSwarm(TestContext ctx, boolean instanced, PhaseSource phaseSource) {
        stopFox(ctx);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(12);
        config.shape.setScale(new NumberFunction3(9, 0, 2));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(12));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);

        var source = new AnimatedGltfModelSource(MODEL);
        source.setPerParticlePhase(true);
        source.setFrames(24);
        source.setPhaseSource(phaseSource);
        ctx.put("foxSource", source);
        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        renderer.setModel(new MeshData(source));
        renderer.setUseGPUInstance(instanced);
        renderer.getMaterials().add(new com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting(
                new com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial(
                        ResourceLocation.parse("textures/block/white_concrete.png")))
                .setDepthMask(true).setCull(true));

        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        var pos = player.blockPosition().relative(player.getDirection(), 8);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0, 1, 0);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("foxExecutor", executor);
        ctx.check("the swarm started", executor.getRuntime() != null, "a runtime", "null");
        ctx.check("it baked a pose table", source.vertexAnimation() != null, "a table", "null");
        ctx.check("and stopped deforming on the CPU", source.asDynamic() == null, "not dynamic", "dynamic");
        if (phaseSource == PhaseSource.Random) {
            assertThePoseActuallyMoves(ctx, source);
        }
    }

    private static void assertSpeedScalesClock(TestContext ctx, String tag) {
        int matched = changed(ctx, "fox_%s_25_speed3", "fox_%s_75", tag);
        int moved = changed(ctx, "fox_%s_25_speed3", "fox_%s_25", tag);
        ctx.check("0.25s and 0.75s are different poses", moved > 150, "> 150 px", moved);
        ctx.check("speed 3 at 0.25s draws the 0.75s pose", matched <= 10, "<= 10 px", matched);
    }

    private static void assertSpeedScalesLifetime(TestContext ctx, String tag) {
        var animation = source(ctx).vertexAnimation();
        ctx.require("the table exists", animation != null);
        ctx.check("the age is weighted by the speed", animation.phase()[2] == 4f, 4f, animation.phase()[2]);
        int held = changed(ctx, "fox_life_%s_a", "fox_life_%s_b", tag);
        int sped = changed(ctx, "fox_life_%s_b", "fox_life_%s_speed4", tag);
        ctx.check("speed 0 holds an ageing swarm still", held <= 10, "<= 10 px", held);
        ctx.check("speed 4 moves it", sped > 500, "> 500 px", sped);
    }

    private static void assertHoldsEnd(TestContext ctx, String tag) {
        var animation = source(ctx).vertexAnimation();
        ctx.require("the table exists", animation != null);
        ctx.check("it clamps and still blends", animation.clamps() && animation.interpolates(),
                "clamp + blend", animation.phase()[3]);
        int drawn = changed(ctx, "fox_hold_%s_a", "fox_hold_%s_gone", tag);
        int held = changed(ctx, "fox_hold_%s_a", "fox_hold_%s_b", tag);
        ctx.check("the swarm is on screen", drawn > 2000, "> 2000 px", drawn);
        ctx.check("past its end it holds still", held <= 10, "<= 10 px", held);
    }

    private static AnimatedGltfModelSource source(TestContext ctx) {
        return ctx.get("foxSource");
    }

    /** Pixels that differ between two of this run's captures, over the middle of the frame. */
    private static int changed(TestContext ctx, String first, String second, String tag) {
        var a = ScreenshotCompare.load(ctx, first.formatted(tag));
        var b = ScreenshotCompare.load(ctx, second.formatted(tag));
        ctx.require(first.formatted(tag) + " and " + second.formatted(tag) + " were read", a != null && b != null);
        return a.diff(b, 24, a.middle(0.8)).count();
    }

    /**
     * ⚠️ The three ways a baked table draws a model that never moves, none of which log anything: the
     * phase does not follow the clock, the table is the rest pose repeated (which is what an unresolved
     * clip name bakes), or every particle lands on one frame. Asserting a table merely exists missed all
     * three.
     */
    private static void assertThePoseActuallyMoves(TestContext ctx, AnimatedGltfModelSource source) {
        try {
            AnimatedGltfModelSource.pinClock(0f);
            var animation = source.vertexAnimation();
            ctx.require("the table survived pinning the clock", animation != null);
            float atZero = animation.phase()[0];
            float randomWeight = animation.phase()[1];

            AnimatedGltfModelSource.pinClock(0.37f);
            source.vertexAnimation();
            float later = animation.phase()[0];
            ctx.check("the phase follows the clock", Math.abs(later - atZero) > 1.0e-4f,
                    "a different phase at a different time", atZero + " both times");

            source.setSpeed(2f);
            AnimatedGltfModelSource.pinClock(0.185f);
            source.vertexAnimation();
            float doubled = animation.phase()[0];
            source.setSpeed(1f);
            ctx.check("speed 2 at half the time is the same phase", Math.abs(doubled - later) < 1.0e-4f,
                    later, doubled);

            ctx.check("the per-particle random is weighted in", randomWeight > 0f,
                    "> 0 so two particles differ", randomWeight);

            // the table itself: frame 0 and the middle frame must not be the same vertices, or the model
            // is pinned to whatever pose was baked into every row
            var table = animation.table();
            int stride = animation.vertexCount() * 4;
            int middle = animation.frames() / 2;
            float widest = 0f;
            for (int i = 0; i < stride; i++) {
                widest = Math.max(widest, Math.abs(table[i] - table[middle * stride + i]));
            }
            ctx.check("frame 0 and the middle frame are different poses", widest > 1.0e-3f,
                    "a moved vertex", "identical rows — a rest-pose bake");

            // and the phase actually spreads: the CPU and the shader both do fract(phase + random)
            int frames = animation.frames();
            int atRandomZero = (int) (fract(later) * frames);
            int atRandomHalf = (int) (fract(later + 0.5f) * frames);
            ctx.check("two particles half a turn apart land on different frames",
                    atRandomZero != atRandomHalf, "different frames", atRandomZero + " for both");
        } finally {
            AnimatedGltfModelSource.pinClock(null);
        }
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    private static void stopFox(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("foxExecutor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("foxExecutor");
    }
}
