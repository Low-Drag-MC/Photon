package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.LightPassTimer;
import com.lowdragmc.photon.client.light.ShadowMode;
import com.lowdragmc.photon.client.light.VoxelWorld;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import com.mojang.blaze3d.platform.GlUtil;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost of the light pass per configuration on the demo courtyard, vsync off: GPU time per stage, CPU time
 * and frame time against a lights-off baseline, plus a cold voxel build. Writes {@code bench-<w>x<h>.md}.
 */
@LDLRegisterClient(name = "dynamic_light_bench", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightBenchScenario implements UIScenario {
    private static final int WARMUP_FRAMES = 40;
    private static final int MEASURED_FRAMES = 240;
    private static final String PACK = "ComplementaryReimagined_r5.5.1.zip";
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final List<String> ROWS = new ArrayList<>();
    private static final List<String> COLD = new ArrayList<>();

    /**
     * extra = scattered lights on top of the 8 demo lights; shadowedExtra of them want shadows (the budget caps at 8);
     * volumetric = the demo lights' volumetric strength; fog = the courtyard fog box.
     */
    private record Config(String label, boolean lights, int extra, int shadowedExtra, ShadowMode mode, boolean soft, float scale,
                          float volumetric, boolean fog) {
        Config(String label, boolean lights, int extra, int shadowedExtra, ShadowMode mode, boolean soft, float scale) {
            this(label, lights, extra, shadowedExtra, mode, soft, scale, 0f, false);
        }

        Config(String label, boolean lights, int extra, int shadowedExtra, ShadowMode mode, boolean soft, float scale, float volumetric) {
            this(label, lights, extra, shadowedExtra, mode, soft, scale, volumetric, false);
        }
    }

    private static final List<Config> CONFIGS = List.of(
            new Config("lights off (baseline)", false, 0, 0, ShadowMode.OFF, true, 0.5f),
            new Config("8 lights, no shadows, half res", true, 0, 0, ShadowMode.OFF, true, 0.5f),
            new Config("8 lights, screen shadows, full res (old)", true, 0, 0, ShadowMode.SCREEN, false, 1f),
            new Config("8 lights, voxel soft, half res (default)", true, 0, 0, ShadowMode.VOXEL, true, 0.5f),
            new Config("8 lights, voxel hard, half res", true, 0, 0, ShadowMode.VOXEL, false, 0.5f),
            new Config("8 lights, voxel soft, full res", true, 0, 0, ShadowMode.VOXEL, true, 1f),
            new Config("64 lights, no shadows, half res", true, 56, 0, ShadowMode.OFF, true, 0.5f),
            new Config("64 lights, 8 voxel soft, half res", true, 56, 0, ShadowMode.VOXEL, true, 0.5f),
            new Config("256 lights, 8 voxel soft, half res", true, 248, 0, ShadowMode.VOXEL, true, 0.5f),
            new Config("1024 lights, 8 voxel soft, half res", true, 1016, 0, ShadowMode.VOXEL, true, 0.5f),
            new Config("8 volumetric lights, voxel soft, half res", true, 0, 0, ShadowMode.VOXEL, true, 0.5f, 1f),
            new Config("64 volumetric lights, voxel soft, half res", true, 56, 0, ShadowMode.VOXEL, true, 0.5f, 1f),
            new Config("256 volumetric lights, voxel soft, half res", true, 248, 0, ShadowMode.VOXEL, true, 0.5f, 1f),
            new Config("1024 volumetric lights, voxel soft, half res", true, 1016, 0, ShadowMode.VOXEL, true, 0.5f, 1f),
            new Config("8 lights in a fog volume, voxel soft, half res", true, 0, 0, ShadowMode.VOXEL, true, 0.5f, 0f, true),
            new Config("64 lights in a fog volume, voxel soft, half res", true, 56, 0, ShadowMode.VOXEL, true, 0.5f, 0f, true));

    private static final List<Config> PACK_CONFIGS = List.of(CONFIGS.get(0), CONFIGS.get(3), CONFIGS.get(8));

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        var restore = new int[2];
        s.step("uncap the frame rate, hide the gui", ctx -> {
                    var options = ctx.mc().options;
                    restore[0] = options.framerateLimit().get();
                    restore[1] = options.enableVsync().get() ? 1 : 0;
                    options.framerateLimit().set(260);
                    options.enableVsync().set(false);
                    ctx.mc().getWindow().updateVsync(false);
                    ctx.mc().getWindow().setFramerateLimit(260);
                    options.hideGui = true;
                    LightDemoScene.frozenSeconds = 3f;
                    ROWS.clear();
                    COLD.clear();
                })
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    LightDemoScene.build(sc.level(), origin);
                })
                .serverTicks(40)
                .server("go to the courtyard", sc -> LightDemoScene.teleport(sc.player(), ORIGIN.get(),
                        LightDemoScene.view("courtyard")))
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(60);

        for (var config : CONFIGS) {
            measure(s, "vanilla", config);
        }
        coldBuild(s);

        s.step("load " + PACK, ctx -> ctx.check("Iris accepted " + PACK, LightTestConfig.selectShaderPack(PACK)))
                .waitUntil("pack active", ctx -> IrisCompat.isUsingShaderPack())
                .frames(120);
        for (var config : PACK_CONFIGS) {
            measure(s, "complementary", config);
        }

        s.step("write the table", DynamicLightBenchScenario::writeTable);

        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("restore", ctx -> {
            var options = ctx.mc().options;
            options.framerateLimit().set(restore[0] == 0 ? 120 : restore[0]);
            options.enableVsync().set(restore[1] == 1);
            ctx.mc().getWindow().updateVsync(restore[1] == 1);
            options.hideGui = false;
            LightTestConfig.restore();
            LightPassTimer.recording = false;
            LightDemoScene.stopLights();
        });
    }

    static int bricks(TestContext ctx) {
        var voxels = DynamicLightManager.voxels(ctx.requirePlayer().level());
        return voxels == null ? 0 : voxels.brickCount();
    }

    /** Drops every brick and times the rebuild with VoxelWorld's cumulative counters. */
    static void coldBuild(ScenarioBuilder s) {
        long[] builtBefore = {0};
        double[] millisBefore = {0};
        s.step("drop the voxel world", ctx -> {
                    LightDemoScene.startLights(ORIGIN.get());
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.VOXEL);
                    builtBefore[0] = VoxelWorld.builtTotal();
                    millisBefore[0] = VoxelWorld.buildMillisTotal();
                    var voxels = DynamicLightManager.voxels(ctx.requirePlayer().level());
                    if (voxels != null) voxels.clear();
                })
                .frames(30)
                .step("measure the rebuild", ctx -> {
                    int built = (int) (VoxelWorld.builtTotal() - builtBefore[0]);
                    double millis = VoxelWorld.buildMillisTotal() - millisBefore[0];
                    double perSection = built == 0 ? 0 : millis / built;
                    double budget = PhotonConfig.INSTANCE.lightVoxelBudgetMs.get();
                    COLD.add(("Cold voxel build: %d shadowed lights; %d sections voxelised in %.2f ms of CPU, %.3f ms per "
                            + "section (%d mixed bricks resident); the %.1f ms budget covers about %d sections a frame.")
                            .formatted(DynamicLightRenderer.lastShadowedCount(), built, millis, perSection,
                                    bricks(ctx), budget, perSection > 0 ? (int) (budget / perSection) : 0));
                    ctx.log(COLD.getLast());
                });
    }

    /** The cold build on its own. */
    @LDLRegisterClient(name = "dynamic_light_voxel_build", group = "photon", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class VoxelBuild implements UIScenario {
        @Override
        public void define(ScenarioBuilder s) {
            s.step("pin the lights", ctx -> {
                        ctx.mc().options.hideGui = true;
                        LightDemoScene.frozenSeconds = 3f;
                        COLD.clear();
                    })
                    .server("build the scene", sc -> {
                        var origin = LightSite.claim(sc.player());
                        ORIGIN.set(origin);
                        LightDemoScene.build(sc.level(), origin);
                    })
                    .serverTicks(40)
                    .server("go to the courtyard", sc -> LightDemoScene.teleport(sc.player(), ORIGIN.get(),
                            LightDemoScene.view("courtyard")))
                    .ticks(5)
                    .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                    .frames(30);
            coldBuild(s);
            s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
            s.teardown("restore", ctx -> {
                ctx.mc().options.hideGui = false;
                LightTestConfig.restore();
                LightDemoScene.stopLights();
            });
        }
    }

    private static void measure(ScenarioBuilder s, String renderer, Config config) {
        var frameMs = new DoubleArrayList();
        long[] last = {0};
        int[] frame = {0};
        String label = renderer + " / " + config.label();
        s.step("set up " + label, ctx -> {
                    LightDemoScene.startLights(ORIGIN.get());
                    LightDemoScene.addScatteredLights(ORIGIN.get(), config.extra(), config.shadowedExtra(), 42L);
                    LightDemoScene.volumetric = config.volumetric();
                    LightDemoScene.fogDensity = config.fog() ? 1f : 0f;
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, config.lights());
                    LightTestConfig.volumetricDefaults();
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, config.mode());
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightSoftShadows, config.soft());
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightResolution, (double) config.scale());
                })
                .frames(WARMUP_FRAMES)
                .step("measure " + label, ctx -> {
                    long now = System.nanoTime();
                    if (frame[0] == 0) {
                        LightPassTimer.clearSamples();
                        LightPassTimer.recording = true;
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
                    LightPassTimer.recording = false;
                    var row = "| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %.0f |".formatted(
                            renderer, config.label(), config.lights() ? DynamicLightRenderer.lastLightCount() : 0,
                            stage(LightPassTimer.Stage.COPY), stage(LightPassTimer.Stage.LIGHT), stage(LightPassTimer.Stage.BLUR),
                            stage(LightPassTimer.Stage.VOLUME), stage(LightPassTimer.Stage.COMPOSITE), stat(LightPassTimer.TOTAL_MS),
                            stat(LightPassTimer.CPU_MS), stat(frameMs), 1000.0 / percentile(frameMs, 0.5));
                    ROWS.add(row);
                    ctx.log(row);
                });
    }

    private static String stage(LightPassTimer.Stage stage) {
        return stat(LightPassTimer.STAGE_MS[stage.ordinal()]);
    }

    private static String stat(DoubleArrayList samples) {
        if (samples.isEmpty()) return "-";
        return "%.3f / %.3f".formatted(percentile(samples, 0.5), percentile(samples, 0.95));
    }

    private static double percentile(DoubleArrayList samples, double p) {
        if (samples.isEmpty()) return Double.NaN;
        double[] sorted = samples.toDoubleArray();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1) + 0.5))];
    }

    private static void writeTable(TestContext ctx) {
        var window = ctx.mc().getWindow();
        var text = new StringBuilder()
                .append("# Dynamic light bench\n\n")
                .append("GPU: ").append(GlUtil.getRenderer()).append("  \n")
                .append("Resolution: ").append(window.getWidth()).append('x').append(window.getHeight())
                .append(", vsync off, frame rate uncapped, demo courtyard view, ")
                .append(MEASURED_FRAMES).append(" frames per row  \n")
                .append("Times are milliseconds, median / p95. Copy = colour+depth snapshot; light = clustered ")
                .append("light + shadow pass; blur = soft-shadow blur; volume = volumetric in-scatter; ")
                .append("composite = upsample and add; ")
                .append("CPU = collection, culling, cluster build, voxel update and upload.\n\n")
                .append("| renderer | config | lights drawn | GPU copy | GPU light | GPU blur | GPU volume | GPU composite ")
                .append("| GPU total | CPU | frame | FPS |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        ROWS.forEach(row -> text.append(row).append('\n'));
        COLD.forEach(line -> text.append('\n').append(line).append('\n'));
        var file = ctx.outDir().resolve("bench-%dx%d.md".formatted(window.getWidth(), window.getHeight()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
            ctx.log("wrote " + file.toAbsolutePath());
        } catch (IOException e) {
            ctx.check("bench table written", false, file.toString(), e.getMessage());
        }
    }
}
