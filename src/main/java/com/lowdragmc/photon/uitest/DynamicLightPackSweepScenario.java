package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Every pack in {@code shaderpacks/}, one after another: the courtyard with the lights on, off, and volumetric
 * with the fog box, plus whether the pack loaded and whether the after-pack passes drew. Writes {@code packs.md}.
 */
@LDLRegisterClient(name = "dynamic_light_packs", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightPackSweepScenario implements UIScenario {
    private static final int LOAD_WAIT_FRAMES = 600;
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final List<String> ROWS = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(900_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui, pin the clock", ctx -> {
                    ctx.mc().options.hideGui = true;
                    LightTestConfig.volumetricDefaults();
                    LightDemoScene.frozenSeconds = 3f;
                    ROWS.clear();
                })
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    LightDemoScene.build(sc.level(), origin);
                })
                .step("start the lights", ctx -> LightDemoScene.startLights(ORIGIN.get()))
                .serverTicks(40)
                .server("go to the courtyard", sc -> LightDemoScene.teleport(sc.player(), ORIGIN.get(),
                        LightDemoScene.view("courtyard")))
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(30);

        for (var pack : packs()) {
            String tag = pack.replaceAll("[^A-Za-z0-9.]+", "_");
            boolean[] active = {false};
            int[] waited = {0};
            String[] lit = {""};
            s.step("load " + pack, ctx -> {
                        waited[0] = 0;
                        active[0] = false;
                        LightTestConfig.selectShaderPack(pack);
                    })
                    .step("wait for " + pack, ctx -> {
                        // a pack that fails to compile leaves Iris without one; record it and move on
                        if (IrisCompat.isUsingShaderPack()) {
                            active[0] = true;
                        } else if (++waited[0] < LOAD_WAIT_FRAMES) {
                            ctx.repeat(pack);
                        }
                    })
                    .frames(120)
                    .screenshot(tag + "_1_lit")
                    .step("lights off (" + pack + ")", ctx -> {
                        lit[0] = "| %s | %s | %d | %s |".formatted(pack, active[0] ? "yes" : "**no**",
                                DynamicLightRenderer.lastLightCount(), gpu());
                        LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, false);
                    })
                    .frames(60)
                    .screenshot(tag + "_2_off")
                    .step("lights on, haze and fog on (" + pack + ")", ctx -> {
                        LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                        LightDemoScene.volumetric = 1f;
                        LightDemoScene.fogDensity = 1f;
                    })
                    // as many frames as the lit capture, so the GPU timer settles
                    .frames(120)
                    .screenshot(tag + "_3_haze")
                    .step("haze and fog off (" + pack + ")", ctx -> {
                        ROWS.add(lit[0] + " %d | %d | %s | %s |".formatted(DynamicLightRenderer.lastVolumeCount(),
                                DynamicLightRenderer.lastFogCount(), gpu(), IrisCompat.gbufferRenderScale()));
                        LightDemoScene.volumetric = 0f;
                        LightDemoScene.fogDensity = 0f;
                    });
        }

        s.step("write the table", DynamicLightPackSweepScenario::writeTable);
        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("restore", ctx -> {
            ctx.mc().options.hideGui = false;
            LightTestConfig.restore();
            LightDemoScene.stopLights();
        });
    }

    private static String gpu() {
        return DynamicLightRenderer.gpuMillis() < 0 ? "-" : "%.2f".formatted(DynamicLightRenderer.gpuMillis());
    }

    private static List<String> packs() {
        var dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void writeTable(TestContext ctx) {
        var text = new StringBuilder("# After-pack light pass across shader packs\n\n")
                .append("Courtyard view, 8 demo lights, shadows on. \"Lights drawn\" > 0 means the pass ran over the pack's image; ")
                .append("then the same lights volumetric, with the courtyard fog box.\n\n")
                .append("| pack | loaded | lights drawn | GPU ms | volumetric | fog | GPU ms with haze | render scale |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        ROWS.forEach(row -> text.append(row).append('\n'));
        var file = ctx.outDir().resolve("packs.md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
        } catch (IOException e) {
            ctx.check("pack table written", false, file.toString(), e.getMessage());
        }
    }
}
