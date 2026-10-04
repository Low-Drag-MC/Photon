package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import it.unimi.dsi.fastutil.floats.FloatPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Photon FX driving dynamic lights end to end: a flickering Light object, an ember emitter whose
 * particles emit light (Light Emission module, also as spots), lit smoke (Lit by Dynamic Lights), and the
 * Java API (a flash and a light attached to an entity) — each toggled off in turn for comparison.
 */
@LDLRegisterClient(name = "dynamic_light_fx", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightFxScenario implements UIScenario {
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui", ctx -> {
                    ctx.mc().options.hideGui = true;
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    LightDemoScene.stopLights();
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
                .frames(20)
                .screenshot("fx_0_scene")
                .step("start the campfire FX", DynamicLightFxScenario::startFx)
                .ticks(60)
                .screenshot("fx_1_full")
                .step("log (full)", ctx -> log(ctx, "full"))
                .step("smoke not lit", ctx -> ctx.<TextureMaterial>get("smoke").getLitParticles().setEnable(false))
                .ticks(2)
                .screenshot("fx_2_unlit_smoke")
                .step("embers stop emitting light", ctx -> {
                    ctx.<TextureMaterial>get("smoke").getLitParticles().setEnable(true);
                    ctx.<ParticleEmitter>get("embers").config.lightEmission.setEnable(false);
                })
                .ticks(2)
                .screenshot("fx_3_no_ember_lights")
                .step("log (no ember lights)", ctx -> log(ctx, "no ember lights"))
                .step("dynamic lights off", ctx -> {
                    ctx.<ParticleEmitter>get("embers").config.lightEmission.setEnable(true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, false);
                })
                .ticks(2)
                .screenshot("fx_4_lights_off")
                .step("ember lights become spots aimed down", ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    var spot = ctx.<ParticleEmitter>get("embers").config.lightEmission.getSpot();
                    spot.setEnable(true);
                    spot.setAlignToVelocity(false);
                })
                .ticks(2)
                .screenshot("fx_5_spots_down")
                .step("they point down", ctx -> spotDirections(ctx, "down", y -> y < -0.99f))
                .step("aim them along the embers' velocity", ctx ->
                        ctx.<ParticleEmitter>get("embers").config.lightEmission.getSpot().setAlignToVelocity(true))
                .ticks(2)
                .screenshot("fx_6_spots_velocity")
                .step("they follow the rising embers", ctx -> {
                    spotDirections(ctx, "up", y -> y > 0);
                    ctx.<ParticleEmitter>get("embers").config.lightEmission.getSpot().setEnable(false);
                })
                .step("API: flash + light attached to the player", ctx -> {
                    var o = ORIGIN.get();
                    PhotonLights.flash(new DynamicLight().at(o.getX() - 3.5, o.getY() + 2.5, o.getZ() + 13.5)
                            .color(0.4f, 0.6f, 1f).intensity(80).range(14), 40);
                    var player = ctx.requirePlayer();
                    ctx.put("attached", PhotonLights.attach(player, new DynamicLight().color(1f, 0.2f, 0.9f)
                            .intensity(25).range(10).shadows(false), new Vec3(0, 0.5, 4)));
                })
                .ticks(10)
                .screenshot("fx_7_api")
                .step("log (api)", ctx -> log(ctx, "api"))
                // only a weak reference here: the API alone has to keep an inline provider alive
                .step("API: a provider added inline", ctx -> {
                    var o = ORIGIN.get();
                    LightProvider provider = (sink, partialTick) -> sink.next()
                            .at(o.getX() + 4.5, o.getY() + 2, o.getZ() + 10.5).color(0.2f, 1f, 0.4f).intensity(10).range(6);
                    ctx.put("before", DynamicLightRenderer.lastLightCount());
                    ctx.put("provider", new WeakReference<>(provider));
                    PhotonLights.addProvider(provider);
                })
                .step("collect garbage", ctx -> System.gc())
                .frames(5)
                .step("it survived the GC and still submits", ctx -> {
                    WeakReference<LightProvider> reference = ctx.get("provider");
                    var provider = reference.get();
                    ctx.check("the provider was not collected", provider != null, "alive", "collected");
                    int before = ctx.get("before");
                    ctx.check("its light is drawn", DynamicLightRenderer.lastLightCount() == before + 1,
                            before + 1, DynamicLightRenderer.lastLightCount());
                    if (provider != null) PhotonLights.removeProvider(provider);
                })
                // the API has no way to cancel a flash; don't hand it to the next scenario
                .ticks(30);

        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
        s.teardown("stop", ctx -> {
            var executor = ctx.<BlockEffectExecutor>get("executor");
            if (executor != null && executor.getRuntime() != null) executor.getRuntime().destroy(true);
            PhotonLights.Handle handle = ctx.get("attached");
            if (handle != null) handle.close();
            ctx.mc().options.hideGui = false;
            LightTestConfig.restore();
        });
    }

    private static void startFx(TestContext ctx) {
        var fx = new FX();

        var light = new LightObject();
        light.config.setLooping(true);
        light.config.setLifetime(40);
        light.config.setColor(new HDRConstantColor(new HDRColor(1f, 0.5f, 0.18f, 1f, 1f)));
        light.config.setIntensity(NumberFunction.constant(9));
        light.config.setRange(NumberFunction.constant(12));
        light.config.setFlicker(0.35f);
        light.config.setSourceRadius(0.3f);
        light.transform().localPosition(new Vector3f(0, 1.0f, 0));
        fx.getFxData().objects().add(light);

        var embers = new ParticleEmitter();
        var config = embers.config;
        config.setLooping(true);
        config.setDuration(100);
        config.setStartLifetime(NumberFunction.constant(36));
        config.setStartSpeed(NumberFunction.constant(1.2f));
        config.setStartSize(new NumberFunction3(0.12, 0.12, 0.12));
        config.setStartColor(NumberFunction.color(0xFFFFA040));
        config.setMaxParticles(200);
        config.shape.setScale(new NumberFunction3(0.4, 0.1, 0.4));
        config.emission.setEmissionRate(NumberFunction.constant(1.5f));
        config.lightEmission.setEnable(true);
        config.lightEmission.setMaxLights(48);
        config.lightEmission.setIntensity(NumberFunction.constant(5));
        config.lightEmission.setRange(NumberFunction.constant(3.5f));
        // the config ships a shared builtin material; replace it rather than drawing every particle twice
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(new TextureMaterial()));
        fx.getFxData().objects().add(embers);

        var smoke = new ParticleEmitter();
        var smokeConfig = smoke.config;
        smokeConfig.setLooping(true);
        smokeConfig.setDuration(100);
        smokeConfig.setStartLifetime(NumberFunction.constant(70));
        smokeConfig.setStartSpeed(NumberFunction.constant(0.6f));
        smokeConfig.setStartSize(new NumberFunction3(1.2, 1.2, 1.2));
        smokeConfig.setStartColor(NumberFunction.color(0x80484848));
        smokeConfig.setMaxParticles(80);
        smokeConfig.shape.setScale(new NumberFunction3(0.5, 0.1, 0.5));
        smokeConfig.emission.setEmissionRate(NumberFunction.constant(0.4f));
        var smokeMaterial = new TextureMaterial();
        smokeMaterial.getLitParticles().setEnable(true);
        smokeConfig.renderer.getMaterials().clear();
        smokeConfig.renderer.getMaterials().add(new MaterialSetting(smokeMaterial));
        smoke.transform().localPosition(new Vector3f(0, 1.8f, 0));
        fx.getFxData().objects().add(smoke);

        var o = ORIGIN.get();
        var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), o.offset(1, 1, 8));
        executor.setOffset(0.5, 0, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        ctx.put("embers", executor.getRuntime().objects.values().stream()
                .filter(object -> object instanceof ParticleEmitter emitter && emitter.config.lightEmission.isEnable())
                .findFirst().orElseThrow());
        ctx.put("smoke", executor.getRuntime().objects.values().stream()
                .filter(object -> object instanceof ParticleEmitter emitter && !emitter.config.lightEmission.isEnable())
                .map(object -> (TextureMaterial) ((ParticleEmitter) object).config.renderer.getMaterials().getFirst().getMaterial())
                .findFirst().orElseThrow());
        ctx.check("the FX started", executor.getRuntime() != null, "a runtime", "null");
    }

    private static void spotDirections(TestContext ctx, String expected, FloatPredicate normalizedY) {
        var embers = ctx.<ParticleEmitter>get("embers");
        var lights = new ArrayList<DynamicLight>();
        embers.runtime().lightEmission.submit(embers.getParticles().values(), () -> {
            var light = new DynamicLight();
            lights.add(light);
            return light;
        }, 0);
        ctx.check("the embers submit lights", !lights.isEmpty(), "> 0", lights.size());
        long spots = lights.stream().filter(light -> light.type == DynamicLight.Type.SPOT).count();
        ctx.check("every ember light is a spot", spots == lights.size(), lights.size(), spots);
        long aimed = lights.stream().filter(light -> normalizedY.test(new Vector3f(light.direction).normalize().y)).count();
        ctx.check("every spot points " + expected, aimed == lights.size(), lights.size(), aimed);
    }

    private static void log(TestContext ctx, String label) {
        ctx.log("%s: %d lights, %d shadowed, GPU %.3f ms".formatted(label, DynamicLightRenderer.lastLightCount(),
                DynamicLightRenderer.lastShadowedCount(), DynamicLightRenderer.gpuMillis()));
    }
}
