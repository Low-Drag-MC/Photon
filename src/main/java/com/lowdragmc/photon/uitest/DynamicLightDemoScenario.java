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
import com.lowdragmc.photon.client.light.LightDebug;
import com.lowdragmc.photon.client.light.ShadowMode;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.ClientCommandHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the dynamic-light demo and photographs every view with voxel shadows, screen-space shadows,
 * no shadows, the shadow mask and the lights off; then the haze views with {@code /photonlight volumetric}
 * and {@code /photonlight fog} on; then a few views again under shader packs (needs the zips in
 * runs/client/shaderpacks). For eyeballing, apart from the two commands.
 */
@LDLRegisterClient(name = "dynamic_light_demo", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightDemoScenario implements UIScenario {
    private static final float CLOCK = 3.0f;
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final List<String[]> PACKS = List.of(
            new String[]{"complementary", "ComplementaryReimagined_r5.5.1.zip"},
            new String[]{"bsl", "BSL_v10.1.3.zip"});
    private static final List<String> PACK_VIEWS = List.of("overview", "courtyard", "house_floor");

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui and pin the light clock", ctx -> {
                    ctx.mc().options.hideGui = true;
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.VOXEL);
                    LightTestConfig.volumetricDefaults();
                    LightDebug.view = 0;
                    LightDemoScene.frozenSeconds = CLOCK;
                })
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    LightDemoScene.build(sc.level(), origin);
                })
                .step("start the lights", ctx -> LightDemoScene.startLights(ORIGIN.get()))
                .serverTicks(40);

        for (var view : LightDemoScene.VIEWS) {
            String name = view.name();
            goTo(s, name, 40)
                    .screenshot(name + "_1_voxel")
                    .step("log (" + name + ")", ctx -> log(ctx, name))
                    .step("screen-space shadows (" + name + ")", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.SCREEN))
                    .frames(3)
                    .screenshot(name + "_2_screen")
                    .step("no shadows (" + name + ")", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.OFF))
                    .frames(3)
                    .screenshot(name + "_3_noshadow")
                    .step("voxel shadow mask (" + name + ")", ctx -> {
                        LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.VOXEL);
                        LightDebug.view = 3;
                    })
                    .frames(3)
                    .screenshot(name + "_4_mask")
                    .step("lights off (" + name + ")", ctx -> {
                        LightDebug.view = 0;
                        LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, false);
                    })
                    .frames(3)
                    .screenshot(name + "_5_vanilla")
                    .step("lights back on (" + name + ")", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true));
        }

        goTo(s, "courtyard", 10)
                .step("hard voxel shadows", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.lightSoftShadows, false))
                .frames(3)
                .screenshot("courtyard_6_hard")
                .step("soft again, full resolution", ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightSoftShadows, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightResolution, (double) 1f);
                })
                .frames(3)
                .screenshot("courtyard_7_fullres")
                .step("clusters", ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightResolution, (double) 0.5f);
                    LightDebug.view = 5;
                })
                .frames(3)
                .screenshot("courtyard_8_clusters")
                .step("final image again", ctx -> LightDebug.view = 0);

        // the dev commands that put the demo in a haze, run as typed
        s.step("/photonlight volumetric 1", ctx -> command(ctx, "photonlight volumetric 1"))
                .step("/photonlight fog 1", ctx -> command(ctx, "photonlight fog 1"));
        for (var name : List.of("searchlight", "windows", "courtyard")) {
            goTo(s, name, 20).screenshot(name + "_9_volumetric");
        }
        s.step("the demo glows in the air and in its fog", ctx -> {
                    int lights = DynamicLightRenderer.lastLightCount();
                    ctx.check("every demo light is volumetric", lights > 0 && DynamicLightRenderer.lastVolumeCount() == lights,
                            lights, DynamicLightRenderer.lastVolumeCount());
                    ctx.check("the courtyard has its fog", DynamicLightRenderer.lastFogCount() == 1, 1, DynamicLightRenderer.lastFogCount());
                })
                .step("/photonlight volumetric 0", ctx -> command(ctx, "photonlight volumetric 0"))
                .step("/photonlight fog 0", ctx -> command(ctx, "photonlight fog 0"))
                .frames(3)
                .step("and both go away", ctx -> {
                    ctx.check("no volumetric lights", DynamicLightRenderer.lastVolumeCount() == 0, 0, DynamicLightRenderer.lastVolumeCount());
                    ctx.check("no fog", DynamicLightRenderer.lastFogCount() == 0, 0, DynamicLightRenderer.lastFogCount());
                });

        for (var pack : PACKS) {
            String tag = pack[0];
            String file = pack[1];
            s.step("load " + file, ctx -> ctx.check("Iris accepted " + file, LightTestConfig.selectShaderPack(file)))
                    .waitUntil(tag + " is active", ctx -> IrisCompat.isUsingShaderPack())
                    .frames(60);
            for (var name : PACK_VIEWS) {
                goTo(s, name, 90)
                        .screenshot(tag + "_" + name + "_1_voxel")
                        .step("log (" + tag + " " + name + ")", ctx -> log(ctx, tag + " " + name))
                        .step("lights off (" + tag + " " + name + ")", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, false))
                        .frames(90)
                        .screenshot(tag + "_" + name + "_5_off")
                        .step("lights back on (" + tag + " " + name + ")", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true));
            }
        }

        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("restore", ctx -> {
            ctx.mc().options.hideGui = false;
            LightTestConfig.restore();
            LightDemoScene.stopLights();
        });
    }

    /** A client command, as if typed: the harness's runCommand goes to the server, which doesn't know these. */
    private static void command(TestContext ctx, String command) {
        ctx.check("/" + command + " ran", ClientCommandHandler.runCommand(command));
    }

    private static ScenarioBuilder goTo(ScenarioBuilder s, String name, int settleFrames) {
        var view = LightDemoScene.view(name);
        return s.server("go to " + name, sc -> LightDemoScene.teleport(sc.player(), ORIGIN.get(), view))
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(settleFrames);
    }

    private static void log(TestContext ctx, String label) {
        ctx.log("%s: %d lights, %d shadowed, GPU %.3f ms, prepare %.3f ms, %d bricks".formatted(label,
                DynamicLightRenderer.lastLightCount(), DynamicLightRenderer.lastShadowedCount(),
                DynamicLightRenderer.gpuMillis(), DynamicLightRenderer.prepareMillis(), DynamicLightBenchScenario.bricks(ctx)));
    }
}
