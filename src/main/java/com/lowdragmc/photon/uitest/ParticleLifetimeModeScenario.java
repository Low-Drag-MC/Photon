package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.ParticleLifetime;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Particles that do not die of age. An infinite one (lifetime 0) goes when its effect is stopped. A looping one starts
 * its T over every lifetime and a ping-pong one runs it back and forth, neither dying of age; when their emitter is
 * stopped (the effect, or a draining clip) they play out the cycle they are in and die, while an emitter finishing
 * its own run is not a stop.
 */
@LDLRegisterClient(name = "particle_lifetime_mode", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ParticleLifetimeModeScenario implements UIScenario {
    private static final UUID E = id("e");

    @Override
    public void define(ScenarioBuilder s) {
        s.step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .step("the mode is saved with the emitter", ctx -> {
                    var config = new ParticleConfig();
                    config.setLifetimeMode(ParticleLifetime.Mode.PingPong);
                    var read = new ParticleConfig();
                    read.deserializeNBT(Platform.getFrozenRegistry(), config.serializeNBT(Platform.getFrozenRegistry()));
                    ctx.check("ping-pong read back", read.getLifetimeMode() == ParticleLifetime.Mode.PingPong,
                            ParticleLifetime.Mode.PingPong, read.getLifetimeMode());
                    ctx.check("a new emitter plays once", new ParticleConfig().getLifetimeMode() == ParticleLifetime.Mode.Once,
                            ParticleLifetime.Mode.Once, new ParticleConfig().getLifetimeMode());
                })

                .step("play four infinite particles", ctx -> play(ctx, fx(emitter(0, true, ParticleLifetime.Mode.Once))))
                .timeoutMs(20_000)
                .waitUntil("all four are out", ctx -> living(ctx).size() == 4)
                .step("stop the effect, letting the remnants drain", ParticleLifetimeModeScenario::stopGently)
                .timeoutMs(20_000)
                .waitUntil("40 ticks pass", ctx -> since(ctx) >= 40)
                .step("infinite particles went with their effect", ctx -> {
                    ctx.check("no particle is left", living(ctx).isEmpty(), 0, living(ctx).size());
                    ctx.check("the effect finished", runtime(ctx).isFinished(), "finished", "running");
                })

                .step("play four looping particles, 20 ticks a cycle", ctx -> play(ctx, fx(emitter(20, true, ParticleLifetime.Mode.Loop))))
                .waitUntil("all four are out", ctx -> living(ctx).size() == 4)
                .step("follow the oldest", ctx -> ctx.put("oldest", living(ctx).getFirst()))
                .timeoutMs(20_000)
                .waitUntil("the oldest has lived three cycles", ctx -> oldest(ctx).getAge() >= 60)
                .step("none died of age, and the oldest's T started over each cycle", ctx -> {
                    ctx.check("all four live", living(ctx).size() == 4, 4, living(ctx).size());
                    var oldest = oldest(ctx);
                    var expected = (oldest.getAge() % 20) / 20f;
                    ctx.check("T is where the cycle is", Math.abs(oldest.getT() - expected) < 0.06f, expected, oldest.getT());
                })
                .waitUntil("the oldest is early in a cycle", ctx -> oldest(ctx).getT() >= 0.1f && oldest(ctx).getT() <= 0.3f)
                .step("stop the effect", ctx -> {
                    ctx.put("tAtStop", oldest(ctx).getT());
                    stopGently(ctx);
                })
                .waitUntil("10 ticks pass", ctx -> since(ctx) >= 10)
                .step("they play on through the cycle they are in", ctx -> {
                    ctx.check("none was cut", living(ctx).size() == 4, 4, living(ctx).size());
                    float before = ctx.get("tAtStop");
                    ctx.check("the oldest's T went on up, not over", oldest(ctx).getT() > before + 0.3f,
                            "> " + (before + 0.3f), oldest(ctx).getT());
                })
                .timeoutMs(20_000)
                .waitUntil("25 ticks pass", ctx -> since(ctx) >= 25)
                .step("and die at its end", ctx -> {
                    ctx.check("no particle is left", living(ctx).isEmpty(), 0, living(ctx).size());
                    ctx.check("the effect finished", runtime(ctx).isFinished(), "finished", "running");
                })

                .step("play four ping-pong particles, 20 ticks each way", ctx -> play(ctx, fx(emitter(20, true, ParticleLifetime.Mode.PingPong))))
                .waitUntil("all four are out", ctx -> living(ctx).size() == 4)
                .step("follow the oldest", ctx -> {
                    ctx.put("oldest", living(ctx).getFirst());
                    ctx.put("samples", new ArrayList<Float>());
                })
                .waitUntil("the oldest is 33 ticks old, sampled every frame", ctx -> {
                    ctx.<List<Float>>get("samples").add(oldest(ctx).getT());
                    return oldest(ctx).getAge() >= 33;
                })
                .step("its T went up to 1 and came back down without a jump", ctx -> {
                    List<Float> samples = ctx.get("samples");
                    var max = samples.stream().max(Float::compare).orElse(0f);
                    var last = samples.getLast();
                    var jump = 0f;
                    for (int i = 1; i < samples.size(); i++) jump = Math.max(jump, Math.abs(samples.get(i) - samples.get(i - 1)));
                    ctx.check("it reached the top", max >= 0.95f, ">= 0.95", max);
                    ctx.check("it is on the way down", last <= 0.45f, "<= 0.45", last);
                    ctx.check("no jump between ticks", jump <= 0.12f, "<= 0.12", jump);
                    ctx.put("tAtStop", oldest(ctx).getT());
                    stopGently(ctx);
                })
                .waitUntil("3 ticks pass", ctx -> since(ctx) >= 3)
                .step("stopped on the way down, they go on down", ctx -> {
                    float before = ctx.get("tAtStop");
                    ctx.check("none was cut", living(ctx).size() == 4, 4, living(ctx).size());
                    ctx.check("the oldest's T kept falling", oldest(ctx).getT() < before, "< " + before, oldest(ctx).getT());
                })
                .timeoutMs(20_000)
                .waitUntil("15 ticks pass", ctx -> since(ctx) >= 15)
                .step("and die at the bottom", ctx ->
                        ctx.check("no particle is left", living(ctx).isEmpty(), 0, living(ctx).size()))

                .step("play looping particles from an emitter that runs 10 ticks, once", ctx -> play(ctx, fx(emitter(20, false, ParticleLifetime.Mode.Loop))))
                .waitUntil("all four are out", ctx -> living(ctx).size() == 4)
                .timeoutMs(20_000)
                .waitUntil("the emitter's own run is 40 ticks over", ctx -> emitter(ctx).getAge() >= 50)
                .step("its particles loop on: an emitter finishing is not a stop", ctx -> {
                    ctx.check("all four live", living(ctx).size() == 4, 4, living(ctx).size());
                    ctx.check("the effect runs on", !runtime(ctx).isFinished(), "running", "finished");
                })
                .step("stop the effect", ParticleLifetimeModeScenario::stopGently)
                .timeoutMs(20_000)
                .waitUntil("25 ticks pass", ctx -> since(ctx) >= 25)
                .step("now they end", ctx -> ctx.check("no particle is left", living(ctx).isEmpty(), 0, living(ctx).size()))

                .step("play looping particles under a control clip [0,30) that drains", ctx ->
                        play(ctx, fx(emitter(20, true, ParticleLifetime.Mode.Loop), drainingClip(0, 30))))
                .timeoutMs(20_000)
                .waitUntil("33 ticks after it started", ctx -> elapsed(ctx) >= 33)
                .step("the drain keeps them going through their cycle", ctx -> {
                    ctx.check("the emitter still runs", emitter(ctx).isActive(), "active", "inactive");
                    ctx.check("all four live", living(ctx).size() == 4, 4, living(ctx).size());
                })
                .timeoutMs(20_000)
                .waitUntil("58 ticks after it started", ctx -> elapsed(ctx) >= 58)
                .step("they died at its end and the drain is over", ctx -> {
                    ctx.check("no particle is left", living(ctx).isEmpty(), 0, living(ctx).size());
                    ctx.check("the emitter stopped", !emitter(ctx).isActive(), "inactive", "active");
                    ctx.check("the effect finished", runtime(ctx).isFinished(), "finished", "running");
                })
                .step("stop", ParticleLifetimeModeScenario::stop);
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private static ParticleEmitter emitter(int lifetime, boolean looping, ParticleLifetime.Mode mode) {
        var emitter = new ParticleEmitter();
        emitter.setName("e");
        emitter.transform()._setInternalID(E);
        var config = emitter.config;
        config.setLooping(looping);
        config.setDuration(10);
        config.setStartLifetime(NumberFunction.constant(lifetime));
        config.setLifetimeMode(mode);
        config.setMaxParticles(4);
        config.emission.setEmissionRate(NumberFunction.constant(1));
        return emitter;
    }

    private static Track drainingClip(double start, double duration) {
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(start, duration, 1).targetId(E).exitMode(Clip.ExitMode.DRAIN));
        return control;
    }

    private static FX fx(ParticleEmitter emitter, Track... tracks) {
        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        fx.getFxData().timeline().tracks().addAll(List.of(tracks));
        return fx;
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("particle_lifetime_mode/" + name).getBytes());
    }

    // ---- playing -----------------------------------------------------------------------------------

    private static void play(TestContext ctx, FX fx) {
        stop(ctx);
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition().relative(player.getDirection(), 5));
        executor.setOffset(0, 1.5f, 0);
        executor.setAllowMulti(true);
        ctx.put("executor", executor);
        executor.start();
        ctx.put("runtime", executor.getRuntime());
        ctx.put("playedAt", gameTime(ctx));
    }

    private static long elapsed(TestContext ctx) {
        return gameTime(ctx) - ctx.<Long>get("playedAt");
    }

    private static void stopGently(TestContext ctx) {
        ctx.put("stoppedAt", gameTime(ctx));
        runtime(ctx).destroy(false);
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static long since(TestContext ctx) {
        return gameTime(ctx) - ctx.<Long>get("stoppedAt");
    }

    private static FXRuntime runtime(TestContext ctx) {
        return ctx.get("runtime");
    }

    private static ParticleEmitter emitter(TestContext ctx) {
        return (ParticleEmitter) object(ctx, E);
    }

    /** The emitter's particles that have not died, oldest first. */
    private static List<TileParticle> living(TestContext ctx) {
        var out = new ArrayList<TileParticle>();
        for (var queue : emitter(ctx).getParticles().values()) {
            for (var particle : queue) {
                if (particle instanceof TileParticle tile && !tile.isRemoved()) out.add(tile);
            }
        }
        out.sort(Comparator.comparing(TileParticle::getAge).reversed());
        return out;
    }

    private static TileParticle oldest(TestContext ctx) {
        return ctx.get("oldest");
    }

    private static IFXObject object(TestContext ctx, UUID id) {
        var object = runtime(ctx).objects.get(id);
        ctx.require("object " + id + " is in the runtime", object != null);
        return object;
    }

    private static long gameTime(TestContext ctx) {
        return ctx.requirePlayer().level().getGameTime();
    }
}
