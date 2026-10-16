package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.light.LightDebug;
import com.lowdragmc.photon.client.light.ShadowMode;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import net.minecraft.core.BlockPos;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Close-ups of artefacts found in play: soft-shadow noise on a lit floor, voxel shadows breaking into a grid under
 * a shader pack while the view bobs, and a TAAU pack, which draws its world into a corner of its depth. Captures,
 * plus checks on the TAAU pack's normals.
 */
@LDLRegisterClient(name = "dynamic_light_artifacts", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightArtifactsScenario implements UIScenario {
    private static final String PACK = "ComplementaryReimagined_r5.5.1.zip";
    private static final String TAAU_PACK = "Kappa_v5.4_T2.zip";
    private static final LightDemoScene.View CLOSE_UP = new LightDemoScene.View("close_up", -2.5, 1, 8.5, 0, 45);
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(300_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        softShadows(s);

        bob(s, "v_bob_none", 0f, 0f, 0);
        bob(s, "v_bob_down", 0.25f, 0f, 0);

        int[] waited = {0};
        s.step("load " + PACK, ctx -> LightTestConfig.selectShaderPack(PACK))
                .step("wait for the pack", ctx -> {
                    if (!IrisCompat.isUsingShaderPack() && ++waited[0] < 600) ctx.repeat("pack");
                })
                .frames(120);
        bob(s, "b_bob_none", 0f, 0f, 0);
        bob(s, "b_bob_down", 0.25f, 0f, 0);
        bob(s, "b_bob_side", 0.25f, 0.5f, 0);
        bob(s, "b_bob_none_mask", 0f, 0f, 3);
        bob(s, "b_bob_down_mask", 0.25f, 0f, 3);
        bob(s, "b_normals", 0f, 0f, 2);

        int[] taauWaited = {0};
        s.step("load " + TAAU_PACK, ctx -> {
                    if (LightTestConfig.hasShaderPack(TAAU_PACK)) {
                        LightTestConfig.selectShaderPack(TAAU_PACK);
                    } else {
                        ctx.log("no " + TAAU_PACK + " in shaderpacks/: the TAAU checks are skipped");
                    }
                })
                .step("wait for the TAAU pack", ctx -> {
                    boolean active = IrisCompat.isUsingShaderPack() && TAAU_PACK.equals(IrisCompat.packName());
                    if (!active && LightTestConfig.hasShaderPack(TAAU_PACK) && ++taauWaited[0] < 600) ctx.repeat("taau");
                })
                .frames(120);
        bob(s, "t_normals", 0f, 0f, 2);
        s.step("the TAAU pack's depth is read at its render scale", ctx -> {
            LightDebug.view = 0;
            if (!LightTestConfig.hasShaderPack(TAAU_PACK)) return;
            boolean active = IrisCompat.isUsingShaderPack() && TAAU_PACK.equals(IrisCompat.packName());
            // with the full-size pack still on, both captures would match and every check below pass
            ctx.check(TAAU_PACK + " is active", active, TAAU_PACK, IrisCompat.packName());
            if (!active) return;
            ctx.check("its render scale is read", Math.abs(IrisCompat.gbufferRenderScale() - 0.75f) < 1e-3, 0.75f,
                    IrisCompat.gbufferRenderScale());
            var full = ScreenshotCompare.load(ctx, "b_normals");
            var taau = ScreenshotCompare.load(ctx, "t_normals");
            if (full == null || taau == null) {
                ctx.check("both normal captures were written", false, "two images", "missing");
                return;
            }
            var region = taau.middle(0.8);
            // the same scene from the same spot: misread depth moves every edge
            var moved = taau.diff(full, 24, region);
            ctx.check("its normals line up with a full-size pack's", moved.fraction() < 0.05, "< 5%",
                    "%.1f%% %s".formatted(moved.fraction() * 100, moved.box()));
            // steps in the depth, from a nearest-texel upscale, stripe every plane
            double stripes = taau.rowChanges(6, region);
            ctx.check("and are smooth", stripes < 0.1, "< 10%", "%.1f%%".formatted(stripes * 100));
        });
        restore(s);
    }

    /** The vanilla half on its own: the soft-shadow close-ups. */
    @LDLRegisterClient(name = "dynamic_light_soft", group = "photon", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Soft implements UIScenario {
        @Override
        public void define(ScenarioBuilder s) {
            softShadows(s);
            restore(s);
        }
    }

    static void softShadows(ScenarioBuilder s) {
        s.step("hide the gui, pin the lights", ctx -> {
                    ctx.mc().options.hideGui = true;
                    LightTestConfig.set(ctx.mc().options.bobView(), true);
                    LightDemoScene.frozenSeconds = 0f;
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.VOXEL);
                })
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    LightDemoScene.build(sc.level(), origin);
                })
                .step("start the lights", ctx -> LightDemoScene.startLights(ORIGIN.get()))
                .serverTicks(40)
                .server("go close to the statue", sc -> LightDemoScene.teleport(sc.player(), ORIGIN.get(), CLOSE_UP))
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(30);

        capture(s, "a_soft_off", 0.5f, false, 0);
        capture(s, "a_soft_on", 0.5f, true, 0);
        capture(s, "a_light_soft_off", 0.5f, false, 1);
        capture(s, "a_light_soft_on", 0.5f, true, 1);
        capture(s, "a_full_soft_on", 1f, true, 0);
        capture(s, "a_light_full_soft_on", 1f, true, 1);
        capture(s, "a_albedo", 0.5f, true, 4);
        s.step("lights off", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, false))
                .frames(10)
                .screenshot("a_lights_off")
                .step("lights on", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true));
    }

    static void restore(ScenarioBuilder s) {
        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("restore", ctx -> {
            ctx.mc().options.hideGui = false;
            LightTestConfig.restore();
            LightDemoScene.stopLights();
        });
    }

    private static void capture(ScenarioBuilder s, String name, float scale, boolean soft, int debug) {
        s.step(name, ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightResolution, (double) scale);
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightSoftShadows, soft);
                    LightDebug.view = debug;
                })
                .frames(10)
                .screenshot(name);
    }

    /** Holds the view-bob pose for a few frames, then captures; walkDist picks the phase (0 = straight down). */
    private static void bob(ScenarioBuilder s, String name, float bob, float walkDist, int debug) {
        int[] frames = {0};
        s.step(name, ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightResolution, (double) 0.5f);
                    LightTestConfig.set(PhotonConfig.INSTANCE.lightSoftShadows, true);
                    LightDebug.view = debug;
                    pose(ctx, bob, walkDist);
                    if (++frames[0] < 12) {
                        ctx.repeat(name);
                    } else {
                        frames[0] = 0;
                    }
                })
                .step("hold " + name, ctx -> pose(ctx, bob, walkDist))
                .screenshot(name);
    }

    private static void pose(TestContext ctx, float bob, float walkDist) {
        var player = ctx.requirePlayer();
        player.bob = player.oBob = bob;
        player.walkDist = player.walkDistO = walkDist;
    }
}
