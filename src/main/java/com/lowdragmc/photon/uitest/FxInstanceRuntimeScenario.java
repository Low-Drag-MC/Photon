package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXInstances;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceRecord;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.instance.InstanceWriter;
import com.lowdragmc.photon.client.fx.instance.NbtPatch;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.AudioClip;
import com.lowdragmc.photon.client.fx.timeline.AudioTrack;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.NestedTimelinePlayer;
import com.lowdragmc.photon.client.fx.timeline.PhotonSignals;
import com.lowdragmc.photon.client.fx.timeline.Signal;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.SpeedTrack;
import com.lowdragmc.photon.client.fx.timeline.TimelineSoundInstance;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.property.PositionPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ScalePropertyType;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FX instances at runtime with real objects: placing (ids, parents, overrides, nesting), every object kind
 * coming back from its source unchanged, the source timeline played on the instance's clock (started by a
 * control clip, slowed by a speed track, overridden by the outer timeline), disabled objects, signals,
 * completion, missing sources, cycles, invalidation and preloading; and what a paused instance must let go of
 * (its audio, the particle engine), speeds and animation layered with the outer timeline, a restart from the
 * source's root, and a placed object this game cannot read.
 */
@LDLRegisterClient(name = "fx_instance_runtime", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxInstanceRuntimeScenario implements UIScenario {
    private static final String ROOT = "uitest_instance";
    private static String dir = ROOT;

    private static final UUID B_EMIT = id("b_emit");
    private static final UUID B_LATE = id("b_late");
    private static final UUID B_GROUP = id("b_group");
    private static final UUID B_CHILD = id("b_child");
    private static final UUID R_EMIT = id("r_emit");
    private static final UUID K = id("K");
    private static final UUID P = id("P");
    private static final UUID R = id("R");
    private static final UUID N = id("N");
    private static final UUID Q = id("Q");
    private static final UUID M = id("M");
    private static final UUID X = id("X");
    private static final UUID Y = id("Y");
    private static final UUID S = id("S");
    private static final UUID A = id("A");
    private static final UUID T = id("T");
    private static final UUID U = id("U");
    private static final UUID GHOST = id("ghost");
    private static final UUID GHOST2 = id("ghost2");
    private static final UUID O = id("O");
    private static final UUID I2 = id("I2");

    private record Fired(UUID instance, String name, double time) {
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("write the fixtures", FxInstanceRuntimeScenario::writeFixtures)
                .step("the outer effect loads with every instance placed", FxInstanceRuntimeScenario::checkPlaced)
                .step("every kind of object comes back from its source unchanged", FxInstanceRuntimeScenario::checkEveryKind)
                .step("saving a loaded effect writes its records, not what they placed", FxInstanceRuntimeScenario::checkResave)
                .step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .frames(2)

                .step("play the outer effect", ctx -> {
                    listenToSignals(ctx);
                    play(ctx, "outer");
                })
                .timeoutMs(20_000)
                .waitUntil("the outer clock reaches 5", ctx -> clock(ctx) >= 5)
                .step("at 5: the outer timeline drives what it binds, the rest waits", FxInstanceRuntimeScenario::checkAt5)
                .waitUntil("the outer clock reaches 25", ctx -> clock(ctx) >= 25)
                .step("at 25: the control clip started P's timeline at 20; N runs at half speed", FxInstanceRuntimeScenario::checkAt25)
                .waitUntil("the outer clock reaches 37", ctx -> clock(ctx) >= 37)
                .step("at 37: P's timeline has reached its activator", FxInstanceRuntimeScenario::checkAt37)
                .waitUntil("the outer clock reaches 70", ctx -> clock(ctx) >= 70)
                .step("every instance fired each of its source's signals once", FxInstanceRuntimeScenario::checkSignals)
                .step("stop the outer effect", FxInstanceRuntimeScenario::stop)

                .step("play an effect that is only an instance", ctx -> play(ctx, "solo"))
                .ticks(3)
                .step("it is not finished while its timeline waits to activate what it placed", ctx -> {
                    var runtime = runtime(ctx);
                    ctx.check("the effect is still running", !runtime.isFinished(), "running", "finished");
                    ctx.check("what the instance placed waits for the source's root activator",
                            !object(ctx, InstanceIds.xor(Q, R_EMIT)).isActive(), "inactive", "active");
                })
                .waitUntil("the source root's activator turns what it placed on", ctx ->
                        object(ctx, InstanceIds.xor(Q, R_EMIT)).isActive())
                .ticks(4)
                .step("what it placed was kept in the particle engine while it waited", ctx -> {
                    var emitter = (ParticleEmitter) object(ctx, InstanceIds.xor(Q, R_EMIT));
                    ctx.check("the emitter is emitting", emitter.getParticleAmount() > 0, "> 0", emitter.getParticleAmount());
                })
                .timeoutMs(20_000)
                .waitUntil("the effect finishes once its timeline and particles are done", ctx -> runtime(ctx).isFinished())
                .step("it finished after its timeline's end", ctx -> {
                    var instance = (FXInstanceObject) object(ctx, Q);
                    var nested = instance.nestedPlayer();
                    ctx.check("the instance's timeline ran to its end", nested != null && nested.isFinished(),
                            "finished", nested == null ? "no player" : nested.localTime());
                })
                .step("stop the solo effect", FxInstanceRuntimeScenario::stop)

                .step("a missing source loads empty and keeps its record", FxInstanceRuntimeScenario::checkMissing)
                .step("writing the source brings it in", FxInstanceRuntimeScenario::checkMissingArrives)
                .step("a cycle loads without recursing", FxInstanceRuntimeScenario::checkCycle)
                .step("invalidating a source reloads what places it", FxInstanceRuntimeScenario::checkInvalidation)
                .step("preload an effect made of instances, its sources not yet read", ctx -> {
                    FXHelper.clearCache();
                    ctx.put("preload", FXHelper.preload(fx("outer_preload")));
                })
                .timeoutMs(60_000)
                .waitUntil("the preload completes", ctx -> ctx.<CompletableFuture<FX>>get("preload").isDone())
                .step("the preload placed its instances", ctx -> {
                    var fx = ctx.<CompletableFuture<FX>>get("preload").getNow(null);
                    ctx.check("the preloaded definition is cached", fx != null && fx == FXHelper.getCachedFX(fx("outer_preload")),
                            "cached", fx);
                    if (fx == null) return;
                    var ids = fx.getFxData().objects().stream().map(IFXObject::id).toList();
                    ctx.check("its instance's content is placed", ids.contains(InstanceIds.xor(P, B_LATE)), "P^b_late", ids);
                    ctx.check("nothing read ahead is left over", prefetched().isEmpty(), "empty", prefetched().keySet());
                })

                .step("play an instance whose control clip ends before its source's timeline", ctx -> play(ctx, "paused_outer"))
                .timeoutMs(20_000)
                .waitUntil("its source's sound plays", ctx -> sound(ctx, X) != null)
                .step("remember the sound", ctx -> ctx.put("pausedSound", sound(ctx, X)))
                .waitUntil("the clip ends", ctx -> !((FXInstanceObject) object(ctx, X)).isOuterActive())
                .ticks(2)
                .step("paused, it plays nothing", ctx -> {
                    var instance = (FXInstanceObject) object(ctx, X);
                    ctx.check("the clip is over", !instance.isOuterActive(), "inactive", "active");
                    ctx.check("its clock stopped short of its end", instance.isTimelineRunning(), "running", "finished");
                    ctx.check("the sound it played was stopped", isStopped(ctx, ctx.get("pausedSound")), "stopped", "playing");
                    ctx.check("and nothing plays now", sound(ctx, X) == null, "no sound", sound(ctx, X));
                })
                .timeoutMs(10_000)
                .waitUntil("the effect leaves the particle engine", ctx -> !runtime(ctx).isValid())
                .step("nothing of it was kept", ctx -> {
                    ctx.check("the effect is finished", runtime(ctx).isFinished(), "finished", "running");
                    var alive = runtime(ctx).objects.values().stream().filter(IFXObject::isAlive).map(IFXObject::getName).toList();
                    ctx.check("no object is alive, the looping emitter included", alive.isEmpty(), List.of(), alive);
                })

                .step("play an instance whose source pauses an instance inside it from 10 to 30", ctx -> play(ctx, "pauser_outer"))
                .waitUntil("the inner one is paused", ctx -> nestedClock(ctx, O) >= 14)
                .step("paused inside a playing instance, it is kept", ctx -> {
                    var inner = (FXInstanceObject) object(ctx, InstanceIds.xor(O, I2));
                    ctx.check("the inner instance is paused", !inner.isOuterActive(), "paused", "active");
                    ctx.check("what it placed stays in the engine", object(ctx, InstanceIds.xor(InstanceIds.xor(O, I2), id("ps_emit"))).isAlive(),
                            "alive", "gone");
                    ctx.put("pausedAt", nestedClock(ctx, InstanceIds.xor(O, I2)));
                })
                .waitUntil("the outer one's clock reaches 36", ctx -> nestedClock(ctx, O) >= 36)
                .step("the inner one went on from where it stopped", ctx -> {
                    var innerClock = nestedClock(ctx, InstanceIds.xor(O, I2));
                    var expected = ctx.<Double>get("pausedAt") + nestedClock(ctx, O) - 30;
                    ctx.check("its clock resumed rather than restarted", Math.abs(innerClock - expected) <= 2, expected, innerClock);
                    stop(ctx);
                })

                .step("play an instance with a sound and nothing outside", ctx -> play(ctx, "audio_outer"))
                .waitUntil("its sound plays", ctx -> sound(ctx, Y) != null)
                .step("the editor's pause silences it at once", ctx -> {
                    var sound = sound(ctx, Y);
                    runtime(ctx).timelinePlayer.setAudioDispatch(false);
                    ctx.check("the sound was stopped", isStopped(ctx, sound), "stopped", "playing");
                    ctx.check("and nothing plays", sound(ctx, Y) == null, "no sound", sound(ctx, Y));
                    runtime(ctx).timelinePlayer.setAudioDispatch(true);
                })
                .waitUntil("playing again, it sounds again", ctx -> sound(ctx, Y) != null)
                .step("removed from the effect, it is silent", ctx -> {
                    var sound = sound(ctx, Y);
                    var instance = object(ctx, Y);
                    runtime(ctx).removeSceneObject(instance);
                    ctx.check("the sound was stopped", isStopped(ctx, sound), "stopped", "playing");
                    runtime(ctx).addSceneObject(instance);
                })
                .waitUntil("back in the effect, it sounds again", ctx -> sound(ctx, Y) != null)
                .step("destroying the effect stops what its instances play", ctx -> {
                    var sound = sound(ctx, Y);
                    var player = ((FXInstanceObject) object(ctx, Y)).nestedPlayer();
                    stop(ctx);
                    ctx.check("the sound was stopped", isStopped(ctx, sound), "stopped", "playing");
                    ctx.check("its timeline is finished", player != null && player.isFinished(), "finished", "running");
                })

                .step("play an instance whose source slows its root and its emitter", ctx -> {
                    play(ctx, "slow_outer");
                    ctx.put("slowStart", gameTime(ctx));
                })
                .waitUntil("ten ticks pass", ctx -> gameTime(ctx) - ctx.<Long>get("slowStart") >= 10)
                .step("speeds stack, the outer one winning where both speak", ctx -> {
                    var instance = (FXInstanceObject) object(ctx, S);
                    var emit = (com.lowdragmc.photon.client.gameobject.FXObject) object(ctx, InstanceIds.xor(S, id("ss_emit")));
                    ctx.check("the source's root speed slows the instance", Math.abs(instance.getSelfTimeScale() - 0.5f) < 1e-4,
                            0.5, instance.getSelfTimeScale());
                    ctx.check("the outer speed track wins over the source's for the emitter",
                            Math.abs(emit.getSelfTimeScale() - 3f) < 1e-4, 3, emit.getSelfTimeScale());
                    ctx.check("which runs at both", Math.abs(emit.timeScale() - 1.5f) < 1e-4, 1.5, emit.timeScale());
                    var elapsed = gameTime(ctx) - ctx.<Long>get("slowStart");
                    ctx.check("the instance's clock keeps the outer pace, not its own root's", Math.abs(nestedClock(ctx, S) - elapsed) <= 2,
                            elapsed, nestedClock(ctx, S));
                    runtime(ctx).setRate(2);
                    ctx.put("rateStart", gameTime(ctx));
                    ctx.put("rateClock", nestedClock(ctx, S));
                })
                .waitUntil("ten more ticks pass", ctx -> gameTime(ctx) - ctx.<Long>get("rateStart") >= 10)
                .step("the effect's rate runs the instance's clock too", ctx -> {
                    var elapsed = gameTime(ctx) - ctx.<Long>get("rateStart");
                    var advanced = nestedClock(ctx, S) - ctx.<Double>get("rateClock");
                    ctx.check("twice as fast", Math.abs(advanced - 2 * elapsed) <= 3, 2 * elapsed, advanced);
                    stop(ctx);
                })

                .step("play an instance whose source animates its emitter", ctx -> play(ctx, "anim_outer"))
                .waitUntil("its clock reaches 20", ctx -> nestedClock(ctx, A) >= 20)
                .step("the outer animation wins for the property it animates, the source keeps the rest", ctx -> {
                    var emit = object(ctx, InstanceIds.xor(A, id("as_emit")));
                    var position = emit.transform().localPosition();
                    var scale = emit.transform().localScale();
                    ctx.check("held where the outer track puts it", Math.abs(position.x - 5) < 0.01, 5, position.x);
                    ctx.check("scaled as the source says", scale.x > 1.4f, "> 1.4", scale.x);
                    stop(ctx);
                })

                .step("play an instance whose source restarts its root", ctx -> play(ctx, "restart_outer"))
                .waitUntil("its clock reaches 15", ctx -> nestedClock(ctx, T) >= 15)
                .step("what it placed restarted at 10, its clock did not", ctx -> {
                    var clock = nestedClock(ctx, T);
                    var emitter = (ParticleEmitter) object(ctx, InstanceIds.xor(T, id("rs_emit")));
                    ctx.check("the clock went on past the restart", clock >= 15 && clock < 30, ">= 15", clock);
                    ctx.check("the emitter is younger than the clock", emitter.getAge() <= clock - 8, "<= " + (clock - 8),
                            emitter.getAge());
                    stop(ctx);
                })

                .step("an object replacing an instance under its id replaces it among the instances", ctx -> {
                    play(ctx, "solo");
                    var runtime = runtime(ctx);
                    var original = (FXInstanceObject) runtime.objects.get(Q);
                    var duplicate = new FXInstanceObject(fx("rooted"));
                    duplicate.transform()._setInternalID(Q);
                    runtime.addSceneObjectInternal(duplicate);
                    List<?> instances = ctx.getField(runtime, "instances");
                    ctx.check("only the newer is an instance of the effect", instances.contains(duplicate) && !instances.contains(original),
                            "the duplicate", instances);
                    stop(ctx);
                })

                .step("a placed object of a type this game lacks is not taken as removed", FxInstanceRuntimeScenario::checkUnreadable);

        s.teardown("stop the effect and delete the fixtures", ctx -> {
            stop(ctx);
            var listener = ctx.<PhotonSignals.Listener>get("signals");
            if (listener != null) PhotonSignals.unregister(listener);
            deleteTree(new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + ROOT));
            FXHelper.clearCache();
        });
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("fx_instance_runtime/" + name).getBytes());
    }

    private static ResourceLocation fx(String name) {
        return Photon.id(dir + "/" + name);
    }

    private static <T extends IFXObject> T named(T object, String name, UUID id) {
        object.setName(name);
        object.transform()._setInternalID(id);
        return object;
    }

    private static ParticleEmitter emitter(String name, UUID id, boolean looping) {
        var emitter = named(new ParticleEmitter(), name, id);
        var config = emitter.config;
        config.setLooping(looping);
        config.setDuration(looping ? 40 : 10);
        config.setStartLifetime(NumberFunction.constant(looping ? 20 : 5));
        config.setMaxParticles(8);
        config.emission.setEmissionRate(NumberFunction.constant(1));
        return emitter;
    }

    private static void under(IFXObject child, IFXObject parent) {
        child.transform()._setInternalParentID(parent.id());
    }

    private static FXInstanceObject instance(String name, UUID id, String source, @Nullable InstanceRecord record) {
        var instance = named(new FXInstanceObject(fx(source)), name, id);
        if (record != null) instance.setOverrides(record.write());
        return instance;
    }

    private static InstanceRecord.Modification set(UUID target, net.minecraft.nbt.Tag value, String... path) {
        return new InstanceRecord.Modification(target, NbtPatch.Change.set(List.of(path), value));
    }

    private static void writeFixtures(TestContext ctx) {
        dir = ROOT + "/" + Long.toString(System.nanoTime() & 0xffffffL, 36);
        FXHelper.clearCache();
        try {
            writeBase("base", 40);

            var rEmit = emitter("r_emit", R_EMIT, false);
            var rooted = PhotonTrackTypes.ACTIVATOR.create().targetId(FXRuntime.ROOT_UUID);
            rooted.clips().add(new Clip(5, 20, 1));
            write("rooted", List.of(rEmit), List.of(rooted));

            var nEmit = named(new EmptyFXObject(), "n_emit", id("n_emit"));
            write("nested", List.of(instance("K", K, "base", null), nEmit), List.of());

            write("cycle_a", List.of(emitter("ca_emit", id("ca_emit"), true), instance("to_b", id("to_b"), "cycle_b", null)), List.of());
            write("cycle_b", List.of(emitter("cb_emit", id("cb_emit"), true), instance("to_a", id("to_a"), "cycle_a", null)), List.of());

            var disabled = new CompoundTag();
            disabled.putBoolean("enabled", false);
            var record = new InstanceRecord(List.of(
                    set(B_EMIT, IntTag.valueOf(77), "config", "duration"),
                    set(B_GROUP, StringTag.valueOf("group renamed"), "name"),
                    set(B_CHILD, disabled, "_additional")), List.of(), List.of());
            var control = PhotonTrackTypes.CONTROL.create();
            control.clips().add(new Clip(20, 40, 1).targetId(P));
            var outerWins = PhotonTrackTypes.ACTIVATOR.create().targetId(InstanceIds.xor(R, B_LATE));
            outerWins.clips().add(new Clip(0, 200, 1));
            var cannotWake = PhotonTrackTypes.ACTIVATOR.create().targetId(InstanceIds.xor(P, B_CHILD));
            cannotWake.clips().add(new Clip(0, 200, 1));
            var half = (SpeedTrack) PhotonTrackTypes.SPEED.create().targetId(N);
            half.speedProperty().putKey(0, 0, 0.5f);
            var outer = List.<IFXObject>of(instance("P", P, "base", record), instance("R", R, "base", null),
                    instance("N", N, "nested", null));
            var tracks = List.<Track>of(control, outerWins, cannotWake, half);
            write("outer", outer, tracks);
            write("outer_preload", List.of(instance("P", P, "base", null)), List.of());

            write("solo", List.of(instance("Q", Q, "rooted", null)), List.of());
            write("missing_outer", List.of(instance("M", M, "absent", new InstanceRecord(
                    List.of(set(B_EMIT, IntTag.valueOf(5), "config", "duration")), List.of(), List.of()))), List.of());

            // one source and one pristine instance of it per object kind
            for (var type : PhotonRegistries.FX_OBJECTS) {
                if (type == FXInstanceObject.TYPE) continue;
                var object = named(type.create(), "kind", id("kind/" + type.name()));
                write("kind_" + type.name(), List.of(object), List.of());
                write("kind_outer_" + type.name(), List.of(instance("I", id("I/" + type.name()), "kind_" + type.name(), null)), List.of());
            }
            writePausing();
            writeLayered();
            writeUnreadable();

            var rich = emitter("rich", id("rich"), true);
            rich.config.noise.setEnable(true);
            rich.config.physics.setEnable(true);
            rich.config.colorOverLifetime.setEnable(true);
            rich.config.sizeOverLifetime.setEnable(true);
            rich.config.rotationOverLifetime.setEnable(true);
            rich.config.velocityOverLifetime.setEnable(true);
            rich.config.trails.setEnable(true);
            write("kind_rich", List.of(rich), List.of());
            write("kind_outer_rich", List.of(instance("I", id("I/rich"), "kind_rich", null)), List.of());
        } catch (IOException e) {
            ctx.require("the fixtures were written: " + e, false);
        }
    }

    /** paused_src: a looping emitter on for 40 ticks with a sound as long; paused_outer gives it 10, audio_outer all. */
    private static void writePausing() throws IOException {
        var emit = emitter("ps_emit", id("ps_emit"), true);
        var on = PhotonTrackTypes.ACTIVATOR.create().targetId(id("ps_emit"));
        on.clips().add(new Clip(0, 40, 1));
        var sound = PhotonTrackTypes.AUDIO.create();
        sound.clips().add(new AudioClip(0, 40, 1));
        write("paused_src", List.of(emit), List.of(on, sound));
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 10, 1).targetId(X));
        write("paused_outer", List.of(instance("X", X, "paused_src", null)), List.of(control));
        write("audio_outer", List.of(instance("Y", Y, "paused_src", null)), List.of());
        var gaps = PhotonTrackTypes.ACTIVATOR.create().targetId(I2);
        gaps.clips().add(new Clip(0, 10, 1));
        gaps.clips().add(new Clip(30, 30, 1));
        write("pauser_src", List.of(instance("I2", I2, "paused_src", null)), List.of(gaps));
        write("pauser_outer", List.of(instance("O", O, "pauser_src", null)), List.of());
    }

    /** Speeds, animation and a restart from the source's root, each with what the outer timeline says over it. */
    private static void writeLayered() throws IOException {
        var ssEmit = emitter("ss_emit", id("ss_emit"), true);
        var rootSpeed = (SpeedTrack) PhotonTrackTypes.SPEED.create().targetId(FXRuntime.ROOT_UUID);
        rootSpeed.speedProperty().putKey(0, 0, 0.5f);
        var emitSpeed = (SpeedTrack) PhotonTrackTypes.SPEED.create().targetId(id("ss_emit"));
        emitSpeed.speedProperty().putKey(0, 0, 0.25f);
        var ssOn = PhotonTrackTypes.ACTIVATOR.create().targetId(id("ss_emit"));
        ssOn.clips().add(new Clip(0, 400, 1));
        write("slow_src", List.of(ssEmit), List.of(rootSpeed, emitSpeed, ssOn));
        var outerSpeed = (SpeedTrack) PhotonTrackTypes.SPEED.create().targetId(InstanceIds.xor(S, id("ss_emit")));
        outerSpeed.speedProperty().putKey(0, 0, 3f);
        write("slow_outer", List.of(instance("S", S, "slow_src", null)), List.of(outerSpeed));

        var asEmit = emitter("as_emit", id("as_emit"), true);
        var move = (AnimationTrack) PhotonTrackTypes.ANIMATION.create().targetId(id("as_emit"));
        var position = PositionPropertyType.INSTANCE.create(asEmit);
        position.putKey(0, 0, 0);
        position.putKey(0, 40, 40);
        var scale = ScalePropertyType.INSTANCE.create(asEmit);
        scale.putKey(0, 0, 1);
        scale.putKey(0, 40, 3);
        move.properties().add(position);
        move.properties().add(scale);
        write("anim_src", List.of(asEmit), List.of(move));
        var hold = (AnimationTrack) PhotonTrackTypes.ANIMATION.create().targetId(InstanceIds.xor(A, id("as_emit")));
        var held = PositionPropertyType.INSTANCE.create(emitter("base_pose", id("base_pose"), true));
        held.putKey(0, 0, 5);
        held.putKey(0, 80, 5);
        hold.properties().add(held);
        write("anim_outer", List.of(instance("A", A, "anim_src", null)), List.of(hold));

        var rsEmit = emitter("rs_emit", id("rs_emit"), true);
        var restart = PhotonTrackTypes.CONTROL.create();
        restart.clips().add(new Clip(0, 10, 1).targetId(FXRuntime.ROOT_UUID));
        restart.clips().add(new Clip(10, 30, 1).targetId(FXRuntime.ROOT_UUID));
        write("restart_src", List.of(rsEmit), List.of(restart));
        write("restart_outer", List.of(instance("T", T, "restart_src", null)), List.of());
    }

    /**
     * unreadable_src: ghost (a type this game does not have) ⊃ ur_child, and ur_top. unreadable_outer renames the
     * ghost and hangs ur_added under it.
     */
    private static void writeUnreadable() throws IOException {
        var child = emitter("ur_child", id("ur_child"), true);
        child.transform()._setInternalParentID(GHOST2);
        var source = new FX();
        source.getFxData().objects().addAll(List.of(child, emitter("ur_top", id("ur_top"), true)));
        var tag = source.serializeNBT(Platform.getFrozenRegistry());
        var objects = tag.getCompound("fxData").getList(InstanceTags.OBJECTS, CompoundTag.TAG_COMPOUND);
        objects.addTag(0, unreadable("ghost2", GHOST2, GHOST, id("ur_child")));
        objects.addTag(0, unreadable("ghost", GHOST, FXRuntime.ROOT_UUID, GHOST2));
        writeTag("unreadable_src", tag);

        var added = emitter("ur_added", id("ur_added"), true);
        added.transform()._setInternalParentID(InstanceIds.xor(U, GHOST));
        var record = new InstanceRecord(List.of(set(GHOST, StringTag.valueOf("renamed ghost"), InstanceTags.NAME)), List.of(),
                List.of(new InstanceRecord.Added(GHOST, id("ur_added"))));
        write("unreadable_outer", List.of(instance("U", U, "unreadable_src", record), added), List.of());
    }

    /** An object of a type this game does not have. */
    private static CompoundTag unreadable(String name, UUID id, UUID parent, UUID child) {
        var transform = new CompoundTag();
        transform.putString(InstanceTags.ID, id.toString());
        transform.putString(InstanceTags.PARENT, parent.toString());
        var children = new ListTag();
        children.add(StringTag.valueOf(child.toString()));
        transform.put(InstanceTags.CHILDREN, children);
        var data = new CompoundTag();
        data.putString(InstanceTags.NAME, name);
        data.put(InstanceTags.TRANSFORM, transform);
        var object = new CompoundTag();
        object.putString(InstanceTags.TYPE, "uitest_no_such_object");
        object.put(InstanceTags.DATA, data);
        return object;
    }

    /** emit, late (an activator from 10 to 30), group ⊃ child; signals "start" at 0 and "mid" at 15. */
    private static void writeBase(String name, int emitDuration) throws IOException {
        var emit = emitter("b_emit", B_EMIT, true);
        emit.config.setDuration(emitDuration);
        var late = emitter("b_late", B_LATE, true);
        var group = named(new EmptyFXObject(), "b_group", B_GROUP);
        var child = emitter("b_child", B_CHILD, true);
        under(child, group);
        var activator = PhotonTrackTypes.ACTIVATOR.create().targetId(B_LATE);
        activator.clips().add(new Clip(10, 20, 1));
        var signals = (SignalTrack) PhotonTrackTypes.SIGNAL.create();
        signals.displayName("base");
        signals.signals().add(new Signal(0, "start"));
        signals.signals().add(new Signal(15, "mid"));
        write(name, List.of(emit, late, group, child), List.of(activator, signals));
    }

    private static void write(String name, List<? extends IFXObject> objects, List<Track> tracks) throws IOException {
        var fx = new FX();
        fx.getFxData().objects().addAll(objects);
        fx.getFxData().timeline().tracks().addAll(tracks);
        writeTag(name, fx.serializeNBT(Platform.getFrozenRegistry()));
    }

    private static void writeTag(String name, CompoundTag tag) throws IOException {
        tag.putInt("version", FXProject.VERSION);
        var file = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + dir + "/" + name + FX.SUFFIX);
        Files.createDirectories(file.getParentFile().toPath());
        NbtIo.writeCompressed(tag, file.toPath());
    }

    // ---- placing -----------------------------------------------------------------------------------

    private static Map<UUID, IFXObject> objects(FX fx) {
        var out = new java.util.HashMap<UUID, IFXObject>();
        for (var object : fx.getFxData().objects()) out.put(object.id(), object);
        return out;
    }

    private static void checkPlaced(TestContext ctx) {
        var fx = FXHelper.getFX(fx("outer"));
        ctx.require("the outer effect loads", fx != null);
        var objects = objects(fx);
        var expected = List.of(P, InstanceIds.xor(P, B_EMIT), InstanceIds.xor(P, B_LATE), InstanceIds.xor(P, B_GROUP),
                InstanceIds.xor(P, B_CHILD), R, InstanceIds.xor(R, B_EMIT), N, InstanceIds.xor(N, K),
                InstanceIds.xor(InstanceIds.xor(N, K), B_LATE), InstanceIds.xor(N, id("n_emit")));
        var missing = expected.stream().filter(id -> !objects.containsKey(id)).toList();
        ctx.check("every placed object is there under its derived id", missing.isEmpty(), "none missing", missing);
        // P and R: the instance + 4 objects each; N: the instance + K, n_emit and K's 4
        ctx.check("nothing else is there", objects.size() == 17, 17, objects.size());

        var pEmit = (ParticleEmitter) objects.get(InstanceIds.xor(P, B_EMIT));
        ctx.check("an override is applied", pEmit != null && pEmit.config.getDuration() == 77, 77,
                pEmit == null ? null : pEmit.config.getDuration());
        var rEmit = (ParticleEmitter) objects.get(InstanceIds.xor(R, B_EMIT));
        ctx.check("another instance of the same source keeps the source's value",
                rEmit != null && rEmit.config.getDuration() == 40, 40, rEmit == null ? null : rEmit.config.getDuration());
        var group = objects.get(InstanceIds.xor(P, B_GROUP));
        ctx.check("a renamed object has its new name", group != null && group.getName().equals("group renamed"),
                "group renamed", group == null ? null : group.getName());
        var child = objects.get(InstanceIds.xor(P, B_CHILD));
        ctx.check("a disabled object is disabled", child != null && !child.isEnabled(), "disabled", child);
        var rChild = objects.get(InstanceIds.xor(R, B_CHILD));
        ctx.check("the same object in the pristine instance is enabled", rChild != null && rChild.isEnabled(), "enabled", rChild);

        var p = (FXInstanceObject) objects.get(P);
        var placement = p.getPlacement();
        ctx.check("the instance knows what it was placed from", placement != null && placement.isPlaced(), "placed", placement);
        ctx.check("every override applied", placement != null && placement.unused().isPristine(), "none unused",
                placement == null ? null : placement.unused());
        ctx.check("the source's timeline is placed with it", !p.getTimeline().isEmpty()
                        && InstanceIds.xor(P, B_LATE).equals(p.getTimeline().tracks().getFirst().targetId()),
                "bound to P^b_late", p.getTimeline().tracks().isEmpty() ? "empty" : p.getTimeline().tracks().getFirst().targetId());
        var nested = (FXInstanceObject) objects.get(InstanceIds.xor(N, K));
        ctx.check("a nested instance carries its own source's timeline", nested != null && !nested.getTimeline().isEmpty()
                        && InstanceIds.xor(InstanceIds.xor(N, K), B_LATE).equals(nested.getTimeline().tracks().getFirst().targetId()),
                "bound to N^K^b_late", nested);
    }

    private static void checkEveryKind(TestContext ctx) {
        var kinds = new ArrayList<String>();
        for (var type : PhotonRegistries.FX_OBJECTS) {
            if (type != FXInstanceObject.TYPE) kinds.add(type.name());
        }
        kinds.add("rich");
        var spurious = new ArrayList<String>();
        for (var kind : kinds) {
            var fx = FXHelper.getFX(fx("kind_outer_" + kind));
            if (fx == null) {
                spurious.add(kind + ": did not load");
                continue;
            }
            var instance = fx.getFxData().objects().stream().filter(o -> o instanceof FXInstanceObject)
                    .map(o -> (FXInstanceObject) o).findFirst().orElse(null);
            var placement = instance == null ? null : instance.getPlacement();
            if (placement == null || !placement.isPlaced()) {
                spurious.add(kind + ": not placed");
                continue;
            }
            if (fx.getFxData().objects().size() != 2) {
                spurious.add(kind + ": placed " + (fx.getFxData().objects().size() - 1) + " objects");
            }
            var record = InstanceWriter.recordOf(fx.getFxData().serializedObjects(), placement, id -> Set.of());
            if (!record.isPristine()) {
                spurious.add(kind + ": " + record.modified().stream().map(m -> m.change().describePath()).toList()
                        + " removed " + record.removed());
            }
        }
        ctx.log("object kinds checked: " + kinds);
        ctx.check("no object kind reads back as overridden", spurious.isEmpty(), "none", spurious);
    }

    private static void checkResave(TestContext ctx) {
        var fx = FXHelper.getFX(fx("outer"));
        ctx.require("the outer effect loads", fx != null);
        var saved = fx.getFxData().serializeNBT(Platform.getFrozenRegistry());
        var ids = InstanceTags.objects(saved).stream().map(InstanceTags::id).toList();
        ctx.check("only the effect's own objects are written", ids.equals(List.of(P, R, N)), List.of(P, R, N), ids);
        var p = InstanceTags.objects(saved).getFirst();
        var record = InstanceRecord.read(InstanceTags.data(p).getCompound(InstanceTags.OVERRIDES));
        ctx.check("P's record says what it said", record.modified().size() == 3 && record.removed().isEmpty(),
                "3 modifications", record);
        ctx.check("the source's timeline is not written with it", !InstanceTags.data(p).contains(InstanceTags.TIMELINE),
                "no timeline", InstanceTags.data(p).getAllKeys());
        var again = new FX();
        again.setFxLocation(fx("outer"));
        var root = new CompoundTag();
        root.put("fxData", saved);
        again.deserializeNBT(Platform.getFrozenRegistry(), root);
        ctx.check("reading that again places the same objects", again.getFxData().objects().size() == fx.getFxData().objects().size(),
                fx.getFxData().objects().size(), again.getFxData().objects().size());
        ctx.check("and writes it the same way", saved.equals(again.getFxData().serializeNBT(Platform.getFrozenRegistry())),
                "equal", "different");
    }

    // ---- playing -----------------------------------------------------------------------------------

    private static void play(TestContext ctx, String name) {
        stop(ctx);
        var fx = FXHelper.getFX(fx(name));
        ctx.require("the " + name + " effect loads", fx != null);
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition().relative(player.getDirection(), 5));
        executor.setOffset(0, 1.5f, 0);
        executor.setAllowMulti(true);
        // before start: the signals at 0 fire while it starts
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

    private static FXRuntime runtime(TestContext ctx) {
        return ctx.get("runtime");
    }

    private static double clock(TestContext ctx) {
        return runtime(ctx).timelinePlayer.localTime();
    }

    private static IFXObject object(TestContext ctx, UUID id) {
        var object = runtime(ctx).objects.get(id);
        ctx.require("object " + id + " is in the runtime", object != null);
        return object;
    }

    private static Map<?, ?> prefetched() {
        try {
            var field = FXInstances.class.getDeclaredField("PREFETCHED");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long gameTime(TestContext ctx) {
        return ctx.requirePlayer().level().getGameTime();
    }

    /** The sound an instance's timeline plays now, or null. */
    @Nullable
    private static TimelineSoundInstance sound(TestContext ctx, UUID instance) {
        var runtime = ctx.<FXRuntime>get("runtime");
        if (runtime == null || !(runtime.objects.get(instance) instanceof FXInstanceObject placed)) return null;
        NestedTimelinePlayer player = placed.nestedPlayer();
        if (player == null) return null;
        Map<?, TimelineSoundInstance> playing = ctx.getField(player, "audioInstances");
        return playing.values().stream().findFirst().orElse(null);
    }

    private static boolean isStopped(TestContext ctx, @Nullable TimelineSoundInstance sound) {
        return sound != null && ctx.<Boolean>getField(sound, "stopRequested");
    }

    private static double nestedClock(TestContext ctx, UUID instance) {
        var player = ((FXInstanceObject) object(ctx, instance)).nestedPlayer();
        return player == null ? -1 : player.localTime();
    }

    private static void listenToSignals(TestContext ctx) {
        var fired = new ConcurrentHashMap<Fired, Integer>();
        ctx.put("fired", fired);
        PhotonSignals.Listener listener = (IEffectExecutor effect, String channel, String name, CompoundTag data, double time) -> {
            if (!"base".equals(channel) || effect != ctx.get("executor")) return;
            // which instance: the one whose clock is at this signal's time
            fired.merge(new Fired(null, name, time), 1, Integer::sum);
        };
        PhotonSignals.register(listener);
        ctx.put("signals", listener);
    }

    private static void checkAt5(TestContext ctx) {
        ctx.log("clocks at 5: outer %.1f, P %.1f, R %.1f, N^K %.1f".formatted(clock(ctx), nestedClock(ctx, P),
                nestedClock(ctx, R), nestedClock(ctx, InstanceIds.xor(N, K))));
        ctx.check("R's late emitter is on: the outer activator wins over R's own timeline (off until 10)",
                object(ctx, InstanceIds.xor(R, B_LATE)).isActive(), "active", "inactive");
        ctx.check("P waits for its control clip", !((FXInstanceObject) object(ctx, P)).isOuterActive(), "inactive", "active");
        ctx.check("so P's timeline has not started", nestedClock(ctx, P) <= 0, "<= 0", nestedClock(ctx, P));
        ctx.check("R's timeline runs from the start, beside the outer one", Math.abs(nestedClock(ctx, R) - clock(ctx)) <= 1,
                clock(ctx), nestedClock(ctx, R));
    }

    private static void checkAt25(TestContext ctx) {
        var outer = clock(ctx);
        var p = nestedClock(ctx, P);
        var k = nestedClock(ctx, InstanceIds.xor(N, K));
        ctx.log("clocks at 25: outer %.1f, P %.1f, N^K %.1f".formatted(outer, p, k));
        ctx.check("P's clock started at the control clip (20)", Math.abs(p - (outer - 20)) <= 1.5, outer - 20, p);
        ctx.check("so P's late emitter is still off (on from 10)", !object(ctx, InstanceIds.xor(P, B_LATE)).isActive(),
                "inactive", "active");
        // started by the evaluation that activated P, before anything under P ticked: not one tick of it went by
        var pLate = (ParticleEmitter) object(ctx, InstanceIds.xor(P, B_LATE));
        ctx.check("and has not emitted a single particle", pLate.getParticleAmount() == 0, 0, pLate.getParticleAmount());
        ctx.check("N^K's clock runs at the speed N is given (0.5)", Math.abs(k - outer * 0.5) <= 1.5, outer * 0.5, k);
        ctx.check("so N^K's late emitter is already on", object(ctx, InstanceIds.xor(InstanceIds.xor(N, K), B_LATE)).isActive(),
                "active", "inactive");
        var child = object(ctx, InstanceIds.xor(P, B_CHILD));
        ctx.check("the disabled child stays off though an activator binds it", !child.isActive() && !child.isVisible(),
                "off", child.isActive());
        var pEmit = (ParticleEmitter) object(ctx, InstanceIds.xor(P, B_EMIT));
        ctx.check("the runtime copy carries the override", pEmit.config.getDuration() == 77, 77, pEmit.config.getDuration());
    }

    private static void checkAt37(TestContext ctx) {
        ctx.log("clocks at 37: outer %.1f, P %.1f".formatted(clock(ctx), nestedClock(ctx, P)));
        ctx.check("P's late emitter is on now that P's clock passed 10", object(ctx, InstanceIds.xor(P, B_LATE)).isActive(),
                "active", "inactive");
    }

    private static void checkSignals(TestContext ctx) {
        Map<Fired, Integer> fired = ctx.get("fired");
        ctx.log("signals: " + fired);
        int start = fired.getOrDefault(new Fired(null, "start", 0), 0);
        int mid = fired.getOrDefault(new Fired(null, "mid", 15), 0);
        // R from 0, P from the control clip at 20, N^K from 0 at half speed: each once
        ctx.check("'start' fired once per instance", start == 3, 3, start);
        ctx.check("'mid' fired once per instance", mid == 3, 3, mid);
    }

    // ---- loading edge cases --------------------------------------------------------------------------

    private static void checkMissing(TestContext ctx) {
        var fx = FXHelper.getFX(fx("missing_outer"));
        ctx.require("an effect whose instance's source is missing still loads", fx != null);
        ctx.check("the instance places nothing", fx.getFxData().objects().size() == 1, 1, fx.getFxData().objects().size());
        var instance = (FXInstanceObject) fx.getFxData().objects().getFirst();
        var placement = instance.getPlacement();
        ctx.check("it is marked missing", placement != null && placement.status() == InstanceBaker.Status.MISSING,
                InstanceBaker.Status.MISSING, placement == null ? null : placement.status());
        var saved = fx.getFxData().serializeNBT(Platform.getFrozenRegistry());
        var overrides = InstanceTags.data(InstanceTags.objects(saved).getFirst()).getCompound(InstanceTags.OVERRIDES);
        ctx.check("its record is written back as it was", InstanceRecord.read(overrides).modified().size() == 1,
                "1 modification", overrides);
    }

    private static void checkMissingArrives(TestContext ctx) {
        try {
            writeBase("absent", 40);
        } catch (IOException e) {
            ctx.require("the source was written: " + e, false);
        }
        FXHelper.invalidate(List.of(fx("absent")));
        ctx.check("what placed it is forgotten too", FXHelper.getCachedFX(fx("missing_outer")) == null, "evicted", "cached");
        var fx = FXHelper.getFX(fx("missing_outer"));
        ctx.require("it loads again", fx != null);
        var ids = fx.getFxData().objects().stream().map(IFXObject::id).toList();
        ctx.check("the instance now places its source", ids.contains(InstanceIds.xor(M, B_LATE)), "M^b_late", ids);
        var emit = (ParticleEmitter) objects(fx).get(InstanceIds.xor(M, B_EMIT));
        ctx.check("and its saved override applies", emit != null && emit.config.getDuration() == 5, 5,
                emit == null ? null : emit.config.getDuration());
    }

    private static void checkCycle(TestContext ctx) {
        var start = System.nanoTime();
        var fx = FXHelper.getFX(fx("cycle_a"));
        ctx.log("cycle loaded in %d ms".formatted((System.nanoTime() - start) / 1_000_000));
        ctx.require("an effect in a cycle loads", fx != null);
        var ids = fx.getFxData().objects().stream().map(IFXObject::id).toList();
        var toB = id("to_b");
        var closing = InstanceIds.xor(toB, id("to_a"));
        ctx.check("it places the other side of the cycle", ids.contains(InstanceIds.xor(toB, id("cb_emit"))), "to_b^cb_emit", ids);
        ctx.check("and stops at the instance closing it", ids.contains(closing) && ids.size() == 4, 4, ids.size());
        ctx.check("which places nothing", fx.getFxData().objects().stream()
                .noneMatch(o -> o.transform()._getInternalParentID() != null && closing.equals(o.transform()._getInternalParentID())), "nothing", ids);
    }

    private static void checkInvalidation(TestContext ctx) {
        ctx.require("the outer effect is cached", FXHelper.getFX(fx("outer")) != null);
        try {
            writeBase("base", 300);
        } catch (IOException e) {
            ctx.require("the source was rewritten: " + e, false);
        }
        FXHelper.invalidate(List.of(fx("base")));
        ctx.check("the outer effect is forgotten with its source", FXHelper.getCachedFX(fx("outer")) == null, "evicted", "cached");
        ctx.check("and so is the source nested two levels down", FXHelper.getCachedFX(fx("nested")) == null
                && FXInstances.templates().cached(fx("nested")) == null, "evicted", "cached");
        var fx = FXHelper.getFX(fx("outer"));
        ctx.require("it loads again", fx != null);
        var objects = objects(fx);
        var rEmit = (ParticleEmitter) objects.get(InstanceIds.xor(R, B_EMIT));
        var pEmit = (ParticleEmitter) objects.get(InstanceIds.xor(P, B_EMIT));
        ctx.check("an instance follows the source's new value", rEmit.config.getDuration() == 300, 300, rEmit.config.getDuration());
        ctx.check("an override stays", pEmit.config.getDuration() == 77, 77, pEmit.config.getDuration());
        var kEmit = (ParticleEmitter) objects.get(InstanceIds.xor(InstanceIds.xor(N, K), B_EMIT));
        ctx.check("so does a nested instance", kEmit.config.getDuration() == 300, 300, kEmit.config.getDuration());
    }

    private static void checkUnreadable(TestContext ctx) {
        var fx = FXHelper.getFX(fx("unreadable_outer"));
        ctx.require("the effect loads", fx != null);
        var objects = objects(fx);
        ctx.check("what cannot be read is not there", !objects.containsKey(InstanceIds.xor(U, GHOST)), "absent", "there");
        var instance = (FXInstanceObject) objects.get(U);
        var placement = instance.getPlacement();
        ctx.require("the instance is placed", placement != null && placement.isPlaced());
        ctx.check("it knows what it could not read", placement.unreadable().equals(Set.of(GHOST, GHOST2)), Set.of(GHOST, GHOST2),
                placement.unreadable());
        var child = objects.get(InstanceIds.xor(U, id("ur_child")));
        ctx.check("what the two ghosts held waits under the instance, not at the root",
                child != null && U.equals(child.transform()._getInternalParentID()), U, child == null ? null : child.transform()._getInternalParentID());
        var added = objects.get(id("ur_added"));
        ctx.check("so does what this effect hung under the ghost", added != null && U.equals(added.transform()._getInternalParentID()),
                U, added == null ? null : added.transform()._getInternalParentID());

        var saved = fx.getFxData().serializeNBT(Platform.getFrozenRegistry());
        var ids = InstanceTags.objects(saved).stream().map(InstanceTags::id).toList();
        ctx.check("only the effect's own objects are written", ids.equals(List.of(U, id("ur_added"))), List.of(U, id("ur_added")), ids);
        var record = InstanceRecord.read(InstanceTags.data(InstanceTags.objects(saved).getFirst()).getCompound(InstanceTags.OVERRIDES));
        ctx.check("the ghost is not saved as removed", record.removed().isEmpty(), List.of(), record.removed());
        ctx.check("its rename is kept", record.modified().equals(List.of(set(GHOST, StringTag.valueOf("renamed ghost"), InstanceTags.NAME))),
                "the rename", record.modified());
        ctx.check("and the added object's anchor under it", record.added().equals(List.of(new InstanceRecord.Added(GHOST, id("ur_added")))),
                "under the ghost", record.added());
    }

    private static void deleteTree(File root) {
        if (!root.exists()) return;
        try (var walk = Files.walk(root.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
