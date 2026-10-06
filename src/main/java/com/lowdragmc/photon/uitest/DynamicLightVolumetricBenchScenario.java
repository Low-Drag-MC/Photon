package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.FogProvider;
import com.lowdragmc.photon.client.light.FogVolume;
import com.lowdragmc.photon.client.light.LightPassTimer;
import com.lowdragmc.photon.client.light.LightPassTimer.Stage;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.ShadowMode;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import com.mojang.blaze3d.platform.GlUtil;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost of volumetric lights and fog volumes on the demo courtyard, vsync off: GPU time per haze stage next to the
 * surface pass, CPU and frame time, swept over light count, shadows, cones, size on screen, quality settings and fog.
 * Writes {@code volumetric-bench-<w>x<h>.md}.
 */
@LDLRegisterClient(name = "dynamic_light_volumetric_bench", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightVolumetricBenchScenario implements UIScenario {
    private static final String PACK = "ComplementaryReimagined_r5.5.1.zip";
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final List<DynamicLight> LIGHTS = new ArrayList<>();
    private static final List<FogVolume> FOGS = new ArrayList<>();
    private static final LightProvider PROVIDER = (sink, partialTick) -> LIGHTS.forEach(light -> sink.next().set(light));
    private static final FogProvider FOG_PROVIDER = (sink, partialTick) -> FOGS.forEach(fog -> sink.next().set(fog));
    private static final Map<String, List<String>> TABLES = new LinkedHashMap<>();

    /**
     * {@code demo}: the courtyard's 8 lights (one a 30-block searchlight spot, all casting shadows) at volumetric
     * strength {@code haze}; otherwise {@code count} scattered lights at that strength, the first {@code shadowed}
     * casting shadows. {@code fogs} boxes share the courtyard's fog box; {@code around} is one big box round the camera.
     */
    private static final class Row {
        final String label;
        boolean lights = true, demo, spot, noise, around;
        float haze, range = 5f, absorption = 0.5f, scale = 0.5f;
        int count, shadowed, fogs, samples = 4, budget = 8;

        Row(String label) {
            this.label = label;
        }

        Row off() {
            lights = false;
            return this;
        }

        Row shadowed(int shadowed) {
            this.shadowed = shadowed;
            budget = Math.max(budget, shadowed);
            return this;
        }

        Row budget(int budget) {
            this.budget = budget;
            return this;
        }

        Row range(float range) {
            this.range = range;
            return this;
        }

        Row spot() {
            spot = true;
            return this;
        }

        Row samples(int samples) {
            this.samples = samples;
            return this;
        }

        Row scale(float scale) {
            this.scale = scale;
            return this;
        }

        Row fogs(int fogs) {
            this.fogs = fogs;
            return this;
        }

        Row noise() {
            noise = true;
            return this;
        }

        Row absorption(float absorption) {
            this.absorption = absorption;
            return this;
        }

        Row around() {
            around = true;
            return this;
        }
    }

    private record Group(String title, List<Row> rows) {
    }

    private static Row demo(String label, float haze) {
        var row = new Row(label);
        row.demo = true;
        row.haze = haze;
        return row;
    }

    private static Row scattered(String label, int count, float haze) {
        var row = new Row(label);
        row.count = count;
        row.haze = haze;
        return row;
    }

    private static final List<Group> VANILLA = List.of(
            new Group("Reference", List.of(
                    demo("lights off", 0).off(),
                    demo("8 demo lights, no haze", 0))),
            new Group("The demo courtyard in haze: 8 lights, one a 30-block searchlight spot, all casting shadows", List.of(
                    demo("haze, no shadowed lights", 1).budget(0),
                    demo("haze, 8 shadowed (defaults)", 1),
                    demo("1 sample", 1).samples(1),
                    demo("2 samples", 1).samples(2),
                    demo("8 samples", 1).samples(8),
                    demo("16 samples", 1).samples(16),
                    demo("quarter res", 1).scale(0.25f),
                    demo("full res", 1).scale(1f))),
            new Group("Light count: scattered point lights of range 5 over the courtyard, no shadows", List.of(
                    scattered("64 lights, no haze", 64, 0),
                    scattered("8 volumetric", 8, 1),
                    scattered("32 volumetric", 32, 1),
                    scattered("64 volumetric", 64, 1),
                    scattered("128 volumetric", 128, 1),
                    scattered("256 volumetric", 256, 1),
                    scattered("512 volumetric", 512, 1),
                    scattered("1024 lights, no haze", 1024, 0),
                    scattered("1024 volumetric", 1024, 1))),
            new Group("Shadowed haze: 64 volumetric lights, some with visibility maps", List.of(
                    scattered("none shadowed", 64, 1),
                    scattered("8 shadowed", 64, 1).shadowed(8),
                    scattered("16 shadowed", 64, 1).shadowed(16),
                    scattered("32 shadowed (the most there can be)", 64, 1).shadowed(32))),
            new Group("Cones and size on screen: volumetric lights, no shadows", List.of(
                    scattered("64 spots, 30 degree cone, range 5", 64, 1).spot(),
                    scattered("256 spots", 256, 1).spot(),
                    scattered("64 points, range 3", 64, 1).range(3),
                    scattered("64 points, range 10 (the camera inside the nearest)", 64, 1).range(10),
                    scattered("64 points, range 20 (the camera inside all)", 64, 1).range(20))),
            new Group("Fog volumes: the courtyard box, 14 x 5 x 12 blocks, density 1, absorption 0.5", List.of(
                    scattered("a fog box, no lights", 0, 0).fogs(1),
                    demo("a fog box, 8 demo lights", 0).fogs(1),
                    demo("a fog box, 8 demo lights in haze", 1).fogs(1),
                    scattered("a fog box, 64 lights", 64, 0).fogs(1),
                    scattered("a fog box, 256 lights", 256, 0).fogs(1),
                    demo("8 demo lights, with noise", 0).fogs(1).noise(),
                    demo("8 demo lights, not absorbing", 0).fogs(1).absorption(0),
                    demo("8 demo lights, 8 samples (32 steps)", 0).fogs(1).samples(8),
                    demo("8 demo lights, 16 samples (64 steps)", 0).fogs(1).samples(16),
                    demo("8 demo lights, the box split in 4", 0).fogs(4),
                    demo("8 demo lights, split in 16", 0).fogs(16),
                    demo("8 demo lights, split in 32 (the most drawn)", 0).fogs(32),
                    demo("8 demo lights, the camera inside a 48 x 16 x 48 box", 0).around())));

    private static final List<Group> PACK_ROWS = List.of(
            new Group("Complementary Reimagined", List.of(
                    demo("lights off", 0).off(),
                    demo("8 demo lights, no haze", 0),
                    demo("haze, 8 shadowed (defaults)", 1),
                    demo("haze and a fog box", 1).fogs(1),
                    scattered("256 volumetric lights", 256, 1))));

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(900_000);
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
                    LightTestConfig.set(options.fov(), 70);
                    LightDemoScene.frozenSeconds = 3f;
                    TABLES.clear();
                    PhotonLights.addProvider(PROVIDER);
                    PhotonLights.addFogProvider(FOG_PROVIDER);
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

        for (var group : VANILLA) {
            for (var row : group.rows()) measure(s, "vanilla", group.title(), row, false);
        }

        int[] waited = {0};
        s.step("load " + PACK, ctx -> {
                    if (LightTestConfig.hasShaderPack(PACK)) {
                        ctx.check("Iris accepted " + PACK, LightTestConfig.selectShaderPack(PACK));
                    } else {
                        ctx.log("no " + PACK + " in shaderpacks/: the shader pack rows are skipped");
                    }
                })
                .step("wait for the pack", ctx -> {
                    if (LightTestConfig.hasShaderPack(PACK) && !IrisCompat.isUsingShaderPack() && ++waited[0] < 600) ctx.repeat("pack");
                })
                .frames(120);
        for (var group : PACK_ROWS) {
            for (var row : group.rows()) measure(s, "complementary", group.title(), row, true);
        }

        s.step("write the table", DynamicLightVolumetricBenchScenario::writeTable);

        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("restore", ctx -> {
            var options = ctx.mc().options;
            options.framerateLimit().set(restore[0] == 0 ? 120 : restore[0]);
            options.enableVsync().set(restore[1] == 1);
            ctx.mc().getWindow().updateVsync(restore[1] == 1);
            options.hideGui = false;
            PhotonLights.removeProvider(PROVIDER);
            PhotonLights.removeFogProvider(FOG_PROVIDER);
            LIGHTS.clear();
            FOGS.clear();
            LightPassTimer.recording = false;
            LightDemoScene.stopLights();
            LightTestConfig.restore();
        });
    }

    private static void measure(ScenarioBuilder s, String renderer, String group, Row row, boolean pack) {
        var frameMs = new DoubleArrayList();
        long[] last = {0};
        int[] frame = {0};
        String label = renderer + " / " + row.label;
        s.step("set up " + label, ctx -> {
                    var origin = ORIGIN.get();
                    if (row.demo) {
                        LightDemoScene.startLights(origin);
                    } else {
                        LightDemoScene.stopLights();
                    }
                    LightDemoScene.volumetric = row.demo ? row.haze : 0f;
                    scatter(row, origin);
                    fog(row, origin);
                    var config = PhotonConfig.INSTANCE;
                    LightTestConfig.set(config.dynamicLights, row.lights);
                    LightTestConfig.volumetricDefaults();
                    LightTestConfig.set(config.volumetricSamples, row.samples);
                    LightTestConfig.set(config.lightShadowMode, ShadowMode.VOXEL);
                    LightTestConfig.set(config.lightSoftShadows, true);
                    LightTestConfig.set(config.lightShadowedLights, row.budget);
                    LightTestConfig.set(config.lightResolution, (double) row.scale);
                })
                .frames(DynamicLightBenchScenario.WARMUP_FRAMES)
                .step("measure " + label, ctx -> {
                    if (pack && !IrisCompat.isUsingShaderPack()) return;
                    long now = System.nanoTime();
                    if (frame[0] == 0) {
                        LightPassTimer.clearSamples();
                        LightPassTimer.recording = true;
                        frameMs.clear();
                    } else {
                        frameMs.add((now - last[0]) / 1e6);
                    }
                    last[0] = now;
                    if (++frame[0] <= DynamicLightBenchScenario.MEASURED_FRAMES) {
                        ctx.repeat("frame " + frame[0] + "/" + DynamicLightBenchScenario.MEASURED_FRAMES);
                        return;
                    }
                    frame[0] = 0;
                    LightPassTimer.recording = false;
                    if (row.lights) {
                        ctx.check("timed " + label, !LightPassTimer.TOTAL_MS.isEmpty(), "> 0 frames", LightPassTimer.TOTAL_MS.size());
                    }
                    if (row.lights && row.haze > 0 && (row.demo || row.count > 0)) {
                        ctx.check("haze drawn in " + label, DynamicLightRenderer.lastVolumeCount() > 0, "> 0 volumetric lights",
                                DynamicLightRenderer.lastVolumeCount());
                    }
                    if (row.lights && (row.fogs > 0 || row.around)) {
                        ctx.check("fog drawn in " + label, DynamicLightRenderer.lastFogCount() > 0, "> 0 fog volumes",
                                DynamicLightRenderer.lastFogCount());
                    }
                    var line = "| %s | %d (%d) | %d | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %.0f |".formatted(
                            row.label, DynamicLightRenderer.lastVolumeCount(), DynamicLightRenderer.lastVolumeShadowedCount(),
                            DynamicLightRenderer.lastVisibilityMapCount(), DynamicLightRenderer.lastFogCount(),
                            median(Stage.VISIBILITY), median(Stage.VOLUME), median(Stage.FOG), median(Stage.VOLUME_BLUR),
                            median(Stage.HAZE), DynamicLightBenchScenario.stat(LightPassTimer.sum(DynamicLightBenchScenario.HAZE_STAGES)),
                            DynamicLightBenchScenario.stat(LightPassTimer.sum(Stage.COPY, Stage.LIGHT, Stage.BLUR, Stage.COMPOSITE)),
                            DynamicLightBenchScenario.stat(LightPassTimer.CPU_MS), DynamicLightBenchScenario.stat(frameMs),
                            1000.0 / DynamicLightBenchScenario.percentile(frameMs, 0.5));
                    TABLES.computeIfAbsent(renderer + ": " + group, title -> new ArrayList<>()).add(line);
                    ctx.log(line);
                });
    }

    private static String median(Stage stage) {
        var samples = LightPassTimer.STAGE_MS[stage.ordinal()];
        return samples.isEmpty() ? "-" : "%.3f".formatted(DynamicLightBenchScenario.percentile(samples, 0.5));
    }

    /** Over the courtyard, like {@link LightDemoScene#addScatteredLights}, with the same seed for every row. */
    private static void scatter(Row row, BlockPos origin) {
        LIGHTS.clear();
        var random = new Random(42L);
        for (int i = 0; i < row.count; i++) {
            int rgb = Mth.hsvToRgb(random.nextFloat(), 0.8f, 1f);
            var light = new DynamicLight()
                    .color((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f)
                    .intensity(6).range(row.range).shadows(i < row.shadowed).volumetric(row.haze)
                    .at(origin.getX() + 0.5 + (random.nextDouble() * 2 - 1) * 6, origin.getY() + 1.6 + random.nextDouble() * 2.5,
                            origin.getZ() + 12.5 + (random.nextDouble() * 2 - 1) * 6);
            if (row.spot) light.spot(20, 30).direction(0, -1, 0);
            LIGHTS.add(light);
        }
    }

    /** The courtyard's fog box ({@code /photonlight fog}) cut into {@code fogs} equal boxes. */
    private static void fog(Row row, BlockPos origin) {
        FOGS.clear();
        if (row.around) {
            FOGS.add(new FogVolume().at(origin.getX() + 0.5, origin.getY() + 6, origin.getZ() + 10).size(48, 16, 48)
                    .absorption(row.absorption));
            return;
        }
        if (row.fogs <= 0) return;
        int across = row.fogs >= 16 ? 4 : row.fogs >= 4 ? 2 : 1;
        int up = row.fogs / (across * across);
        float sx = 14f / across, sy = 5f / up, sz = 12f / across;
        for (int x = 0; x < across; x++) {
            for (int y = 0; y < up; y++) {
                for (int z = 0; z < across; z++) {
                    var fog = new FogVolume().at(origin.getX() - 6.5 + sx * (x + 0.5), origin.getY() + 1 + sy * (y + 0.5),
                            origin.getZ() + 6.5 + sz * (z + 0.5)).size(sx, sy, sz).absorption(row.absorption);
                    if (row.noise) fog.noise(0.6f, 2f);
                    FOGS.add(fog);
                }
            }
        }
    }

    private static void writeTable(TestContext ctx) {
        var window = ctx.mc().getWindow();
        var text = new StringBuilder()
                .append("# Volumetric light bench\n\n")
                .append("GPU: ").append(GlUtil.getRenderer()).append("  \n")
                .append("Resolution: ").append(window.getWidth()).append('x').append(window.getHeight())
                .append(", vsync off, frame rate uncapped, demo courtyard view, FOV 70, ")
                .append(DynamicLightBenchScenario.MEASURED_FRAMES).append(" frames per row  \n")
                .append("Unless a row says otherwise: voxel soft shadows, 8 shadowed lights, light and haze buffers at half res, ")
                .append("4 volumetric samples, density 1, forward scattering 0.3.\n\n")
                .append("Milliseconds. Single stages show the median, totals median / p95. Visibility = the shadowed lights' ")
                .append("visibility maps; in-scatter = the volumetric lights' single scattering; fog = the fog volume march; ")
                .append("haze blur = the blur of the shadowed haze and the fog; haze = drawing it over the frame after the ")
                .append("particles (twice with absorbing fog); GPU volumetric = all of these; GPU surfaces = snapshot copy, ")
                .append("light pass, shadow blur and composite. CPU = collection, culling, clusters, voxels and uploads. ")
                .append("Volumetric (shadowed), maps and fog count what the last frame drew.\n");
        TABLES.forEach((title, rows) -> {
            text.append("\n## ").append(title).append("\n\n")
                    .append("| config | volumetric (shadowed) | maps | fog | visibility | in-scatter | fog | haze blur | haze ")
                    .append("| GPU volumetric | GPU surfaces | CPU | frame | FPS |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            rows.forEach(row -> text.append(row).append('\n'));
        });
        var file = ctx.outDir().resolve("volumetric-bench-%dx%d.md".formatted(window.getWidth(), window.getHeight()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
            ctx.log("wrote " + file.toAbsolutePath());
        } catch (IOException e) {
            ctx.check("bench table written", false, file.toString(), e.getMessage());
        }
    }
}
