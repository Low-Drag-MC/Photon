package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.PhotonRenderStats;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Relative;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost of Photon's rendering per workload, vsync off: CPU time of the tick, extraction, bake and recording, Photon's GPU
 * time from timestamps, draws, pipeline binds, passes and uploaded vertex data per frame. The table goes to
 * {@code build/photon-bench/}, outside the UI tests' output, so runs can be compared.
 */
@LDLRegisterClient(name = "photon_render_bench", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RenderBenchScenario implements UIScenario {
    private static final int WARMUP_FRAMES = 60;
    private static final int MEASURED_FRAMES = 240;
    private static final List<Identifier> TEXTURES = List.of("white", "circle", "ring", "smoke", "laser", "kila_tail")
            .stream().map(name -> Photon.id("textures/particle/" + name + ".png")).toList();
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final List<String> ROWS = new ArrayList<>();

    /**
     * {@code emitters} with {@code particles} each; {@code materials}: per emitter; {@code textures}: emitters cycle
     * through this many textures.
     */
    private record Case(String tag, int emitters, int particles, int materials, int textures, boolean instanced,
                        boolean oit) {
        int total() {
            return emitters * particles * materials;
        }
    }

    private static final List<Case> CASES = List.of(
            new Case("400 emitters x 50", 400, 50, 1, 1, false, false),
            new Case("4 emitters x 5000", 4, 5000, 1, 1, false, false),
            new Case("400 x 50, 2 materials", 400, 50, 2, 1, false, false),
            new Case("400 x 50, 6 textures", 400, 50, 1, 6, false, false),
            new Case("400 x 50, GPU instanced", 400, 50, 1, 1, true, false),
            new Case("4 x 5000, GPU instanced", 4, 5000, 1, 1, true, false),
            new Case("400 x 50, OIT", 400, 50, 1, 1, false, true),
            new Case("400 x 50, 6 textures, OIT", 400, 50, 1, 6, false, true));

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render", "bench").scenarioTimeoutMs(900_000).defaultTimeoutMs(60_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("uncap the frame rate, hide the gui, shaders off", ctx -> {
                    var options = ctx.mc().options;
                    LightTestConfig.set(options.framerateLimit(), 260);
                    LightTestConfig.set(options.enableVsync(), false);
                    LightTestConfig.set(options.inactivityFpsLimit(), InactivityFpsLimit.MINIMIZED);
                    LightTestConfig.set(options.chunkSectionFadeInTime(), 0.0);
                    LightTestConfig.set(PhotonConfig.INSTANCE.orderIndependentTransparency, true);
                    if (IrisCompat.isModInstalled()) LightTestConfig.selectShaderPack(null);
                    HiddenGui.hide(ctx.mc());
                    ROWS.clear();
                })
                .server("a flat view south", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    TestClock.set(sc.level(), 6000);
                    var player = sc.player();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(sc.level(), origin.getX() + 0.5, origin.getY() + 8, origin.getZ() + 0.5,
                            Set.<Relative>of(), 0f, 0f, true);
                })
                .serverTicks(20)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(30);
        Boolean[] improved = {null};
        for (var benchCase : selected()) {
            if (!Boolean.valueOf(benchCase.oit()).equals(improved[0])) {
                improved[0] = benchCase.oit();
                s.step("improved transparency " + (benchCase.oit() ? "on" : "off"),
                                ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(), benchCase.oit()))
                        .ticks(5)
                        .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                        .frames(30);
            }
            measure(s, benchCase);
        }
        s.step("write the table", RenderBenchScenario::writeTable)
                .teardown("restore", ctx -> {
                    PhotonRenderStats.recording = false;
                    stop(ctx);
                    HiddenGui.restore(ctx.mc());
                    LightTestConfig.restore();
                })
                .teardownServer("put the world back", sc -> LightSite.release(sc.player()));
    }

    /** {@code -Dphoton.bench.cases=a,b}: only the cases whose tag contains one of them, by index too ("#3"). */
    private static List<Case> selected() {
        var filter = System.getProperty("photon.bench.cases", "").trim();
        if (filter.isEmpty()) return CASES;
        var parts = List.of(filter.split(","));
        var selected = new ArrayList<Case>();
        for (int i = 0; i < CASES.size(); i++) {
            var benchCase = CASES.get(i);
            var index = "#" + i;
            if (parts.stream().anyMatch(part -> part.equals(index) || benchCase.tag().contains(part))) {
                selected.add(benchCase);
            }
        }
        return selected;
    }

    private static void measure(ScenarioBuilder s, Case benchCase) {
        var frameMs = new DoubleArrayList();
        long[] last = {0};
        int[] frame = {0};
        s.step("start " + benchCase.tag(), ctx -> start(ctx, benchCase))
                .frames(WARMUP_FRAMES)
                .step("measure " + benchCase.tag(), ctx -> {
                    long now = System.nanoTime();
                    if (frame[0] == 0) {
                        PhotonRenderStats.clear();
                        PhotonRenderStats.recording = true;
                        frameMs.clear();
                    } else {
                        frameMs.add((now - last[0]) / 1e6);
                    }
                    last[0] = now;
                    if (++frame[0] <= MEASURED_FRAMES) {
                        ctx.repeat("frame " + frame[0] + "/" + MEASURED_FRAMES);
                        return;
                    }
                    frame[0] = 0;
                    PhotonRenderStats.recording = false;
                    var row = "| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %.0f |".formatted(
                            benchCase.tag(), benchCase.total(),
                            median(PhotonRenderStats.DRAWS), median(PhotonRenderStats.BINDS),
                            median(PhotonRenderStats.PASSES), median(PhotonRenderStats.UPLOAD_KB),
                            stat(cpu(PhotonRenderStats.Cpu.TICK)), stat(cpu(PhotonRenderStats.Cpu.EXTRACT)),
                            stat(cpu(PhotonRenderStats.Cpu.PREPARE)), stat(cpu(PhotonRenderStats.Cpu.RECORD)),
                            stat(PhotonRenderStats.GPU_MS), stat(frameMs), 1000.0 / percentile(frameMs, 0.5));
                    ROWS.add(row);
                    ctx.log(row);
                    ctx.check(benchCase.tag() + ": Photon drew", percentile(PhotonRenderStats.DRAWS, 0.5) > 0);
                })
                .step("stop " + benchCase.tag(), RenderBenchScenario::stop)
                .frames(10);
    }

    private static DoubleArrayList cpu(PhotonRenderStats.Cpu cpu) {
        return PhotonRenderStats.CPU_MS[cpu.ordinal()];
    }

    private static String median(DoubleArrayList samples) {
        return samples.isEmpty() ? "-" : "%.0f".formatted(percentile(samples, 0.5));
    }

    private static String stat(DoubleArrayList samples) {
        return DynamicLightBenchScenario.stat(samples);
    }

    private static double percentile(DoubleArrayList samples, double p) {
        return DynamicLightBenchScenario.percentile(samples, p);
    }

    private static void start(TestContext ctx, Case benchCase) {
        stop(ctx);
        var fx = new FX();
        var grid = (int) Math.ceil(Math.sqrt(benchCase.emitters()));
        var spacing = benchCase.emitters() > 16 ? 1.5f : 6f;
        var size = spacing * 0.6f;
        for (int i = 0; i < benchCase.emitters(); i++) {
            var emitter = emitter(benchCase, i, size);
            float x = (i % grid - (grid - 1) / 2f) * spacing;
            float y = (float) (i / grid) * spacing;
            emitter.transform().localPosition(new Vector3f(x, y, 0));
            fx.getFxData().objects().add(emitter);
        }
        var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), ORIGIN.get().offset(0, 2, 32));
        executor.setOffset(0.5, 0, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("effect", executor);
    }

    private static ParticleEmitter emitter(Case benchCase, int index, float size) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(false);
        config.setDuration(100_000);
        config.setStartLifetime(NumberFunction.constant(100_000));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(0.25f, 0.25f, 0.25f));
        config.setMaxParticles(benchCase.particles());
        config.shape.setShape(new Box());
        config.shape.setScale(new NumberFunction3(size, size, size));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(benchCase.particles()));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        config.renderer.getMaterials().clear();
        for (int m = 0; m < benchCase.materials(); m++) {
            var texture = TEXTURES.get((index % benchCase.textures() + m) % TEXTURES.size());
            config.renderer.getMaterials().add(new MaterialSetting(new TextureMaterial(texture)));
        }
        config.renderer.setLayer(RendererSetting.Layer.Translucent);
        config.renderer.setUseGPUInstance(benchCase.instanced());
        return emitter;
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("effect");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
    }

    private static void writeTable(TestContext ctx) {
        var window = ctx.mc().getWindow();
        var device = RenderSystem.getDevice().getDeviceInfo();
        var text = new StringBuilder()
                .append("# Photon render bench\n\n")
                .append("GPU: ").append(device.name()).append(" (").append(device.backendName()).append(")  \n")
                .append("Resolution: ").append(window.getWidth()).append('x').append(window.getHeight())
                .append(", vsync off, frame rate uncapped, ").append(MEASURED_FRAMES).append(" frames per row  \n")
                .append("Times are milliseconds, median / p95; draws, binds, passes and uploads are per frame. ")
                .append("Tick = one client tick of Photon's objects; extract = culling and the bake list; ")
                .append("bake = geometry and its upload; record = passes and draws, bloom and composites included; ")
                .append("GPU = Photon's work between its timestamps.\n\n")
                .append("| case | particles | draws | pipeline binds | passes | upload KB | tick | extract | bake ")
                .append("| record | GPU | frame | FPS |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        ROWS.forEach(row -> text.append(row).append('\n'));
        var root = ctx.mc().gameDirectory.toPath().toAbsolutePath().normalize().getParent().getParent();
        var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var file = root.resolve("build/photon-bench/render-bench-%s-%s.md".formatted(
                device.backendName().toLowerCase(), stamp));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
            ctx.log("wrote " + file);
        } catch (IOException e) {
            ctx.check("bench table written", false, file.toString(), e.getMessage());
        }
    }
}
