package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialRequirements;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPipelines;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaScreenDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.StackedDistortion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every KilaMaterial preset, a page per category, each on what it is built for: lifetime-driven ones as a stream,
 * the trail on particle trails, the mesh ones on spheres, the ground ones over the ground. For a person to look at;
 * the checks are that every preset compiles on its paths and every page draws and moves.
 */
@LDLRegisterClient(name = "kila_gallery", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaGalleryScenario implements UIScenario {
    private static final double SPACING = 3.5;
    private static final double DISTANCE = 9;
    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);
    private static final Map<BlockPos, BlockState> WALL = new LinkedHashMap<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render").requiresWorld(true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every preset compiles on what it is built for", KilaGalleryScenario::compiles)
                .server("pin the sun at noon", sc -> {
                    var level = sc.level();
                    DAY_TIME.set(TestClock.time(level));
                    DAYLIGHT.set(TestClock.advancing(level));
                    TestClock.setAdvancing(level, false);
                    TestClock.set(level, 6000);
                })
                .server("a dark wall behind the row, so additive glows and bent air show", sc -> {
                    var player = sc.player();
                    var ahead = player.getDirection();
                    var right = ahead.getClockWise();
                    var base = player.blockPosition().relative(ahead, (int) DISTANCE + 5);
                    for (int across = -14; across <= 14; across++) {
                        for (int up = -1; up <= 9; up++) {
                            var pos = base.relative(right, across).above(up);
                            if (up < 0 && !sc.level().getBlockState(pos).isAir()) continue;
                            WALL.putIfAbsent(pos, sc.level().getBlockState(pos));
                            sc.level().setBlockAndUpdate(pos, Blocks.DEEPSLATE_TILES.defaultBlockState());
                        }
                    }
                })
                .ticks(2)
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(TestClock.time(ctx.mc().level) % 24000 - 6000) < 40)
                .waitUntil("the wall is drawn", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .ticks(10);
        for (var category : KilaPresets.Category.values()) {
            var page = category.name().toLowerCase();
            float pitch = category == KilaPresets.Category.GROUND ? 30 : 8;
            s.step("look at the " + page + " page", ctx -> {
                        var player = ctx.requirePlayer();
                        player.setXRot(pitch);
                        player.xRotO = pitch;
                    })
                    .frames(4)
                    .screenshot(page + "_empty")
                    .step("put up " + page, ctx -> start(ctx, category))
                    .ticks(40)
                    .screenshot(page)
                    .ticks(10)
                    .screenshot(page + "_later")
                    .step("the " + page + " page is drawn and moving", ctx -> check(ctx, page, category))
                    .step("take down " + page, KilaGalleryScenario::stop);
        }
        s.teardown("take it down", ctx -> {
                    stop(ctx);
                    ctx.requirePlayer().setXRot(0);
                })
                .teardownServer("put the world back", sc -> {
                    WALL.forEach((pos, state) -> sc.level().setBlockAndUpdate(pos, state));
                    WALL.clear();
                    if (DAY_TIME.get() == null) return;
                    TestClock.set(sc.level(), DAY_TIME.get());
                    TestClock.setAdvancing(sc.level(), DAYLIGHT.get());
                    DAY_TIME.set(null);
                });
    }

    /** A draw path: an instanced family, or the CPU geometry (null) with the records bound or not. */
    private record DrawPath(@Nullable PhotonPipelines.InstancedVariant variant, boolean cpuData) {
        String name() {
            return variant == null ? cpuData ? PhotonPipelines.CPU_DATA_DEFINE : "cpu" : String.join("+", variant.defines);
        }

        @Nullable
        PhotonPipelines.InstancedGeometryKey geometry() {
            return PipelineChecks.fullGeometry(variant);
        }
    }

    private static List<DrawPath> paths(KilaPresets.Target target) {
        return switch (target) {
            case PARTICLE -> List.of(new DrawPath(null, false), new DrawPath(null, true),
                    new DrawPath(PhotonPipelines.InstancedVariant.TILE, false));
            case TRAIL -> List.of(new DrawPath(null, false), new DrawPath(PhotonPipelines.InstancedVariant.TRAIL, false),
                    new DrawPath(PhotonPipelines.InstancedVariant.ARA, false));
            case MESH -> List.of(new DrawPath(null, false), new DrawPath(PhotonPipelines.InstancedVariant.MODEL, false));
        };
    }

    private static void compiles(TestContext ctx) {
        // a stacked distortion compiles another variant where a frame can stack it
        ctx.require("stacked distortion is available", StackedDistortion.available());
        var broken = new ArrayList<String>();
        try (var lease = new KilaPipelines.Lease()) {
            for (var preset : KilaPresets.ALL) {
                var material = preset.create();
                for (boolean stacking : material.screenDistortion.stack ? new boolean[]{false, true} : new boolean[]{false}) {
                    material.screenDistortion.stack = stacking;
                    var layout = material.layout();
                    if (stacking && !layout.defines.contains(KilaScreenDistortion.STACK_DEFINE)) {
                        broken.add(preset.id() + " stacked: not the stacked variant");
                    }
                    for (var path : paths(preset.target())) {
                        var pipeline = KilaPipelines.get(lease, layout, PhotonPipelines.ParticlePipelineKey.DEFAULT,
                                path.geometry(), path.cpuData());
                        if (pipeline == null) broken.add(preset.id() + (stacking ? " stacked/" : "/") + path.name());
                    }
                }
            }
        }
        ctx.check("every preset links on its draw paths", broken.isEmpty(), "none broken", broken);
    }

    private static void start(TestContext ctx, KilaPresets.Category category) {
        stop(ctx);
        var presets = KilaPresets.ALL.stream().filter(p -> p.category() == category).toList();
        var player = ctx.requirePlayer();
        var ahead = player.getDirection();
        var right = ahead.getClockWise();
        var executors = new ArrayList<BlockEffectExecutor>();
        for (int i = 0; i < presets.size(); i++) {
            var preset = presets.get(i);
            double across = (i - (presets.size() - 1) / 2.0) * SPACING;
            double along = category == KilaPresets.Category.GROUND ? DISTANCE - 3 : DISTANCE;
            var emitter = showcase(preset);
            var fx = new FX();
            fx.getFxData().objects().add(emitter);
            var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition());
            executor.setOffset(ahead.getStepX() * along + right.getStepX() * across, height(preset),
                    ahead.getStepZ() * along + right.getStepZ() * across);
            executor.setAllowMulti(true);
            executor.start();
            executors.add(executor);
        }
        ctx.put("executors", executors);
    }

    /** Above the block the player stands on, which the executor centres on. */
    private static double height(KilaPresets.Preset preset) {
        if (preset == KilaPresets.SCAN_PULSE) return -0.5;
        if (preset.category() == KilaPresets.Category.GROUND) return 0.6;
        return 1.4;
    }

    private static ParticleEmitter showcase(KilaPresets.Preset preset) {
        var material = preset.create();
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setMaxParticles(1);
        config.setStartSpeed(NumberFunction.constant(0));
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
        if (preset == KilaPresets.EXPLOSION_SMOKE) {
            stream(emitter, 40, 0.25f, 0.6f, 1.1f);
        } else if (preset == KilaPresets.TOON_FIRE) {
            stream(emitter, 24, 0.6f, 1.6f, 0.9f);
        } else if (preset == KilaPresets.SPARKS) {
            stream(emitter, 12, 2, 5, 0.12f);
            config.shape.setShape(new Sphere());
        } else if (preset == KilaPresets.JET_EXHAUST) {
            stream(emitter, 30, 1, 2, 0.8f);
        } else if (preset == KilaPresets.ENERGY_TRAIL) {
            stream(emitter, 30, 0.2f, 5, 0.3f);
            config.physics.setEnable(true);
            config.physics.setHasCollision(false);
            config.physics.setGravity(NumberFunction.constant(0.6f));
            config.renderer.setRenderMode(ParticleRendererSetting.Mode.None);
            config.renderer.getMaterials().clear();
            var trails = config.trails;
            trails.setEnable(true);
            trails.config.renderer.getMaterials().clear();
            trails.config.renderer.getMaterials().add(new MaterialSetting(material));
            MaterialRequirements.fix(trails.config.renderer.getMaterials(), trails.config.additionalGPUDataSetting, false,
                    trails.config.renderer::isUseGPUInstance, trails.config.renderer::setUseGPUInstance);
        } else {
            single(emitter, preset == KilaPresets.SCORCH_MARK || preset == KilaPresets.CHARGE_ORB ? 80 : 400, switch (preset.id()) {
                case "ground_circle", "scorch_mark", "caustics" -> 2.0f;
                case "scan_pulse" -> 3.0f;
                default -> preset.target() == KilaPresets.Target.MESH ? 1.8f : 1.2f;
            });
        }
        if (preset.target() == KilaPresets.Target.MESH) {
            config.renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
            config.renderer.setModel(new MeshData(new ObjModelSource(Photon.id(preset == KilaPresets.DEBRIS
                    ? "models/cube.obj" : "models/sphere.obj"))));
        }
        if (preset == KilaPresets.CHARGE_ORB) {
            MaterialRequirements.fix(config.renderer.getMaterials(), config.additionalGPUDataSetting, true,
                    config.renderer::isUseGPUInstance, config.renderer::setUseGPUInstance);
            config.additionalGPUDataSetting.customDataStreams().getFirst().getChannels().set(0,
                    new Curve(0, 1, 0, 1, "t", "charge", new ECBCurves(0, 0, 0.1f, 0.25f, 0.9f, 1f, 1, 1)));
        }
        return emitter;
    }

    /** One particle at a time, born again every {@code lifetime} ticks. */
    private static void single(ParticleEmitter emitter, int lifetime, float size) {
        var config = emitter.config;
        config.setDuration(lifetime);
        config.setStartLifetime(NumberFunction.constant(lifetime));
        config.setStartSize(new NumberFunction3(size, size, size));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
    }

    private static void stream(ParticleEmitter emitter, int lifetime, float perTick, float speed, float size) {
        var config = emitter.config;
        config.setDuration(100);
        config.setMaxParticles(200);
        config.setStartLifetime(NumberFunction.constant(lifetime));
        config.setStartSpeed(NumberFunction.constant(speed));
        config.setStartSize(new NumberFunction3(size, size, size));
        config.shape.setScale(new NumberFunction3(0.4, 0.4, 0.4));
        config.emission.setEmissionRate(NumberFunction.constant(perTick));
    }

    private static void check(TestContext ctx, String page, KilaPresets.Category category) {
        var empty = ScreenshotCompare.load(ctx, page + "_empty");
        var shot = ScreenshotCompare.load(ctx, page);
        var later = ScreenshotCompare.load(ctx, page + "_later");
        ctx.require("the " + page + " captures were written", empty != null && shot != null && later != null);
        long count = KilaPresets.ALL.stream().filter(p -> p.category() == category).count();
        // a distortion only moves what is behind it
        long least = count * (category == KilaPresets.Category.SCREEN ? 500 : 1500);
        var drawn = shot.diff(empty, 24, shot.whole());
        ctx.check("the " + page + " presets are on screen", drawn.count() > least, "> " + least + " px",
                drawn.count() + " px at " + drawn.box());
        var moved = later.diff(shot, 12, shot.whole());
        ctx.check("and they move", moved.count() > 500, "> 500 px", moved.count() + " px at " + moved.box());
    }

    private static void stop(TestContext ctx) {
        List<BlockEffectExecutor> executors = ctx.get("executors");
        if (executors != null) {
            for (var executor : executors) {
                if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
            }
        }
        ctx.state().remove("executors");
    }
}
