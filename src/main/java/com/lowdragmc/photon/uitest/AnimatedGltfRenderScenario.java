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
            s.step("stop the fox (" + tag + ")", AnimatedGltfRenderScenario::stopFox);
        }

        // Per-particle phase: one baked table, every particle at its own frame. The point of the capture
        // is that the poses DIFFER between particles, which no numeric check states as plainly.
        // ⚠️ both paths: GPU instancing is off by default, and the CPU one reads the same baked table
        for (boolean instanced : List.of(false, true)) {
            String tag = instanced ? "instanced" : "cpu";
            s.step("play a swarm with per-particle phase (" + tag + ")",
                            ctx -> startSwarm(ctx, instanced))
                    .frames(6)
                    .screenshot("fox_swarm_" + tag)
                    .step("stop the swarm (" + tag + ")", AnimatedGltfRenderScenario::stopFox);
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

    /** The fixture lives in the test source set, off the client run's classpath, so read it off disk. */
    /** Package-visible: {@link EditorModelInstanceScenario} stages the same fixture, and the list of
     *  candidate paths is a function of where the client's working directory is, not of the scenario. */
    static byte[] readFixture() {
        var classpath = AnimatedGltfRenderScenario.class.getResourceAsStream("/assets/photon/models/fox.glb");
        if (classpath != null) {
            try (var in = classpath) {
                return in.readAllBytes();
            } catch (IOException ignored) {
                // fall through to the disk candidates
            }
        }
        for (String candidate : List.of(
                "src/test/resources/assets/photon/models/fox.glb",
                "../src/test/resources/assets/photon/models/fox.glb",
                "../../src/test/resources/assets/photon/models/fox.glb")) {
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

        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        renderer.setModel(new MeshData(new AnimatedGltfModelSource(MODEL)));
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
    private static void startSwarm(TestContext ctx, boolean instanced) {
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
        assertThePoseActuallyMoves(ctx, source);
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
