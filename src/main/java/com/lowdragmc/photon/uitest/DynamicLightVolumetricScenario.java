package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.light.FogVolumeObject;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.LightDebug;
import com.lowdragmc.photon.client.light.LightPassTimer;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.ShadowMode;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Volumetric lights and fog volumes end to end: halos and beams only inside their range and cone and never through a
 * wall; shadows cutting the haze, the fog and lit particles; no dark pixels from inside a light; the FX side; the
 * passes' cost; and under a shader pack (if its zip is in shaderpacks/) drawn by default, gone with its setting.
 */
@LDLRegisterClient(name = "dynamic_light_volumetric", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightVolumetricScenario implements UIScenario {
    private static final String PACK = "ComplementaryReimagined_r5.5.1.zip";
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final LightDemoScene.View COURTYARD = new LightDemoScene.View("volumetric courtyard", 0.5, 2, 1.5, 0, 5);
    private static final LightDemoScene.View HOUSE = new LightDemoScene.View("volumetric house", 14.5, 1, 23.5, 180, 0);

    // offsets from the origin block, like LightDemoScene's
    private static final Vec3 POINT = new Vec3(-3.5, 3, 11.5);
    private static final Vec3 SPOT = new Vec3(4.5, 7, 13.5);
    private static final Vec3 SKY = new Vec3(0.5, 16, 34.5);
    private static final Vec3 INSIDE_HOUSE = new Vec3(14.5, 2.5, 12.5);
    private static final Vec3 BY_THE_WALL = new Vec3(11.5, 2.5, 19.5);
    private static final Vec3 AT_THE_CAMERA = new Vec3(0.5, 4, 4.5);
    private static final Vec3 BY_THE_STATUE = new Vec3(-1.5, 2.5, 12.5);
    private static final Vec3 UPSTAIRS = new Vec3(14.5, 7.5, 13.5);

    private static final DynamicLight POINT_LIGHT = new DynamicLight().color(1f, 0.75f, 0.45f).intensity(12).range(6).shadows(false);
    private static final DynamicLight SPOT_LIGHT = new DynamicLight().color(0.6f, 0.8f, 1f).intensity(60).range(10)
            .spot(9, 15).direction(0, -1, 0).shadows(false);
    private static final DynamicLight SKY_LIGHT = new DynamicLight().color(1f, 0.5f, 0.9f).intensity(20).range(8).shadows(false);
    private static final DynamicLight HOUSE_LIGHT = new DynamicLight().color(1f, 0.6f, 0.3f).intensity(15).range(3);
    private static final DynamicLight WALL_LIGHT = new DynamicLight().color(0.4f, 1f, 0.6f).intensity(12).range(4).shadows(false);
    private static final DynamicLight CAMERA_LIGHT = new DynamicLight().color(0.5f, 0.7f, 1f).intensity(8).range(14).shadows(false);
    private static final DynamicLight STATUE_LIGHT = new DynamicLight().color(1f, 0.85f, 0.6f).intensity(14).range(8).sourceRadius(0f);
    private static final DynamicLight UPSTAIRS_LIGHT = new DynamicLight().color(1f, 0.7f, 0.4f).intensity(30).range(10).sourceRadius(0f);
    private static final List<DynamicLight> LIGHTS = new ArrayList<>();
    private static final List<DynamicLight> SCATTERED = new ArrayList<>();
    private static final LightProvider PROVIDER = (sink, partialTick) -> {
        for (var light : LIGHTS) sink.next().set(light);
        for (var light : SCATTERED) sink.next().set(light);
    };

    /** The light pass's debug view that shows the haze alone. */
    private static final int VOLUME_VIEW = 6;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Vec3[] CAMERA = {Vec3.ZERO};
    private static final Consumer<RenderLevelStageEvent> CAMERA_CAPTURE = event -> {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        VIEW_PROJECTION.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        CAMERA[0] = event.getCamera().getPosition();
    };
    private static final Map<String, Integer> VOLUME_COUNTS = new HashMap<>();
    private static final Map<String, Integer> SHADOWED_COUNTS = new HashMap<>();
    private static final Map<String, Integer> MAP_COUNTS = new HashMap<>();
    private static final Map<String, Integer> FOG_COUNTS = new HashMap<>();
    private static final Map<String, ScreenshotCompare.Region> REGIONS = new HashMap<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(300_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("set up", ctx -> {
                    var config = PhotonConfig.INSTANCE;
                    var options = ctx.mc().options;
                    options.hideGui = true;
                    LightTestConfig.set(config.dynamicLights, true);
                    LightTestConfig.set(config.lightShadowMode, ShadowMode.VOXEL);
                    LightTestConfig.set(config.lightResolution, 0.5);
                    LightTestConfig.volumetricDefaults();
                    LightTestConfig.set(options.bobView(), false);
                    LightTestConfig.set(options.fovEffectScale(), 0.0);
                    LightTestConfig.set(options.fov(), 70);
                    // drifting clouds would show in the whole-frame comparisons
                    LightTestConfig.set(options.cloudStatus(), CloudStatus.OFF);
                    LightDemoScene.stopLights();
                    LIGHTS.clear();
                    SCATTERED.clear();
                    VOLUME_COUNTS.clear();
                    SHADOWED_COUNTS.clear();
                    MAP_COUNTS.clear();
                    FOG_COUNTS.clear();
                    REGIONS.clear();
                    PhotonLights.addProvider(PROVIDER);
                    NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, CAMERA_CAPTURE);
                })
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    LightDemoScene.build(sc.level(), origin);
                    // the torch's flames change every frame, which a pixel comparison can't tell from haze
                    sc.level().setBlockAndUpdate(origin.offset(7, 1, 8), Blocks.AIR.defaultBlockState());
                })
                .serverTicks(40)
                .step("place the lights", ctx -> {
                    place(POINT_LIGHT, POINT);
                    place(SPOT_LIGHT, SPOT);
                    place(SKY_LIGHT, SKY);
                    place(HOUSE_LIGHT, INSIDE_HOUSE);
                    place(WALL_LIGHT, BY_THE_WALL);
                    place(CAMERA_LIGHT, AT_THE_CAMERA);
                    place(STATUE_LIGHT, BY_THE_STATUE);
                    place(UPSTAIRS_LIGHT, UPSTAIRS);
                });

        go(s, COURTYARD);
        capture(s, "a0_baseline", ctx -> scene(POINT_LIGHT, SPOT_LIGHT, SKY_LIGHT));
        s.step("find the lights on screen", ctx -> {
            REGIONS.put("point", around(ctx, POINT_LIGHT, 0, 60));
            REGIONS.put("beam", around(ctx, SPOT_LIGHT, -3, 24));
            REGIONS.put("beside the beam", around(ctx, SPOT_LIGHT, 4, -3, 0, 24));
            REGIONS.put("sky", around(ctx, SKY_LIGHT, 0, 60));
        });
        capture(s, "a1_point", ctx -> haze(1f, POINT_LIGHT));
        capture(s, "a2_spot", ctx -> haze(1f, SPOT_LIGHT));
        capture(s, "a3_sky", ctx -> haze(1f, SKY_LIGHT));
        capture(s, "a4_isotropic", ctx -> {
            haze(1f, POINT_LIGHT);
            LightTestConfig.set(PhotonConfig.INSTANCE.volumetricForwardScattering, 0.0);
        });
        capture(s, "a5_switched_off", ctx -> {
            LightTestConfig.set(PhotonConfig.INSTANCE.volumetricForwardScattering, 0.3);
            haze(1f, POINT_LIGHT, SPOT_LIGHT, SKY_LIGHT);
            LightTestConfig.set(PhotonConfig.INSTANCE.volumetricLights, false);
        });
        capture(s, "a6_no_haze", ctx -> {
            LightTestConfig.set(PhotonConfig.INSTANCE.volumetricLights, true);
            LightTestConfig.set(PhotonConfig.INSTANCE.volumetricDensity, 0.0);
        });
        capture(s, "a7_all", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.volumetricDensity, 1.0));
        s.step("halos and beams stay where they belong", DynamicLightVolumetricScenario::checkCourtyard);

        go(s, HOUSE);
        capture(s, "b0_house", ctx -> scene(HOUSE_LIGHT, WALL_LIGHT));
        capture(s, "b1_house_lit", ctx -> haze(2f, HOUSE_LIGHT));
        capture(s, "b2_wall_lit", ctx -> haze(2f, HOUSE_LIGHT, WALL_LIGHT));
        s.step("a wall hides the haze behind it", ctx -> {
            var baseline = load(ctx, "b0_house");
            var hidden = load(ctx, "b1_house_lit");
            var shown = load(ctx, "b2_wall_lit");
            if (baseline == null || hidden == null || shown == null) return;
            var whole = baseline.whole();
            var behind = hidden.diff(baseline, 6, whole);
            ctx.check("nothing shows of the light behind the wall", behind.count() < 200, "< 200 px", behind.count() + " " + behind.box());
            ctx.check("it was still drawn", VOLUME_COUNTS.get("b1_house_lit") == 1, 1, VOLUME_COUNTS.get("b1_house_lit"));
            var front = shown.diff(baseline, 6, whole);
            ctx.check("the light in front of the wall shows", front.count() > 20_000, "> 20000 px", front.count() + " " + front.box());
        });

        // the volume debug view: the haze alone, so surface shadows can't pass for it
        capture(s, "e0_upstairs", ctx -> {
            scene(UPSTAIRS_LIGHT);
            LightDebug.view = VOLUME_VIEW;
        });
        capture(s, "e1_upstairs_leaking", ctx -> {
            UPSTAIRS_LIGHT.shadows(false);
            haze(2f, UPSTAIRS_LIGHT);
        });
        capture(s, "e2_upstairs_shadowed", ctx -> UPSTAIRS_LIGHT.shadows(true));
        s.step("find the window", ctx -> {
            REGIONS.put("window", around(ctx, UPSTAIRS_LIGHT, 0, 0, 4, 20));
            REGIONS.put("wall", around(ctx, UPSTAIRS_LIGHT, 0, -4, 3.6, 40));
        });
        s.step("a shadowed light shines out of the window only", ctx -> {
            var baseline = load(ctx, "e0_upstairs");
            var leaking = load(ctx, "e1_upstairs_leaking");
            var shadowed = load(ctx, "e2_upstairs_shadowed");
            LightDebug.view = 0;
            if (baseline == null || leaking == null || shadowed == null) return;
            ctx.check("its haze is shadowed", SHADOWED_COUNTS.get("e2_upstairs_shadowed") == 1, 1,
                    SHADOWED_COUNTS.get("e2_upstairs_shadowed"));
            var leak = leaking.diff(baseline, 6, REGIONS.get("wall"));
            ctx.check("unshadowed, the haze leaks through the wall", leak.fraction() > 0.5, "> 50%", "%.1f%%".formatted(leak.fraction() * 100));
            var wall = shadowed.diff(baseline, 6, REGIONS.get("wall"));
            ctx.check("shadowed, none in front of the wall", wall.fraction() < 0.05, "< 5%", "%.1f%%".formatted(wall.fraction() * 100));
            var beam = shadowed.diff(baseline, 6, REGIONS.get("window"));
            ctx.check("but a beam out of the window", beam.fraction() > 0.5, "> 50%", "%.1f%%".formatted(beam.fraction() * 100));
            int brighter = leaking.darkerThan(shadowed, 6, baseline.whole());
            ctx.check("shadows only take light away", brighter < 200, "< 200 px", brighter);
        });
        capture(s, "e3_upstairs_beauty", ctx -> {
        });
        litParticleBehindWall(s);

        go(s, COURTYARD);
        capture(s, "d1_statue_unshadowed", ctx -> {
            scene(STATUE_LIGHT.shadows(false));
            haze(1.5f, STATUE_LIGHT);
            LightDebug.view = VOLUME_VIEW;
        });
        capture(s, "d2_statue_shadowed", ctx -> STATUE_LIGHT.shadows(true));
        s.step("find the statue's shadow", ctx -> {
            REGIONS.put("behind the statue", around(ctx, STATUE_LIGHT, 4.5, 0, 0, 24));
            REGIONS.put("away from the statue", around(ctx, STATUE_LIGHT, -4, 0, 0, 24));
        });
        s.step("the statue casts a shadow into the haze", ctx -> {
            LightDebug.view = 0;
            var unshadowed = load(ctx, "d1_statue_unshadowed");
            var shadowed = load(ctx, "d2_statue_shadowed");
            if (unshadowed == null || shadowed == null) return;
            ctx.check("unshadowed: its haze isn't shadowed", SHADOWED_COUNTS.get("d1_statue_unshadowed") == 0, 0,
                    SHADOWED_COUNTS.get("d1_statue_unshadowed"));
            ctx.check("shadowed: it is", SHADOWED_COUNTS.get("d2_statue_shadowed") == 1, 1, SHADOWED_COUNTS.get("d2_statue_shadowed"));
            var behind = shadowed.diff(unshadowed, 4, REGIONS.get("behind the statue"));
            ctx.check("darker behind the statue", behind.fraction() > 0.3, "> 30%", "%.1f%%".formatted(behind.fraction() * 100));
            var away = shadowed.diff(unshadowed, 6, REGIONS.get("away from the statue"));
            ctx.check("the same away from it", away.fraction() < 0.05, "< 5%", "%.1f%%".formatted(away.fraction() * 100));
            int brighter = unshadowed.darkerThan(shadowed, 6, unshadowed.whole());
            ctx.check("shadows only take light away", brighter < 200, "< 200 px", brighter);
        });
        capture(s, "d3_statue_beauty", ctx -> {
        });
        capture(s, "c0_camera_light", ctx -> scene(CAMERA_LIGHT));
        capture(s, "c1_inside_the_light", ctx -> haze(2f, CAMERA_LIGHT));
        s.step("from inside a light the haze is everywhere and never darkens", ctx -> {
            var baseline = load(ctx, "c0_camera_light");
            var inside = load(ctx, "c1_inside_the_light");
            if (baseline == null || inside == null) return;
            int darker = inside.darkerThan(baseline, 2, baseline.whole());
            ctx.check("no pixel got darker", darker < 50, "< 50 px", darker);
            var changed = inside.diff(baseline, 4, baseline.middle(0.9));
            ctx.check("the whole view is hazier", changed.fraction() > 0.9, "> 90%", "%.1f%%".formatted(changed.fraction() * 100));
        });

        fx(s);
        particleBehind(s);
        fogVolumes(s);
        cost(s);

        int[] waited = {0};
        s.step("load " + PACK, ctx -> {
                    if (LightTestConfig.hasShaderPack(PACK)) {
                        ctx.check("Iris accepted " + PACK, LightTestConfig.selectShaderPack(PACK));
                    } else {
                        ctx.log("no " + PACK + " in shaderpacks/: the shader pack checks are skipped");
                    }
                })
                .step("wait for the pack", ctx -> {
                    if (LightTestConfig.hasShaderPack(PACK) && !IrisCompat.isUsingShaderPack() && ++waited[0] < 600) ctx.repeat("pack");
                })
                .frames(120);
        capture(s, "p0_pack", ctx -> scene(POINT_LIGHT));
        capture(s, "p1_pack_haze", ctx -> haze(1f, POINT_LIGHT));
        capture(s, "p2_pack_opted_out", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.volumetricWithShaderPacks, false));
        s.step("under a shader pack the haze shows unless switched off", ctx -> {
            var baseline = load(ctx, "p0_pack");
            var haze = load(ctx, "p1_pack_haze");
            var optedOut = load(ctx, "p2_pack_opted_out");
            if (!LightTestConfig.hasShaderPack(PACK) || baseline == null || haze == null || optedOut == null) return;
            ctx.check(PACK + " is active", IrisCompat.isUsingShaderPack());
            var region = REGIONS.get("point");
            ctx.check("drawn by default", VOLUME_COUNTS.get("p1_pack_haze") == 1, 1, VOLUME_COUNTS.get("p1_pack_haze"));
            var halo = haze.diff(baseline, 6, region);
            ctx.check("the halo shows over the pack", halo.fraction() > 0.5, "> 50%", "%.1f%%".formatted(halo.fraction() * 100));
            int darker = haze.darkerThan(baseline, 6, region);
            ctx.check("and only brightens", darker < 50, "< 50 px", darker);
            ctx.check("off with the setting", VOLUME_COUNTS.get("p2_pack_opted_out") == 0, 0, VOLUME_COUNTS.get("p2_pack_opted_out"));
            var unchanged = optedOut.diff(baseline, 6, region);
            ctx.check("so the image is back", unchanged.fraction() < 0.02, "< 2%", "%.1f%%".formatted(unchanged.fraction() * 100));
        });

        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("restore", ctx -> {
            var executor = ctx.<BlockEffectExecutor>get("executor");
            if (executor != null && executor.getRuntime() != null) executor.getRuntime().destroy(true);
            PhotonLights.removeProvider(PROVIDER);
            NeoForge.EVENT_BUS.unregister(CAMERA_CAPTURE);
            LIGHTS.clear();
            SCATTERED.clear();
            LightPassTimer.recording = false;
            ctx.mc().options.hideGui = false;
            LightTestConfig.restore();
        });
    }

    private static void checkCourtyard(TestContext ctx) {
        var baseline = load(ctx, "a0_baseline");
        var point = load(ctx, "a1_point");
        var spot = load(ctx, "a2_spot");
        var sky = load(ctx, "a3_sky");
        var isotropic = load(ctx, "a4_isotropic");
        var off = load(ctx, "a5_switched_off");
        var noHaze = load(ctx, "a6_no_haze");
        if (baseline == null || point == null || spot == null || sky == null || isotropic == null || off == null || noHaze == null) {
            return;
        }
        var whole = baseline.whole();

        ctx.check("one volumetric light", VOLUME_COUNTS.get("a1_point") == 1, 1, VOLUME_COUNTS.get("a1_point"));
        var halo = point.diff(baseline, 6, REGIONS.get("point"));
        ctx.check("a halo around the point light", halo.fraction() > 0.8, "> 80%", "%.1f%%".formatted(halo.fraction() * 100));
        int darker = point.darkerThan(baseline, 2, whole);
        ctx.check("the halo only brightens", darker < 50, "< 50 px", darker);

        var beam = spot.diff(baseline, 6, REGIONS.get("beam"));
        ctx.check("a beam inside the cone", beam.fraction() > 0.5, "> 50%", "%.1f%%".formatted(beam.fraction() * 100));
        var beside = spot.diff(baseline, 6, REGIONS.get("beside the beam"));
        ctx.check("nothing beside the cone", beside.fraction() < 0.02, "< 2%", "%.1f%%".formatted(beside.fraction() * 100));

        var skyHalo = sky.diff(baseline, 6, REGIONS.get("sky"));
        ctx.check("a halo against the sky", skyHalo.fraction() > 0.8, "> 80%", "%.1f%%".formatted(skyHalo.fraction() * 100));
        int height = whole.bottom();
        var ground = sky.diff(baseline, 6, new ScreenshotCompare.Region(0, height / 2, whole.right(), height));
        ctx.check("and nothing below its range", ground.count() < 100, "< 100 px", ground.count() + " " + ground.box());

        var scattering = isotropic.diff(point, 4, REGIONS.get("point"));
        ctx.check("forward scattering changes the halo", scattering.fraction() > 0.3, "> 30%",
                "%.1f%%".formatted(scattering.fraction() * 100));

        var switchedOff = off.diff(baseline, 2, whole);
        ctx.check("switched off: no volumetric lights", VOLUME_COUNTS.get("a5_switched_off") == 0, 0, VOLUME_COUNTS.get("a5_switched_off"));
        ctx.check("switched off: the image is the baseline", switchedOff.count() < 50, "< 50 px", switchedOff.count() + " " + switchedOff.box());
        var thin = noHaze.diff(baseline, 2, whole);
        ctx.check("no haze: no volumetric lights", VOLUME_COUNTS.get("a6_no_haze") == 0, 0, VOLUME_COUNTS.get("a6_no_haze"));
        ctx.check("no haze: the image is the baseline", thin.count() < 50, "< 50 px", thin.count() + " " + thin.box());
        ctx.check("all three together", VOLUME_COUNTS.get("a7_all") == 3, 3, VOLUME_COUNTS.get("a7_all"));
    }

    /** A Light object and an emitter whose particles emit light, both volumetric. */
    private static void fx(ScenarioBuilder s) {
        s.step("start a volumetric FX", ctx -> {
                    scene();
                    startFx(ctx);
                })
                .ticks(10);
        capture(s, "f0_fx", ctx -> {
        });
        s.step("the FX lights are volumetric", ctx -> {
            var emitter = ctx.<ParticleEmitter>get("emitter");
            int particles = emitter.getParticleAmount();
            int volumetric = VOLUME_COUNTS.get("f0_fx");
            ctx.check("the light and the particles are volumetric", volumetric >= 2 && volumetric <= 1 + particles,
                    "2.." + (1 + particles), volumetric);
            emitter.config.lightEmission.getVolumetric().setEnable(false);
            ctx.<LightObject>get("light").config.getVolumetric().setEnable(false);
        })
                .frames(4)
                .step("switched off on both", ctx -> ctx.check("no volumetric lights left",
                        DynamicLightRenderer.lastVolumeCount() == 0, 0, DynamicLightRenderer.lastVolumeCount()))
                .step("stop the FX", ctx -> {
                    var executor = ctx.<BlockEffectExecutor>get("executor");
                    if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
                })
                .frames(4);
    }

    /** A lit particle outside the house: the light inside lights it only while it casts no shadows. */
    private static void litParticleBehindWall(ScenarioBuilder s) {
        s.step("a lit particle outside the wall", ctx -> {
                    scene(UPSTAIRS_LIGHT.shadows(false));
                    var o = ORIGIN.get();
                    var fx = new FX();
                    var emitter = new ParticleEmitter();
                    var config = emitter.config;
                    config.setLooping(true);
                    config.setStartLifetime(NumberFunction.constant(100000));
                    config.setStartSpeed(NumberFunction.constant(0));
                    config.setStartSize(new NumberFunction3(1, 1, 1));
                    config.setStartColor(NumberFunction.color(0xFFC0C0C0));
                    config.setMaxParticles(1);
                    config.shape.setScale(new NumberFunction3(0.01, 0.01, 0.01));
                    config.emission.setEmissionRate(NumberFunction.constant(1f));
                    var material = new TextureMaterial();
                    material.getLitParticles().setEnable(true);
                    config.renderer.getMaterials().clear();
                    config.renderer.getMaterials().add(new MaterialSetting(material));
                    fx.getFxData().objects().add(emitter);
                    var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), o.offset(14, 3, 18));
                    executor.setOffset(0.5, 0.5, 0.5);
                    executor.setAllowMulti(false);
                    executor.start();
                    ctx.put("executor", executor);
                })
                .ticks(5);
        capture(s, "h0_lit_particle_unshadowed", ctx -> {
        });
        capture(s, "h1_lit_particle_shadowed", ctx -> UPSTAIRS_LIGHT.shadows(true));
        s.step("the wall shadows the lit particle", ctx -> {
            var executor = ctx.<BlockEffectExecutor>get("executor");
            var runtime = executor.getRuntime();
            var emitter = runtime == null ? null : (ParticleEmitter) runtime.objects.values().stream()
                    .filter(ParticleEmitter.class::isInstance).findFirst().orElse(null);
            var where = emitter == null ? null : emitter.getParticles().values().stream().flatMap(Collection::stream)
                    .filter(TileParticle.class::isInstance).map(particle -> ((TileParticle) particle).getWorldPos(0f))
                    .findFirst().orElse(null);
            if (runtime != null) runtime.destroy(true);
            ctx.check("the particle was found", where != null, "a particle", "none");
            var unshadowed = load(ctx, "h0_lit_particle_unshadowed");
            var shadowed = load(ctx, "h1_lit_particle_shadowed");
            if (unshadowed == null || shadowed == null || where == null) return;
            ctx.check("the light got a visibility map for lit particles", MAP_COUNTS.get("h1_lit_particle_shadowed") >= 1, ">= 1",
                    MAP_COUNTS.get("h1_lit_particle_shadowed"));
            var region = around(ctx, new DynamicLight().at(where.x, where.y, where.z), 0, 20);
            var lit = unshadowed.diff(shadowed, 6, region);
            ctx.check("lit through the wall without shadows, dark with them", lit.fraction() > 0.5, "> 50%",
                    "%.1f%%".formatted(lit.fraction() * 100));
            int brighter = unshadowed.darkerThan(shadowed, 6, region);
            ctx.check("the shadowed particle is never the brighter one", brighter == 0, 0, brighter);
        })
                .frames(4);
    }

    /** A fog volume over the courtyard, from an FX: lit, absorbing, glowing, shadowed, noisy and switched off. */
    private static void fogVolumes(ScenarioBuilder s) {
        capture(s, "k0_no_fog", ctx -> scene(POINT_LIGHT, SPOT_LIGHT, STATUE_LIGHT.shadows(false)));
        s.step("find the fog on screen", ctx -> {
                    var o = ORIGIN.get();
                    REGIONS.put("in the fog", around(ctx, new DynamicLight().at(o.getX() + 0.5, o.getY() + 4, o.getZ() + 12.5), 0, 40));
                    REGIONS.put("sky over the fog", around(ctx, new DynamicLight().at(o.getX() + 0.5, o.getY() + 14, o.getZ() + 30), 0, 40));
                    startFog(ctx);
                })
                .ticks(3);
        capture(s, "k1_fog", ctx -> {
        });
        capture(s, "k2_fog_absorbing", ctx -> {
            var fog = ctx.<FogVolumeObject>get("fog").config;
            fog.setColor(new HDRConstantColor(new HDRColor(0f, 0f, 0f, 1f, 1f)));
            fog.setAbsorption(1f);
            fog.setDensity(NumberFunction.constant(3f));
        });
        capture(s, "k3_fog_glow", ctx -> {
            scene();
            var fog = ctx.<FogVolumeObject>get("fog").config;
            fog.setAbsorption(0f);
            fog.setDensity(NumberFunction.constant(1.5f));
            fog.setEmission(new HDRConstantColor(new HDRColor(0.4f, 0.8f, 1f, 1f, 1f)));
        });
        capture(s, "k4_fog_unshadowed", ctx -> {
            scene(STATUE_LIGHT.shadows(false));
            var fog = ctx.<FogVolumeObject>get("fog").config;
            fog.setColor(new HDRConstantColor(new HDRColor(1f, 1f, 1f, 1f, 1f)));
            fog.setEmission(new HDRConstantColor(new HDRColor(0f, 0f, 0f, 1f, 1f)));
            LightDebug.view = VOLUME_VIEW;
        });
        capture(s, "k5_fog_shadowed", ctx -> STATUE_LIGHT.shadows(true));
        capture(s, "k6_fog_noise", ctx -> {
            LightDebug.view = 0;
            scene(POINT_LIGHT, SPOT_LIGHT, STATUE_LIGHT.shadows(false));
            // k1 with noise and nothing else changed
            var fog = ctx.<FogVolumeObject>get("fog").config;
            fog.setAbsorption(0.5f);
            fog.getNoise().setEnable(true);
        });
        capture(s, "k7_fog_switched_off", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.volumetricLights, false));
        s.step("fog volumes scatter, hide, glow, shadow and switch off", ctx -> {
            LightTestConfig.set(PhotonConfig.INSTANCE.volumetricLights, true);
            var executor = ctx.<BlockEffectExecutor>get("executor");
            if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
            var none = load(ctx, "k0_no_fog");
            var lit = load(ctx, "k1_fog");
            var absorbing = load(ctx, "k2_fog_absorbing");
            var glow = load(ctx, "k3_fog_glow");
            var unshadowed = load(ctx, "k4_fog_unshadowed");
            var shadowed = load(ctx, "k5_fog_shadowed");
            var noisy = load(ctx, "k6_fog_noise");
            var off = load(ctx, "k7_fog_switched_off");
            if (none == null || lit == null || absorbing == null || glow == null || unshadowed == null || shadowed == null
                    || noisy == null || off == null) {
                return;
            }
            var whole = none.whole();
            ctx.check("one fog volume", FOG_COUNTS.get("k1_fog") == 1, 1, FOG_COUNTS.get("k1_fog"));
            var scattered = lit.diff(none, 6, REGIONS.get("point"));
            ctx.check("the lights show in the fog", scattered.fraction() > 0.5, "> 50%", "%.1f%%".formatted(scattered.fraction() * 100));
            var sky = lit.diff(none, 6, REGIONS.get("sky over the fog"));
            ctx.check("nothing over the fog", sky.fraction() < 0.01, "< 1%", "%.1f%%".formatted(sky.fraction() * 100));
            int hidden = absorbing.darkerThan(none, 6, REGIONS.get("in the fog"));
            ctx.check("an absorbing fog hides what is behind it", hidden > 1000, "> 1000 px", hidden);
            int brighter = none.darkerThan(absorbing, 6, whole);
            ctx.check("and black fog adds no light", brighter < 200, "< 200 px", brighter);
            ctx.check("glowing fog with no light is still drawn", FOG_COUNTS.get("k3_fog_glow") == 1, 1, FOG_COUNTS.get("k3_fog_glow"));
            var glowing = glow.diff(none, 6, REGIONS.get("in the fog"));
            ctx.check("and glows", glowing.fraction() > 0.8, "> 80%", "%.1f%%".formatted(glowing.fraction() * 100));
            var behind = shadowed.diff(unshadowed, 4, REGIONS.get("behind the statue"));
            ctx.check("the statue shadows the fog", behind.fraction() > 0.3, "> 30%", "%.1f%%".formatted(behind.fraction() * 100));
            int lighter = unshadowed.darkerThan(shadowed, 6, whole);
            ctx.check("and the shadow only takes light away", lighter < 200, "< 200 px", lighter);
            var wisps = noisy.diff(lit, 4, REGIONS.get("point"));
            ctx.check("noise breaks the fog up", wisps.fraction() > 0.2, "> 20%", "%.1f%%".formatted(wisps.fraction() * 100));
            ctx.check("switched off: no fog", FOG_COUNTS.get("k7_fog_switched_off") == 0, 0, FOG_COUNTS.get("k7_fog_switched_off"));
            var gone = off.diff(none, 2, whole);
            ctx.check("switched off: the image is the baseline", gone.count() < 50, "< 50 px", gone.count() + " " + gone.box());
        })
                .frames(4);
    }

    private static void startFog(TestContext ctx) {
        var fx = new FX();
        var fog = new FogVolumeObject();
        fog.config.setDensity(NumberFunction.constant(1.5f));
        fog.config.getNoise().setEnable(false);
        fog.transform().localPosition(new Vector3f(0, 2.5f, 0));
        fog.transform().localScale(new Vector3f(12, 5, 10));
        fx.getFxData().objects().add(fog);
        var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), ORIGIN.get().offset(-1, 0, 12));
        executor.setOffset(0.5, 0, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        ctx.put("fog", executor.getRuntime().objects.values().stream().filter(FogVolumeObject.class::isInstance)
                .findFirst().orElseThrow());
    }

    /** The haze goes on after the particles: a dark particle right behind a light keeps its halo in front. */
    private static void particleBehind(ScenarioBuilder s) {
        s.step("a dark particle behind the light", ctx -> {
                    scene(POINT_LIGHT);
                    var camera = CAMERA[0];
                    var p = POINT_LIGHT.position;
                    var behind = new Vec3(p.x, p.y, p.z).add(new Vec3(p.x, p.y, p.z).subtract(camera).normalize().scale(2));
                    var fx = new FX();
                    var emitter = new ParticleEmitter();
                    var config = emitter.config;
                    config.setLooping(true);
                    config.setStartLifetime(NumberFunction.constant(100000));
                    config.setStartSpeed(NumberFunction.constant(0));
                    config.setStartSize(new NumberFunction3(3, 3, 3));
                    config.setStartColor(NumberFunction.color(0xFF181818));
                    config.setMaxParticles(1);
                    config.shape.setScale(new NumberFunction3(0.01, 0.01, 0.01));
                    config.emission.setEmissionRate(NumberFunction.constant(1f));
                    config.renderer.getMaterials().clear();
                    config.renderer.getMaterials().add(new MaterialSetting(new TextureMaterial()));
                    fx.getFxData().objects().add(emitter);
                    var block = BlockPos.containing(behind);
                    var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), block);
                    executor.setOffset(behind.x - block.getX(), behind.y - block.getY(), behind.z - block.getZ());
                    executor.setAllowMulti(false);
                    executor.start();
                    ctx.put("executor", executor);
                })
                .ticks(5);
        capture(s, "g0_particle", ctx -> {
        });
        capture(s, "g1_particle_haze", ctx -> haze(1f, POINT_LIGHT));
        s.step("the halo stays in front of the particle", ctx -> {
            var executor = ctx.<BlockEffectExecutor>get("executor");
            if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
            var without = load(ctx, "g0_particle");
            var with = load(ctx, "g1_particle_haze");
            if (without == null || with == null) return;
            var halo = with.diff(without, 6, REGIONS.get("point"));
            ctx.check("the halo shows over the particle", halo.fraction() > 0.5, "> 50%", "%.1f%%".formatted(halo.fraction() * 100));
        })
                .frames(4);
    }

    private static void startFx(TestContext ctx) {
        var fx = new FX();
        var light = new LightObject();
        light.config.setLooping(true);
        light.config.setColor(new HDRConstantColor(new HDRColor(1f, 0.45f, 0.2f, 1f, 1f)));
        light.config.setIntensity(NumberFunction.constant(10));
        light.config.setRange(NumberFunction.constant(5));
        light.config.getVolumetric().setEnable(true);
        light.config.getVolumetric().setStrength(NumberFunction.constant(1.5f));
        light.transform().localPosition(new Vector3f(0, 1.5f, 0));
        fx.getFxData().objects().add(light);

        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(100);
        config.setStartLifetime(NumberFunction.constant(30));
        config.setStartSpeed(NumberFunction.constant(1f));
        config.setStartSize(new NumberFunction3(0.1, 0.1, 0.1));
        config.setStartColor(NumberFunction.color(0xFF60C0FF));
        config.setMaxParticles(40);
        config.emission.setEmissionRate(NumberFunction.constant(1f));
        config.lightEmission.setEnable(true);
        config.lightEmission.setIntensity(NumberFunction.constant(4));
        config.lightEmission.setRange(NumberFunction.constant(2.5f));
        config.lightEmission.getVolumetric().setEnable(true);
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(new TextureMaterial()));
        fx.getFxData().objects().add(emitter);

        var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), ORIGIN.get().offset(-2, 1, 8));
        executor.setOffset(0.5, 0, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        var objects = executor.getRuntime().objects.values();
        ctx.put("light", objects.stream().filter(LightObject.class::isInstance).findFirst().orElseThrow());
        ctx.put("emitter", objects.stream().filter(ParticleEmitter.class::isInstance).findFirst().orElseThrow());
    }

    /**
     * The volume and fog passes' GPU time, without the visibility maps (built earlier, with the light list) or the
     * late haze composite: skipped without volumetric lights, then with many of them.
     */
    private static void cost(ScenarioBuilder s) {
        var results = new double[5];
        int[] counts = {0, 64, 256, 64, 0};
        int[] shadowedCounts = {0, 0, 0, 8, 0};
        boolean[] fogs = {false, false, false, false, true};
        for (int i = 0; i < counts.length; i++) {
            int index = i;
            int count = counts[i];
            int shadowed = shadowedCounts[i];
            boolean fog = fogs[i];
            int[] frame = {0};
            s.step("scatter " + count + " volumetric lights, " + shadowed + " shadowed" + (fog ? ", a fog volume" : ""), ctx -> {
                        scene(POINT_LIGHT, SPOT_LIGHT, SKY_LIGHT, STATUE_LIGHT.shadows(true));
                        scatter(count, shadowed);
                        if (fog) startFog(ctx);
                    })
                    .frames(20)
                    .step("time " + count + " volumetric lights", ctx -> {
                        if (frame[0] == 0) {
                            LightPassTimer.clearSamples();
                            LightPassTimer.recording = true;
                        }
                        if (++frame[0] <= 120) {
                            ctx.repeat("frame " + frame[0]);
                            return;
                        }
                        LightPassTimer.recording = false;
                        var samples = LightPassTimer.STAGE_MS[LightPassTimer.Stage.VOLUME.ordinal()].toDoubleArray();
                        ctx.check("timed " + count + " volumetric lights", samples.length > 0, "> 0 samples", samples.length);
                        Arrays.sort(samples);
                        results[index] = samples.length == 0 ? 0 : samples[samples.length / 2];
                        ctx.log("%d volumetric lights, %d shadowed, %d fog: volume pass %.3f ms median, %d drawn, %d shadowed".formatted(
                                count, shadowed, DynamicLightRenderer.lastFogCount(), results[index],
                                DynamicLightRenderer.lastVolumeCount(), DynamicLightRenderer.lastVolumeShadowedCount()));
                        if (fog) {
                            var executor = ctx.<BlockEffectExecutor>get("executor");
                            if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
                        }
                    });
        }
        s.step("the pass costs nothing without volumetric lights, little with many", ctx -> {
            ctx.check("skipped without volumetric lights", results[0] < 0.02, "< 0.02 ms", "%.3f ms".formatted(results[0]));
            ctx.check("256 volumetric lights at 1080p", results[2] < 3.0, "< 3 ms", "%.3f ms".formatted(results[2]));
            ctx.check("64 volumetric lights, 8 of them shadowed", results[3] < 3.0, "< 3 ms", "%.3f ms".formatted(results[3]));
            ctx.check("a fog volume over the courtyard, four lights", results[4] < 2.0, "< 2 ms", "%.3f ms".formatted(results[4]));
            SCATTERED.clear();
        });
    }

    /** The first {@code shadowed} cast shadows. */
    private static void scatter(int count, int shadowed) {
        SCATTERED.clear();
        var random = new Random(7);
        var o = ORIGIN.get();
        for (int i = 0; i < count; i++) {
            SCATTERED.add(new DynamicLight().color(random.nextFloat(), random.nextFloat(), random.nextFloat())
                    .intensity(6).range(3 + random.nextFloat() * 3).shadows(i < shadowed).volumetric(1f)
                    .at(o.getX() + 0.5 + (random.nextDouble() * 2 - 1) * 6, o.getY() + 1.5 + random.nextDouble() * 3,
                            o.getZ() + 12.5 + (random.nextDouble() * 2 - 1) * 6));
        }
    }

    private static void go(ScenarioBuilder s, LightDemoScene.View view) {
        s.server("go to " + view.name(), sc -> LightDemoScene.teleport(sc.player(), ORIGIN.get(), view))
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(20);
    }

    private static void capture(ScenarioBuilder s, String name, Consumer<TestContext> setUp) {
        s.step(name, setUp::accept)
                .frames(8)
                .step("count " + name, ctx -> {
                    VOLUME_COUNTS.put(name, DynamicLightRenderer.lastVolumeCount());
                    SHADOWED_COUNTS.put(name, DynamicLightRenderer.lastVolumeShadowedCount());
                    MAP_COUNTS.put(name, DynamicLightRenderer.lastVisibilityMapCount());
                    FOG_COUNTS.put(name, DynamicLightRenderer.lastFogCount());
                })
                .screenshot(name);
    }

    /** Exactly these lights, none of them volumetric yet. */
    private static void scene(DynamicLight... lights) {
        LIGHTS.clear();
        for (var light : lights) {
            LIGHTS.add(light.volumetric(0f));
        }
    }

    /** Only these of the scene's lights in the haze, at this strength. */
    private static void haze(float strength, DynamicLight... lights) {
        for (var light : LIGHTS) light.volumetric(0f);
        for (var light : lights) light.volumetric(strength);
    }

    private static void place(DynamicLight light, Vec3 offset) {
        var o = ORIGIN.get();
        light.at(o.getX() + offset.x, o.getY() + offset.y, o.getZ() + offset.z);
    }

    private static ScreenshotCompare.Region around(TestContext ctx, DynamicLight light, double dy, int radius) {
        return around(ctx, light, 0, dy, 0, radius);
    }

    /**
     * A square of {@code radius} pixels around where the light, moved by the offset in blocks, lands on screen. Checked
     * to be wholly on screen: a comparison over a clipped region would pass on nothing.
     */
    private static ScreenshotCompare.Region around(TestContext ctx, DynamicLight light, double dx, double dy, double dz, int radius) {
        var camera = CAMERA[0];
        var p = light.position;
        var clip = VIEW_PROJECTION.transform(new Vector4f((float) (p.x + dx - camera.x), (float) (p.y + dy - camera.y),
                (float) (p.z + dz - camera.z), 1f));
        var window = ctx.mc().getWindow();
        int x = Math.round((clip.x / clip.w * 0.5f + 0.5f) * window.getWidth());
        int y = Math.round((0.5f - clip.y / clip.w * 0.5f) * window.getHeight());
        boolean onScreen = clip.w > 0 && x - radius >= 0 && y - radius >= 0 && x + radius <= window.getWidth()
                && y + radius <= window.getHeight();
        ctx.check("the region at %.1f %.1f %.1f is on screen".formatted(p.x + dx, p.y + dy, p.z + dz), onScreen, "on screen",
                "x %d y %d w %.2f".formatted(x, y, clip.w));
        return new ScreenshotCompare.Region(x - radius, y - radius, x + radius, y + radius);
    }

    private static ScreenshotCompare load(TestContext ctx, String name) {
        var image = ScreenshotCompare.load(ctx, name);
        ctx.check("capture " + name + " was written", image != null, "an image", "missing");
        return image;
    }
}
