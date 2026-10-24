package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.joml.Vector3f;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import net.minecraft.world.entity.Relative;

/**
 * Particle collision cost and correctness on a fixed site in the real client level: emitters are built in
 * code and ticked by hand (never handed to the particle engine), so each row times exactly the emitter's
 * own simulation. Writes {@code collision-bench.md}.
 */
@LDLRegisterClient(name = "collision_bench", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CollisionBenchScenario implements UIScenario {
    static final int SETTLE_TICKS = 60;
    static final int MEASURED_TICKS = 200;
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final List<String> ROWS = new ArrayList<>();

    enum Kind {
        /** radial burst over flat ground: bouncing, then resting contact */
        SPARKS,
        /** straight down onto a 1-block-thick floating platform: anything below it fell through */
        RAIN,
        /** onto stairs, slabs, fences, walls, panes and water */
        MIXED,
        /** floating mid-air: collision enabled, nothing to hit */
        AIR
    }

    record Case(String label, Kind kind, int particles, boolean collision) {
    }

    static final List<Case> CASES = List.of(
            new Case("sparks on flat ground", Kind.SPARKS, 2_000, true),
            new Case("sparks on flat ground", Kind.SPARKS, 10_000, true),
            new Case("sparks on flat ground", Kind.SPARKS, 30_000, true),
            new Case("sparks on flat ground, collision off", Kind.SPARKS, 30_000, false),
            new Case("rain on a 1-block platform", Kind.RAIN, 5_000, true),
            new Case("rain on mixed terrain", Kind.MIXED, 10_000, true),
            new Case("floating, nothing to hit", Kind.AIR, 30_000, true));

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(1_800_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("reset", ctx -> ROWS.clear())
                .server("build the site", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    buildSite(sc.level(), origin);
                    sc.player().teleportTo(sc.level(), origin.getX() + 24.5, origin.getY() + 14, origin.getZ() - 8.5, Set.<Relative>of(), 0, 30, true);
                })
                .serverTicks(40)
                .ticks(10)
                .waitUntil("site loaded on the client", ctx -> ctx.level() != null
                        && ctx.level().getBlockState(platformBlock(ORIGIN.get())).is(Blocks.STONE)
                        && ctx.level().getBlockState(ORIGIN.get().offset(24, 1, 30)).is(Blocks.STONE))
                .step("warm the JIT", ctx -> run(ctx, new Case("warm-up", Kind.SPARKS, 5_000, true), true, false));
        for (var c : CASES) {
            for (boolean parallel : new boolean[]{false, true}) {
                s.step("%s, %d particles, parallelUpdate %s".formatted(c.label(), c.particles(), parallel),
                        ctx -> run(ctx, c, parallel, true));
            }
        }
        s.step("write the table", CollisionBenchScenario::writeTable);
        s.teardownServer("put the world back", sc -> {
            var origin = ORIGIN.get();
            if (origin != null) clearSite(sc.level(), origin);
            LightSite.release(sc.player());
        });
    }

    static BlockPos platformBlock(BlockPos origin) {
        return origin.offset(36, 9, 12);
    }

    /** Server thread. {@code origin} is the top block of the superflat ground. */
    static void buildSite(ServerLevel level, BlockPos origin) {
        clearSite(level, origin);
        var stone = Blocks.STONE.defaultBlockState();
        // a floating 16x16 platform, one block thick, nine blocks of air under it
        for (int x = 28; x < 44; x++)
            for (int z = 4; z < 20; z++)
                level.setBlock(origin.offset(x, 9, z), stone, 2);
        // mixed terrain, z 28..52
        for (int x = 4; x < 44; x++) {
            for (int z = 28; z < 34; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, (x / 4) % 2 == 0 ? Direction.EAST : Direction.NORTH), 2);
            }
            for (int z = 34; z < 37; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.STONE_SLAB.defaultBlockState(), 2);
                // what the filter checks fall onto: grass a slab covered in an earlier run has turned to dirt
                level.setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
            for (int z = 37; z < 40; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.STONE_SLAB.defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.TOP), 2);
            }
            level.setBlockAndUpdate(origin.offset(x, 1, 41), Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 1, 44), Blocks.COBBLESTONE_WALL.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 1, 46), Blocks.GLASS_PANE.defaultBlockState());
        }
        for (int z = 28; z < 52; z++)
            for (int y = 1; y <= 3; y++)
                level.setBlock(origin.offset(24, y, z), stone, 2);
        for (int x = 32; x < 40; x++)
            for (int z = 47; z < 52; z++)
                level.setBlock(origin.offset(x, 0, z), Blocks.WATER.defaultBlockState(), 2);
    }

    static void clearSite(ServerLevel level, BlockPos origin) {
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -2; x < 50; x++)
            for (int z = -2; z < 56; z++)
                for (int y = 1; y <= 40; y++)
                    level.setBlock(origin.offset(x, y, z), air, 2);
        var grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = 32; x < 40; x++)
            for (int z = 47; z < 52; z++)
                level.setBlock(origin.offset(x, 0, z), grass, 2);
    }

    /** A seeded executor that never kills or moves its objects. */
    record BenchExecutor(Level level, RandomSource random) implements IEffectExecutor {
        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public RandomSource getRandomSource() {
            return random;
        }
    }

    static ParticleEmitter createEmitter(Case c, boolean parallel) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(false);
        config.setDuration(100_000);
        config.setStartLifetime(NumberFunction.constant(100_000));
        config.setStartSize(new NumberFunction3(new RandomConstant(0.08, 0.25), new RandomConstant(0.08, 0.25),
                new RandomConstant(0.08, 0.25)));
        config.setMaxParticles(c.particles());
        config.setSimulationSpace(ParticleConfig.Space.World);
        config.setParallelUpdate(parallel);
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.cycles = 1;
        burst.setCount(NumberFunction.constant(c.particles()));
        config.emission.getBursts().add(burst);

        var physics = config.physics;
        physics.setEnable(true);
        physics.setHasCollision(c.collision());
        // some drag, so every case stays on its own patch of the site
        physics.setFriction(NumberFunction.constant(0.95f));
        physics.setCollidedFriction(NumberFunction.constant(0.7f));
        switch (c.kind()) {
            case SPARKS -> {
                var sphere = new Sphere();
                sphere.setRadius(6);
                config.shape.setShape(sphere);
                config.setStartSpeed(new RandomConstant(2, 8));
                physics.setGravity(NumberFunction.constant(1));
                physics.setBounceChance(NumberFunction.constant(1));
                physics.setBounceRate(NumberFunction.constant(0.5f));
            }
            case RAIN, MIXED -> {
                config.shape.setShape(new Box());
                config.shape.setScale(c.kind() == Kind.RAIN ? new NumberFunction3(14, 1, 14) : new NumberFunction3(38, 1, 22));
                config.setStartSpeed(new RandomConstant(-12, -4));
                physics.setGravity(NumberFunction.constant(1));
                physics.setBounceChance(NumberFunction.constant(0.3f));
                physics.setBounceRate(NumberFunction.constant(0.4f));
            }
            case AIR -> {
                var sphere = new Sphere();
                sphere.setRadius(6);
                config.shape.setShape(sphere);
                config.setStartSpeed(new RandomConstant(0.5, 2));
                physics.setGravity(NumberFunction.constant(0));
                physics.setFriction(NumberFunction.constant(0.98f));
            }
        }
        return emitter;
    }

    static Vector3f center(Case c, BlockPos origin) {
        return switch (c.kind()) {
            case SPARKS -> new Vector3f(origin.getX() + 12, origin.getY() + 8, origin.getZ() + 12);
            case RAIN -> new Vector3f(origin.getX() + 36, origin.getY() + 22, origin.getZ() + 12);
            case MIXED -> new Vector3f(origin.getX() + 24, origin.getY() + 14, origin.getZ() + 40);
            case AIR -> new Vector3f(origin.getX() + 12, origin.getY() + 30, origin.getZ() + 40);
        };
    }

    static void run(TestContext ctx, Case c, boolean parallel, boolean record) {
        var level = ctx.level();
        ctx.require("a client level", level != null);
        var origin = ORIGIN.get();
        var emitter = createEmitter(c, parallel);
        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var runtime = fx.createInternalRuntime();
        var executor = new BenchExecutor(level, RandomSource.create(42L));
        for (var object : runtime.objects.values()) {
            object.setEffect(executor);
            object.reset();
            object.setLevel(level);
        }
        runtime.root.updatePos(center(c, origin));
        var ticking = new ArrayList<FXObject>();
        for (var object : runtime.objects.values()) {
            if (object instanceof FXObject fxObject) ticking.add(fxObject);
        }

        System.gc();
        for (int i = 0; i < SETTLE_TICKS; i++) {
            for (var object : ticking) object.tick();
        }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocatedBefore = threads.getCurrentThreadAllocatedBytes();
        var tickMs = new DoubleArrayList();
        for (int i = 0; i < MEASURED_TICKS; i++) {
            long start = System.nanoTime();
            emitter.tick();
            tickMs.add((System.nanoTime() - start) / 1e6);
            for (var object : ticking) {
                if (object != emitter) object.tick();
            }
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - allocatedBefore;

        int alive = 0, below = 0, inside = 0;
        double floor = switch (c.kind()) {
            case RAIN -> origin.getY() + 10;
            case SPARKS -> origin.getY() + 1;
            case MIXED, AIR -> Double.NEGATIVE_INFINITY;
        };
        for (var queue : emitter.getParticles().values()) {
            for (var particle : queue) {
                if (!(particle instanceof TileParticle tile) || !tile.isAlive()) continue;
                alive++;
                var box = tile.getRealBoundingBox(1);
                if (box.getCenter().y < floor - 0.01) below++;
                if (!level.noCollision(null, box.deflate(0.01))) inside++;
            }
        }
        for (var object : runtime.objects.values()) {
            if (object instanceof FXObject fxObject) fxObject.remove(true);
        }
        if (!record) return;
        var row = "| %s | %d | %s | %s | %.3f | %.3f | %.1f | %d | %s | %d |".formatted(
                c.label(), c.particles(), parallel ? "on" : "off", c.collision() ? "on" : "off",
                DynamicLightBenchScenario.percentile(tickMs, 0.5), DynamicLightBenchScenario.percentile(tickMs, 0.95),
                allocated / 1024.0 / MEASURED_TICKS, alive, floor == Double.NEGATIVE_INFINITY ? "-" : String.valueOf(below), inside);
        ROWS.add(row);
        ctx.log(row);
    }

    private static void writeTable(TestContext ctx) {
        var runtime = Runtime.getRuntime();
        var text = new StringBuilder()
                .append("# Collision bench\n\n")
                .append("CPU threads: ").append(runtime.availableProcessors())
                .append(", JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append("  \n")
                .append("Each row: one emitter built in code and ticked by hand in the client level, ")
                .append(SETTLE_TICKS).append(" ticks to settle, then ").append(MEASURED_TICKS)
                .append(" measured. Times are the emitter's own tick in milliseconds. Allocation is the client ")
                .append("thread only (parallel workers are not counted). `below` = particles under the surface they ")
                .append("should rest on (the platform top for rain, the ground for sparks); `inside` = particles whose ")
                .append("collision box, shrunk by 0.01, still overlaps a block's collision shape.\n\n")
                .append("| case | particles | parallelUpdate | collision | median ms/tick | p95 ms/tick | KB/tick (client thread) ")
                .append("| alive | below | inside |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        ROWS.forEach(row -> text.append(row).append('\n'));
        var file = ctx.outDir().resolve("collision-bench.md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
            ctx.log("wrote " + file.toAbsolutePath());
        } catch (IOException e) {
            ctx.check("bench table written", false, file.toString(), e.getMessage());
        }
    }
}
