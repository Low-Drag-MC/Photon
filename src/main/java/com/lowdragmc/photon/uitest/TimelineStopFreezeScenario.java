package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.AudioClip;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.PhotonSignals;
import com.lowdragmc.photon.client.fx.timeline.Signal;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.TimelineSoundInstance;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.property.PositionPropertyType;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stopped timeline stays stopped: after a destroy nothing restarts, no signal or sound plays and the remnants drain
 * where they were; a destroy from a signal listener starts no sound in that evaluation; signals passed while dispatch
 * was off are not fired later; a frozen effect holds its animated pose between ticks.
 */
@LDLRegisterClient(name = "timeline_stop_freeze", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TimelineStopFreezeScenario implements UIScenario {
    private static final UUID A = id("a");
    private static final UUID B = id("b");
    private static final UUID MOVER = id("mover");

    @Override
    public void define(ScenarioBuilder s) {
        s.step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .step("listen to signals", TimelineStopFreezeScenario::listen)

                .step("play: A on [0,45), B on [30,80), signals at 5 and 40, a sound over [30,60)",
                        ctx -> play(ctx, destroyedFx()))
                .timeoutMs(20_000)
                .waitUntil("the clock reaches 10", ctx -> clock(ctx) >= 10)
                .step("destroy it, letting the remnants drain", ctx -> {
                    ctx.put("destroyedAt", gameTime(ctx));
                    ctx.put("clockAtDestroy", clock(ctx));
                    runtime(ctx).destroy(false);
                })
                .waitUntil("40 ticks pass", ctx -> gameTime(ctx) >= ctx.<Long>get("destroyedAt") + 40)
                .step("nothing on the timeline ran after the destroy", ctx -> {
                    double stoppedAt = ctx.get("clockAtDestroy");
                    ctx.check("the clock stopped", clock(ctx) <= stoppedAt + 1, "<= " + (stoppedAt + 1), clock(ctx));
                    var b = (ParticleEmitter) object(ctx, B);
                    ctx.check("B's clip at 30 did not bring it back", !b.isActive() && b.getParticleAmount() == 0,
                            "inactive, 0 particles", describe(b));
                    ctx.check("the signal at 40 did not fire", fired(ctx, "late") == 0, 0, fired(ctx, "late"));
                    ctx.check("no sound started", playing(ctx).isEmpty(), "none", playing(ctx).size());
                    var a = (ParticleEmitter) object(ctx, A);
                    ctx.check("A's remnants drain where they were, not cut when its clip ended at 45",
                            a.isActive() && a.getParticleAmount() > 0, "active with particles", describe(a));
                })
                .step("stop", TimelineStopFreezeScenario::stop)

                .step("play: a signal at 10 whose listener destroys the effect, and a sound from 10",
                        ctx -> play(ctx, killedFx()))
                .waitUntil("the listener destroyed it", ctx -> fired(ctx, "kill") > 0)
                .ticks(3)
                .step("the evaluation that fired the signal started no sound", ctx ->
                        ctx.check("no sound plays", playing(ctx).isEmpty(), "none", playing(ctx).size()))
                .step("stop", TimelineStopFreezeScenario::stop)

                .step("play signals at 5, 15, 25 and 35 with dispatch off", ctx -> {
                    play(ctx, windowFx());
                    runtime(ctx).timelinePlayer.setSignalDispatch(false);
                })
                .waitUntil("the clock reaches 20", ctx -> clock(ctx) >= 20)
                .step("turn dispatch on", ctx -> runtime(ctx).timelinePlayer.setSignalDispatch(true))
                .waitUntil("the clock reaches 22", ctx -> clock(ctx) >= 22)
                .step("the signals passed while it was off are not fired now", ctx ->
                        ctx.check("nothing fired", total(ctx, "w") == 0, 0, total(ctx, "w")))
                .waitUntil("the clock reaches 27", ctx -> clock(ctx) >= 27)
                .step("the next one fires once", ctx -> {
                    ctx.check("25 fired once", fired(ctx, "w25") == 1, 1, fired(ctx, "w25"));
                    ctx.check("and nothing else", total(ctx, "w") == 1, 1, total(ctx, "w"));
                })
                .step("stop", TimelineStopFreezeScenario::stop)

                .step("play an object moving a block per tick", ctx -> play(ctx, moverFx()))
                .waitUntil("the clock reaches 10", ctx -> clock(ctx) >= 10)
                .step("freeze the effect", ctx -> runtime(ctx).setRate(0))
                .ticks(2)
                .step("a frame between two ticks keeps the frozen pose", ctx -> {
                    var root = (FXObject) runtime(ctx).root;
                    var mover = object(ctx, MOVER);
                    root.updateFrame(0);
                    var at = mover.transform().localPosition().x;
                    root.updateFrame(0.9f);
                    var later = mover.transform().localPosition().x;
                    ctx.check("the pose did not move", Math.abs(later - at) < 1e-3, at, later);
                })
                .step("stop", TimelineStopFreezeScenario::stop)
                .step("stop listening", ctx -> PhotonSignals.unregister(ctx.get("listener")));
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private static FX destroyedFx() {
        var a = emitter("a", A, 80);
        var b = emitter("b", B, 20);
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 45, 1).targetId(A));
        control.clips().add(new Clip(30, 50, 1).targetId(B));
        var sound = PhotonTrackTypes.AUDIO.create();
        sound.clips().add(new AudioClip(30, 30, 1));
        return fx(List.of(a, b), List.of(control, signals("freeze", 5, "early", 40, "late"), sound));
    }

    private static FX killedFx() {
        var sound = PhotonTrackTypes.AUDIO.create();
        sound.clips().add(new AudioClip(10, 30, 1));
        return fx(List.of(), List.of(signals("kill", 10, "kill"), sound));
    }

    private static FX windowFx() {
        return fx(List.of(), List.of(signals("window", 5, "w5", 15, "w15", 25, "w25", 35, "w35")));
    }

    private static FX moverFx() {
        var mover = named(new EmptyFXObject(), "mover", MOVER);
        var move = (AnimationTrack) PhotonTrackTypes.ANIMATION.create().targetId(MOVER);
        var position = PositionPropertyType.INSTANCE.create(mover);
        position.putKey(0, 0, 0);
        position.putKey(0, 40, 40);
        move.properties().add(position);
        return fx(List.of(mover), List.of(move));
    }

    private static SignalTrack signals(String channel, Object... timesAndNames) {
        var track = (SignalTrack) PhotonTrackTypes.SIGNAL.create();
        track.displayName(channel);
        for (int i = 0; i < timesAndNames.length; i += 2) {
            track.signals().add(new Signal(((Number) timesAndNames[i]).doubleValue(), (String) timesAndNames[i + 1]));
        }
        return track;
    }

    private static FX fx(List<? extends IFXObject> objects, List<Track> tracks) {
        var fx = new FX();
        fx.getFxData().objects().addAll(objects);
        fx.getFxData().timeline().tracks().addAll(tracks);
        return fx;
    }

    private static ParticleEmitter emitter(String name, UUID id, int lifetime) {
        var emitter = named(new ParticleEmitter(), name, id);
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(40);
        config.setStartLifetime(NumberFunction.constant(lifetime));
        config.setMaxParticles(8);
        config.emission.setEmissionRate(NumberFunction.constant(1));
        return emitter;
    }

    private static <T extends IFXObject> T named(T object, String name, UUID id) {
        object.setName(name);
        object.transform()._setInternalID(id);
        return object;
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("timeline_stop_freeze/" + name).getBytes());
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
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static void listen(TestContext ctx) {
        var fired = new ConcurrentHashMap<String, Integer>();
        ctx.put("fired", fired);
        PhotonSignals.Listener listener = (effect, channel, name, data, time) -> {
            if (effect == null || effect != ctx.get("executor")) return;
            fired.merge(name, 1, Integer::sum);
            if ("kill".equals(name)) ctx.<FXRuntime>get("runtime").destroy(false);
        };
        PhotonSignals.register(listener);
        ctx.put("listener", listener);
    }

    private static int fired(TestContext ctx, String name) {
        return ctx.<Map<String, Integer>>get("fired").getOrDefault(name, 0);
    }

    private static int total(TestContext ctx, String prefix) {
        return ctx.<Map<String, Integer>>get("fired").entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix)).mapToInt(Map.Entry::getValue).sum();
    }

    /** The root timeline's sounds that are still playing. */
    private static List<TimelineSoundInstance> playing(TestContext ctx) {
        Map<?, TimelineSoundInstance> sounds = ctx.getField(runtime(ctx).timelinePlayer, "audioInstances");
        return sounds.values().stream().filter(sound -> !ctx.<Boolean>getField(sound, "stopRequested")).toList();
    }

    private static FXRuntime runtime(TestContext ctx) {
        return ctx.get("runtime");
    }

    private static double clock(TestContext ctx) {
        return runtime(ctx).timelinePlayer.localTime();
    }

    private static long gameTime(TestContext ctx) {
        return ctx.requirePlayer().level().getGameTime();
    }

    private static IFXObject object(TestContext ctx, UUID id) {
        var object = runtime(ctx).objects.get(id);
        ctx.require("object " + id + " is in the runtime", object != null);
        return object;
    }

    private static String describe(ParticleEmitter emitter) {
        return (emitter.isActive() ? "active, " : "inactive, ") + emitter.getParticleAmount() + " particles";
    }
}
